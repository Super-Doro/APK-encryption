package com.function.cardissuer;

import android.content.Context;
import android.util.Base64OutputStream;
import com.function.protocol.ActivationKeys;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.Base64;
import java.util.function.Consumer;
import org.json.JSONObject;

final class ScriptProtector {
    private ScriptProtector() {}

    static ApkProtector.Result protect(Context context, File source, String name, Consumer<String> progress) throws Exception {
        return protect(context, source, name, (stage, percent) -> progress.accept(stage));
    }

    static ApkProtector.Result protect(Context context, File source, String name, ProtectionProgress progress) throws Exception {
        return protect(context, source, name, 0, 0, progress);
    }

    static ApkProtector.Result protect(Context context, File source, String name, long issuedAt,
            long expiresAt, ProtectionProgress progress) throws Exception {
        progress.update("准备加密脚本", 0);
        byte[] id = ActivationKeys.random(16);
        byte[] secret = ActivationKeys.random(32);
        String buildId = ActivationKeys.encode(id);
        String identity = "sh/" + name;
        File root = new File(context.getFilesDir(), "protected");
        if (!root.exists() && !root.mkdirs()) throw new java.io.IOException("无法创建输出目录");
        File directory = new File(context.getCacheDir(), "protect-" + buildId);
        if (!directory.mkdir()) throw new java.io.IOException("无法创建临时目录");
        boolean committed = false;
        try {
            progress.update("正在加载签发密钥", 1);
            KeyPair issuer = IssuerKeyStore.loadOrCreate();
            progress.update("正在签发专属密钥", 2);
            ActivationKeys.Policy keyPolicy = expiresAt > 0 ? ActivationKeys.issueCompact(id, secret,
                    identity, issuer.getPrivate(), issuedAt, expiresAt) : null;
            String token = keyPolicy == null
                    ? ActivationKeys.issue(id, secret, identity, issuer.getPrivate(), issuedAt, expiresAt)
                    : ActivationKeys.compactToken(secret);
            JSONObject config = new JSONObject().put("version", 4).put("payloadFormat", "chunked-v1")
                    .put("activationPolicy", "permanent-after-activation")
                    .put("id", buildId).put("packageName", identity)
                    .put("issuedAt", issuedAt).put("expiresAt", expiresAt)
                    .put("publicKey", Base64.getEncoder().encodeToString(issuer.getPublic().getEncoded()));
            if (keyPolicy != null) config.put("keyHash", keyPolicy.keyHash).put("keySignature", keyPolicy.signature);
            progress.update("正在读取脚本运行时", 3);
            byte[] dex = ApkProtector.read(context.getAssets().open("function-script.dex"), Integer.MAX_VALUE - 8);
            String header = "#!/system/bin/sh\n# FUNCTION_CONFIG="
                    + Base64.getEncoder().encodeToString(config.toString().getBytes(StandardCharsets.UTF_8)) + "\n"
                    + "FUNCTION_SOURCE=\"$0\"\nFUNCTION_KEY=\"\"\n"
                    + "case \"${1-}\" in FA2.*|FA3.*|DK1.*) FUNCTION_KEY=\"$1\"; shift ;; esac\n"
                    + "FUNCTION_TMP=\"$(mktemp -d \"${TMPDIR:-/data/local/tmp}/function-sh.XXXXXX\")\" || exit 1\n"
                    + "trap 'rm -f \"$FUNCTION_TMP/runner.dex\" \"$FUNCTION_TMP\"/source-*.sh; rmdir \"$FUNCTION_TMP\" 2>/dev/null' EXIT HUP INT TERM\n"
                    + "sed -n '/^__FUNCTION_DEX__$/,/^__FUNCTION_PAYLOAD__$/{/^__FUNCTION_DEX__$/d;/^__FUNCTION_PAYLOAD__$/q;p;}' \"$FUNCTION_SOURCE\" | base64 -d > \"$FUNCTION_TMP/runner.dex\" || exit 1\n"
                    + "chmod 444 \"$FUNCTION_TMP/runner.dex\" || exit 1\n"
                    + "CLASSPATH=\"$FUNCTION_TMP/runner.dex\" /system/bin/app_process /system/bin com.function.script.ScriptRunner \"$FUNCTION_SOURCE\" \"$FUNCTION_KEY\" \"$FUNCTION_TMP\" \"$@\"\n"
                    + "exit \"$?\"\n__FUNCTION_DEX__\n";
            try (FileOutputStream file = new FileOutputStream(new File(directory, "protected.sh"));
                    BufferedOutputStream output = new BufferedOutputStream(file, 256 * 1024)) {
                progress.update("正在写入脚本头部", 4);
                output.write(header.getBytes(StandardCharsets.UTF_8));
                output.write(Base64.getMimeEncoder(76, new byte[]{'\n'}).encode(dex));
                output.write("\n__FUNCTION_PAYLOAD__\n".getBytes(StandardCharsets.UTF_8));
                // Encode whole encrypted chunks and buffer filesystem writes for large scripts.
                try (OutputStream encoded = new Base64OutputStream(output, android.util.Base64.NO_CLOSE);
                        FileInputStream input = new FileInputStream(source)) {
                    progress.update("正在加密脚本", 5);
                    ActivationKeys.encryptStream(input, encoded, secret, ActivationKeys.dexAad(id, "script.sh"),
                            done -> progress.update("正在加密脚本", ProtectionProgress.percent(done, source.length(), 5, 93)));
                }
                output.write('\n');
                progress.update("正在写入加密文件", 94);
                output.flush();
                file.getFD().sync();
            }
            progress.update("正在保存专属密钥", 96);
            ActivationKeys.ensureValidTime(token, keyPolicy);
            Files.write(new File(directory, "activation-key.txt").toPath(), (token + "\n").getBytes(StandardCharsets.UTF_8));
            JSONObject record = new JSONObject().put("kind", "sh").put("id", buildId).put("packageName", identity)
                    .put("sourceName", name).put("createdAt", System.currentTimeMillis()).put("key", token);
            record.put("issuedAt", issuedAt).put("expiresAt", expiresAt);
            record.put("activationPolicy", "permanent-after-activation");
            progress.update("正在保存生成记录", 97);
            Files.write(new File(directory, "result.json").toPath(), record.toString().getBytes(StandardCharsets.UTF_8));
            File destination = new File(root, buildId);
            progress.update("正在提交生成结果", 98);
            if (!directory.renameTo(destination)) throw new java.io.IOException("无法保存加密脚本");
            committed = true;
            progress.update("加密完成", 100);
            return ApkProtector.Result.read(destination);
        } finally {
            Arrays.fill(secret, (byte) 0);
            if (!committed) {
                File[] files = directory.listFiles();
                if (files != null) for (File file : files) file.delete();
                directory.delete();
            }
        }
    }

}
