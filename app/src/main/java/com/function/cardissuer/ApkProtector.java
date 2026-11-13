package com.function.cardissuer;

import android.content.Context;
import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import com.function.protocol.ActivationKeys;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

final class ApkProtector {
    private static final String ROOT = "assets/function-protect/";

    private ApkProtector() {}

    static Result protect(Context context, File source, Consumer<String> progress) throws Exception {
        return protect(context, source, IsolationPlanner.AUTO, progress);
    }

    static Result protect(Context context, File source, String isolationPolicy, Consumer<String> progress) throws Exception {
        return protect(context, source, source.getName(), isolationPolicy, (stage, percent) -> progress.accept(stage));
    }

    static Result protect(Context context, File source, String sourceName, String isolationPolicy,
            ProtectionProgress progress) throws Exception {
        byte[] id = ActivationKeys.random(16);
        byte[] encryptionKey = ActivationKeys.random(32);
        String buildId = ActivationKeys.encode(id);
        File outputRoot = new File(context.getFilesDir(), "protected");
        if (!outputRoot.exists() && !outputRoot.mkdirs()) throw new IOException("无法创建输出目录");
        File staging = new File(context.getCacheDir(), "protect-" + buildId);
        if (!staging.mkdir()) throw new IOException("无法创建打包临时目录");
        boolean committed = false;
        try (ZipFile original = new ZipFile(source)) {
            progress.update("正在检查 APK", 2);
            if (original.getEntry(ROOT + "config.json") != null) throw new IOException("请选择未经本工具加密的原始 APK");
            ZipEntry manifestEntry = original.getEntry("AndroidManifest.xml");
            if (manifestEntry == null) throw new IOException("文件不是有效 APK");
            BinaryManifest manifest = new BinaryManifest(read(original.getInputStream(manifestEntry), Integer.MAX_VALUE - 8));
            byte[] patchedManifest = manifest.protect();
            List<? extends ZipEntry> entries = Collections.list(original.entries());
            Set<String> unique = new HashSet<>();
            List<String> dex = new ArrayList<>();
            long resourceTotal = 0;
            long codeTotal = 0;
            for (ZipEntry entry : entries) {
                String name = entry.getName();
                if (!unique.add(name) || name.contains("\\") || name.startsWith("/")
                        || Arrays.asList(name.split("/")).contains("..")) {
                    throw new IOException("APK 含无效或重复的 ZIP 路径");
                }
                if (entry.getSize() < 0) throw new IOException("APK 中的 ZIP 长度信息无效");
                if (isDex(name)) {
                    dex.add(name);
                    codeTotal = Math.addExact(codeTotal, entry.getSize());
                } else resourceTotal = Math.addExact(resourceTotal, entry.getSize());
            }
            if (dex.isEmpty()) throw new IOException("APK 缺少可加载的 DEX 代码");
            dex.sort(java.util.Comparator.comparingInt(ApkProtector::dexNumber));
            final long resourceBytes = resourceTotal;
            final long codeBytes = codeTotal;
            IsolationPlanner planner = new IsolationPlanner(manifest, isolationPolicy);
            if (!manifest.isolatedServices.isEmpty()) {
                long analyzed = 0;
                for (String name : dex) {
                    progress.update("正在分析隔离服务", ProtectionProgress.percent(analyzed, codeBytes, 5, 20));
                    planner.inspect(read(original.getInputStream(original.getEntry(name)), Integer.MAX_VALUE - 8));
                    analyzed += original.getEntry(name).getSize();
                }
            }
            JSONArray isolationPlans = planner.plans();
            String strategyReport = IsolationPlanner.report(isolationPolicy, isolationPlans);
            KeyPair issuer = IssuerKeyStore.loadOrCreate();
            JSONObject config = new JSONObject();
            JSONObject dexSizes = new JSONObject();
            config.put("version", 4).put("dexFormat", "chunked-v1").put("dexSizes", dexSizes)
                    .put("id", buildId).put("packageName", manifest.packageName)
                    .put("launcher", manifest.launcher).put("factory", manifest.originalFactory)
                    .put("publicKey", Base64.getEncoder().encodeToString(issuer.getPublic().getEncoded()))
                    .put("isolatedServices", new JSONArray(manifest.isolatedServices))
                    .put("isolationPlans", isolationPlans)
                    .put("dex", new JSONArray(dex));
            File unsigned = new File(staging, "unsigned.apk");
            try (CountingOutputStream counting = new CountingOutputStream(new FileOutputStream(unsigned));
                    ZipOutputStream zip = new ZipOutputStream(counting)) {
                long copied = 0;
                for (ZipEntry entry : entries) {
                    String name = entry.getName();
                    if (name.equals("AndroidManifest.xml") || isDex(name) || signatureEntry(name) || entry.isDirectory()) continue;
                    // Native libraries are extracted by Android. Resource tables remain stored and aligned.
                    ZipEntry copy = new ZipEntry(name);
                    if (entry.getMethod() == ZipEntry.STORED && !name.startsWith("lib/")) {
                        copy.setMethod(ZipEntry.STORED);
                        copy.setSize(entry.getSize());
                        copy.setCompressedSize(entry.getSize());
                        copy.setCrc(entry.getCrc());
                        align(copy, counting.count);
                    }
                    zip.putNextEntry(copy);
                    long before = copied;
                    try (InputStream input = original.getInputStream(entry)) {
                        copied += copy(input, zip, done -> progress.update("正在复制应用资源",
                                ProtectionProgress.percent(before + done, resourceBytes, 20, 50)));
                    }
                    zip.closeEntry();
                }
                put(zip, "AndroidManifest.xml", patchedManifest);
                put(zip, ROOT + "LICENSE.txt", read(context.getAssets().open("function-runtime-LICENSE.txt"), 65536));
                put(zip, ROOT + "NOTICE.txt", read(context.getAssets().open("function-runtime-NOTICE.txt"), 65536));
                put(zip, "classes.dex", IsolatedDexAdapter.prepareShell(
                        read(context.getAssets().open("function-shell.dex"), 4 * 1024 * 1024)));
                long processed = 0;
                for (int i = 0; i < dex.size(); i++) {
                    String name = dex.get(i);
                    String stage = "正在加密代码 " + (i + 1) + " / " + dex.size();
                    progress.update(stage, ProtectionProgress.percent(processed, codeBytes, 50, 85));
                    long before = processed;
                    byte[] plain = null;
                    InputStream code;
                    if (!manifest.isolatedServices.isEmpty()) {
                        plain = read(original.getInputStream(original.getEntry(name)), Integer.MAX_VALUE - 8);
                        if (plain.length < 8 || plain[0] != 'd' || plain[1] != 'e' || plain[2] != 'x') {
                            throw new IOException("原 APK 的 DEX 格式不受支持");
                        }
                        plain = IsolatedDexAdapter.adapt(plain, planner::isContextType);
                        dexSizes.put(name, plain.length);
                        code = new java.io.ByteArrayInputStream(plain);
                    } else {
                        dexSizes.put(name, original.getEntry(name).getSize());
                        java.io.PushbackInputStream checked = new java.io.PushbackInputStream(
                                original.getInputStream(original.getEntry(name)), 8);
                        byte[] header = new byte[8];
                        new java.io.DataInputStream(checked).readFully(header);
                        if (header[0] != 'd' || header[1] != 'e' || header[2] != 'x') throw new IOException("DEX 格式无效");
                        checked.unread(header);
                        code = checked;
                    }
                    zip.putNextEntry(new ZipEntry(ROOT + name + ".enc"));
                    try (InputStream input = code) {
                        ActivationKeys.encryptStream(input, zip, encryptionKey, ActivationKeys.dexAad(id, name),
                                done -> progress.update(stage, ProtectionProgress.percent(before + done, codeBytes, 50, 85)));
                    } finally { if (plain != null) Arrays.fill(plain, (byte) 0); }
                    zip.closeEntry();
                    processed += original.getEntry(name).getSize();
                }
                put(zip, ROOT + "config.json", config.toString().getBytes(StandardCharsets.UTF_8));
            }
            progress.update("正在签名安装包", 88);
            File signed = new File(staging, "protected.apk");
            new ApkSigner.Builder(Collections.singletonList(ApkSigningKeyStore.signer()))
                    .setInputApk(unsigned).setOutputApk(signed).setMinSdkVersion(29)
                    .setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(true)
                    .setV4SigningEnabled(false).setAlignmentPreserved(true).build().sign();
            progress.update("正在校验安装包", 94);
            ApkVerifier.Result verified = new ApkVerifier.Builder(signed).setMinCheckedPlatformVersion(29).build().verify();
            if (!verified.isVerified()) throw new IOException("生成包签名校验失败 " + verified.getErrors());
            String token = ActivationKeys.issue(id, encryptionKey, manifest.packageName, issuer.getPrivate());
            ActivationKeys.open(token, id, manifest.packageName, issuer.getPublic());
            JSONObject record = new JSONObject().put("id", buildId).put("packageName", manifest.packageName)
                    .put("sourceName", sourceName)
                    .put("strategyReport", strategyReport)
                    .put("createdAt", System.currentTimeMillis()).put("key", token);
            Files.write(new File(staging, "result.json").toPath(), record.toString().getBytes(StandardCharsets.UTF_8));
            Files.write(new File(staging, "activation-key.txt").toPath(), (token + "\n").getBytes(StandardCharsets.UTF_8));
            if (!unsigned.delete()) throw new IOException("无法清理打包临时文件");
            File destination = new File(outputRoot, buildId);
            if (!staging.renameTo(destination)) throw new IOException("无法保存生成结果");
            committed = true;
            progress.update("加密完成", 100);
            return Result.read(destination);
        } finally {
            Arrays.fill(encryptionKey, (byte) 0);
            if (!committed) deleteStaging(staging);
        }
    }

