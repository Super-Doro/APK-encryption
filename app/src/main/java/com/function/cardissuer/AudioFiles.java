package com.function.cardissuer;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class AudioFiles {
    private static final Set<String> ACTIVE_JOBS = ConcurrentHashMap.newKeySet();
    private final Context context;
    private final File root;

    AudioFiles(Context context) {
        this.context = context.getApplicationContext();
        root = new File(context.getCacheDir(), "audio-conversion");
    }

    File job(String name) throws IOException {
        if (name == null || !name.matches("job-[a-f0-9-]+")) throw new IOException("临时任务编号无效");
        ACTIVE_JOBS.add(name);
        return new File(root, name);
    }

    void clearAbandoned(String keep) throws IOException {
        File[] jobs = root.listFiles();
        if (jobs == null) return;
        for (File directory : jobs) {
            if (!directory.getName().equals(keep) && !ACTIVE_JOBS.contains(directory.getName())) delete(directory);
        }
    }

    void create(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建音频临时目录");
    }

    void save(File directory, List<AudioConverter.Output> outputs) throws IOException {
        JSONArray entries = new JSONArray();
        try {
            for (AudioConverter.Output output : outputs) {
                entries.put(new JSONObject().put("file", output.file.getName()).put("path", output.path));
            }
        } catch (JSONException error) { throw new IOException("保存转换结果失败", error); }
        AtomicFile manifest = new AtomicFile(new File(directory, "outputs.json"));
        FileOutputStream stream = null;
        try {
            stream = manifest.startWrite();
            stream.write(entries.toString().getBytes(StandardCharsets.UTF_8));
            manifest.finishWrite(stream);
        } catch (IOException error) {
            if (stream != null) manifest.failWrite(stream);
            throw error;
        }
    }

    List<AudioConverter.Output> restore(File directory) throws IOException {
        List<AudioConverter.Output> outputs = new ArrayList<>();
        try {
            JSONArray entries = new JSONArray(new String(Files.readAllBytes(
                    new File(directory, "outputs.json").toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                String file = entry.getString("file");
                String path = entry.getString("path");
                if (!file.matches("[0-9]+\\.[a-z0-9]+") || path.startsWith("/")) {
                    throw new IOException("转换临时文件信息无效");
                }
                for (String part : path.split("/", -1)) {
                    if (part.isEmpty() || !part.equals(AudioConverter.cleanName(part))) {
                        throw new IOException("转换文件名无效");
                    }
                }
                File source = new File(directory, file);
                if (!source.isFile() || source.length() == 0) throw new IOException("临时音频已被系统清理，请重新转换");
                outputs.add(new AudioConverter.Output(source, path));
            }
        } catch (JSONException error) { throw new IOException("恢复转换结果失败", error); }
        return outputs;
    }

    void delete(File directory) throws IOException {
        if (directory == null) return;
        Path base = root.toPath().toAbsolutePath().normalize();
        Path target = directory.toPath().toAbsolutePath().normalize();
        if (!target.startsWith(base) || target.equals(base)) throw new IOException("临时文件清理路径无效");
        try {
            if (!Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
            Files.walkFileTree(target, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path path, IOException error) throws IOException {
                    if (error != null) throw error;
                    Files.delete(path);
                    return FileVisitResult.CONTINUE;
                }
            });
        } finally { ACTIVE_JOBS.remove(directory.getName()); }
    }

    void export(List<AudioConverter.Output> outputs, Uri location, boolean folder, AudioConverter.Format format,
            Runnable checkCancelled, AudioConverter.Progress progress) throws IOException {
        Uri created = location;
        boolean complete = false;
        try {
            checkCancelled.run();
            if (folder) {
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(location,
                        DocumentsContract.getTreeDocumentId(location));
                String name = "Doro音频-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
                created = createDocument(parent, DocumentsContract.Document.MIME_TYPE_DIR, name);
            }
            Map<String, Uri> directories = new HashMap<>();
            directories.put("", created);
            byte[] buffer = new byte[256 * 1024];
            for (int i = 0; i < outputs.size(); i++) {
                checkCancelled.run();
                AudioConverter.Output output = outputs.get(i);
                Uri destination = created;
                if (folder) {
                    String[] parts = output.path.split("/");
                    Uri parent = created;
                    String path = "";
                    for (int j = 0; j < parts.length - 1; j++) {
                        checkCancelled.run();
                        path += parts[j] + "/";
                        Uri directory = directories.get(path);
                        if (directory == null) {
                            directory = createDocument(parent, DocumentsContract.Document.MIME_TYPE_DIR, parts[j]);
                            directories.put(path, directory);
                        }
                        parent = directory;
                    }
                    destination = createDocument(parent, format.mime, parts[parts.length - 1]);
                }
                try (InputStream input = new FileInputStream(output.file);
                        OutputStream saved = context.getContentResolver().openOutputStream(destination, "wt")) {
                    if (saved == null) throw new IOException("无法打开导出位置");
                    long done = 0;
                    int length;
                    int previous = -1;
                    while ((length = input.read(buffer)) != -1) {
                        checkCancelled.run();
                        saved.write(buffer, 0, length);
                        done += length;
                        int percent = (int) ((i + done / (double) Math.max(1, output.file.length())) * 100 / outputs.size());
                        if (percent != previous) {
                            previous = percent;
                            progress.update("正在导出 " + (i + 1) + "/" + outputs.size() + "\n" + output.path, percent);
                        }
                    }
                    saved.flush();
                }
            }
            checkCancelled.run();
            complete = true;
        } finally {
            if (!complete && created != null && (!folder || !created.equals(location))) {
                try {
                    if (!DocumentsContract.deleteDocument(context.getContentResolver(), created)) {
                        throw new IOException("无法删除未完成的导出文件");
                    }
                } catch (Exception error) {
                    try { ErrorLogStore.save(context, ErrorLogStore.describe(context, "未完成的音频导出清理失败",
                            "位置  " + created + "\n" + android.util.Log.getStackTraceString(error))); }
                    catch (IOException logError) { android.util.Log.e("DoroAudio", "日志保存失败", logError); }
                }
            }
        }
    }

    private Uri createDocument(Uri parent, String mime, String name) throws IOException {
        Uri document = DocumentsContract.createDocument(context.getContentResolver(), parent, mime, name);
        if (document == null) throw new IOException("无法创建导出文件 " + name);
        return document;
    }
}