    private static boolean isDex(String name) { return name.matches("classes(?:[1-9][0-9]*)?\\.dex"); }
    private static int dexNumber(String name) {
        String number = name.substring(7, name.length() - 4);
        return number.isEmpty() ? 1 : Integer.parseInt(number);
    }
    private static boolean signatureEntry(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("META-INF/")) return false;
        return upper.equals("META-INF/MANIFEST.MF") || upper.endsWith(".SF")
                || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC");
    }
    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }
    private static void align(ZipEntry entry, long offset) {
        long start = offset + 30 + entry.getName().getBytes(StandardCharsets.UTF_8).length;
        if (start % 4 == 0) return;
        int padding = (int) ((4 - (start + 4) % 4) % 4);
        byte[] extra = new byte[4 + padding];
        extra[0] = (byte) 0xff;
        extra[1] = (byte) 0xff;
        extra[2] = (byte) padding;
        entry.setExtra(extra);
    }
    static byte[] read(InputStream input, int limit) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copy(source, output, limit);
            return output.toByteArray();
        }
    }
    static void copy(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[65536];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > limit) throw new IOException("文件超过支持的大小");
            output.write(buffer, 0, count);
        }
    }
    static long copy(InputStream input, OutputStream output, java.util.function.LongConsumer progress) throws IOException {
        byte[] buffer = new byte[65536];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
            total += count;
            progress.accept(total);
        }
        return total;
    }
    private static void deleteStaging(File directory) {
        File[] contents = directory.listFiles();
        if (contents != null) for (File file : contents) file.delete();
        directory.delete();
    }

    static List<Result> history(Context context) {
        List<Result> results = new ArrayList<>();
        File[] directories = new File(context.getFilesDir(), "protected").listFiles();
        if (directories != null) for (File directory : directories) {
            try { results.add(Result.read(directory)); }
            catch (Exception error) { android.util.Log.w("FunctionProtect", "Unreadable history item", error); }
        }
        results.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        return results;
    }

    static final class Result {
        final File apk;
        final String id;
        final String packageName;
        final String key;
        final long createdAt;
        final boolean script;
        final String strategyReport;
        final String sourceName;
        private Result(File directory, JSONObject record) throws Exception {
            script = record.optString("kind").equals("sh");
            apk = new File(directory, script ? "protected.sh" : "protected.apk");
            if (!apk.isFile()) throw new IOException("缺少生成 APK");
            id = record.getString("id");
            packageName = record.getString("packageName");
            key = record.getString("key");
            createdAt = record.getLong("createdAt");
            strategyReport = record.optString("strategyReport", "此记录未保存隔离策略报告。");
            sourceName = record.optString("sourceName", script ? packageName.replaceFirst("^sh/", "") : packageName + ".apk");
        }
        static Result read(File directory) throws Exception {
            return new Result(directory, new JSONObject(new String(readRecord(directory), StandardCharsets.UTF_8)));
        }
        private static byte[] readRecord(File directory) throws IOException {
            return ApkProtector.read(new java.io.FileInputStream(new File(directory, "result.json")), Integer.MAX_VALUE - 8);
        }
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        long count;
        CountingOutputStream(OutputStream output) { super(output); }
        @Override public void write(int value) throws IOException { out.write(value); count++; }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            out.write(bytes, offset, length);
            count += length;
        }
    }
}
