package com.function.cardissuer;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.LogRedirectionStrategy;
import com.arthenica.ffmpegkit.Level;
import com.arthenica.ffmpegkit.MediaInformation;
import com.arthenica.ffmpegkit.MediaInformationSession;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.StreamInformation;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;

final class AudioConverter {
    enum Format {
        MP3("MP3", "mp3", "audio/mpeg", "libmp3lame", "-q:a", "2", "-ar", "44100", "-ac", "2"),
        M4A("M4A · AAC", "m4a", "audio/mp4", "aac", "-b:a", "192k", "-ar", "48000", "-ac", "2"),
        AAC("AAC", "aac", "audio/aac", "aac", "-b:a", "192k", "-ar", "48000", "-ac", "2"),
        WAV("WAV", "wav", "audio/wav", "pcm_s16le"),
        FLAC("FLAC", "flac", "audio/flac", "flac"),
        OGG("OGG · Vorbis", "ogg", "audio/ogg", "libvorbis", "-q:a", "5", "-ar", "48000", "-ac", "2"),
        OPUS("Opus", "opus", "audio/ogg", "libopus", "-b:a", "160k", "-ar", "48000", "-ac", "2"),
        WMA("WMA", "wma", "audio/x-ms-wma", "wmav2", "-b:a", "192k", "-ar", "44100", "-ac", "2"),
        AIFF("AIFF", "aiff", "audio/aiff", "pcm_s16be"),
        ALAC("ALAC · M4A", "m4a", "audio/mp4", "alac");

        final String title;
        final String extension;
        final String mime;
        final String codec;
        final String[] options;

        Format(String title, String extension, String mime, String codec, String... options) {
            this.title = title;
            this.extension = extension;
            this.mime = mime;
            this.codec = codec;
            this.options = options;
        }
    }

    interface Progress { void update(String stage, int percent); }

    static final class Source {
        final Uri uri;
        final String path;
        final String type;
        final double duration;

        Source(Uri uri, String path, String type, double duration) {
            this.uri = uri;
            this.path = path;
            this.type = type;
            this.duration = duration;
        }
    }

    static final class Selection {
        final List<Source> sources = new ArrayList<>();
        int scanned;
        int skipped;
        int unreadable;
    }

    static final class Output {
        final File file;
        final String path;
        Output(File file, String path) { this.file = file; this.path = path; }
    }

    private final Context context;
    private volatile boolean cancelled;
    private volatile long sessionId;

    AudioConverter(Context context) {
        this.context = context.getApplicationContext();
        FFmpegKitConfig.setSessionHistorySize(4);
    }

    void cancel() {
        cancelled = true;
        long active = sessionId;
        if (active != 0) FFmpegKit.cancel(active);
    }

    void checkCancelled() {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    Selection scan(Uri uri, boolean folder, Progress progress) throws IOException {
        Selection selection = new Selection();
        if (!folder) {
            String name = documentName(uri);
            Source source = probe(uri, cleanName(name));
            if (source == null) throw new IOException("所选文件中没有可转换的音频，或文件已经损坏");
            selection.sources.add(source);
            selection.scanned = 1;
            return selection;
        }
        String rootId = DocumentsContract.getTreeDocumentId(uri);
        ArrayDeque<Directory> directories = new ArrayDeque<>();
        directories.add(new Directory(rootId, ""));
        Set<String> visited = new HashSet<>();
        String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE};
        while (!directories.isEmpty()) {
            checkCancelled();
            Directory directory = directories.removeFirst();
            if (!visited.add(directory.id)) continue;
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, directory.id);
            try (Cursor cursor = context.getContentResolver().query(children, columns, null, null, null)) {
                if (cursor == null) throw new IOException("无法读取文件夹内容");
                while (cursor.moveToNext()) {
                    checkCancelled();
                    String id = cursor.getString(0);
                    String path = directory.path + cleanName(cursor.getString(1));
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) {
                        directories.addLast(new Directory(id, path + "/"));
                        continue;
                    }
                    selection.scanned++;
                    progress.update("识别 " + selection.scanned + " 个文件 · 已找到 "
                            + selection.sources.size() + " 个音频\n" + path, -1);
                    try {
                        Source source = probe(DocumentsContract.buildDocumentUriUsingTree(uri, id), path);
                        if (source == null) selection.skipped++;
                        else selection.sources.add(source);
                    } catch (IOException | SecurityException error) {
                        selection.unreadable++;
                        log("音频识别失败", path, error);
                    }
                }
            } catch (SecurityException | IOException error) {
                if (directory.id.equals(rootId)) throw new IOException("无法读取所选文件夹", error);
                selection.unreadable++;
                log("子文件夹读取失败", directory.path, error);
            }
        }
        checkCancelled();
        return selection;
    }

    private Source probe(Uri uri, String path) throws IOException {
        checkCancelled();
        try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
            if (stream == null) throw new IOException("无法读取源文件 " + path);
            inspectHeader(readHeader(stream), path);
        }
        String input = FFmpegKitConfig.getSafParameterForRead(context, uri);
        MediaInformationSession session = MediaInformationSession.create(new String[]{
                "-v", "error", "-probesize", "32M", "-analyzeduration", "30000000",
                "-print_format", "json", "-show_format", "-show_streams", input}, null, log -> {
                    if (log.getLevel() != Level.AV_LOG_STDERR) {
                        android.util.Log.w("DoroFFprobe", log.getMessage());
                    }
                });
        sessionId = session.getSessionId();
        try {
            checkCancelled();
            FFmpegKitConfig.getMediaInformationExecute(session, 5000);
            checkCancelled();
            MediaInformation information = session.getMediaInformation();
            if (!ReturnCode.isSuccess(session.getReturnCode()) || information == null) return null;
            StreamInformation audio = null;
            for (StreamInformation stream : information.getStreams()) {
                if ("audio".equals(stream.getType()) && audio == null) audio = stream;
                if ("video".equals(stream.getType())) {
                    org.json.JSONObject disposition = stream.getAllProperties().optJSONObject("disposition");
                    if (disposition == null || disposition.optInt("attached_pic") != 1) return null;
                }
            }
            if (audio == null) return null;
            String container = information.getFormat();
            String codec = audio.getCodec();
            String type = (container == null ? "音频" : container.toUpperCase(Locale.ROOT))
                    + (codec == null ? "" : " · " + codec.toUpperCase(Locale.ROOT));
            double duration = 0;
            try { duration = Double.parseDouble(information.getDuration()); }
            catch (NumberFormatException | NullPointerException ignored) { }
            return new Source(uri, path, type, Double.isFinite(duration) ? duration : 0);
        } catch (IllegalArgumentException error) {
            throw new IOException("无法识别 " + path, error);
        } finally { sessionId = 0; }
    }

    Output convert(Source source, Format format, File directory, int index, Set<String> names,
            Progress progress) throws IOException {
        checkCancelled();
        String name = outputName(source.path, format.extension, names);
        File inputFile = new File(directory, index + "-source.bin");
        File destination = new File(directory, index + "." + format.extension);
        boolean success = false;
        try {
            String header = copySource(source, inputFile, progress);
            List<String> command = new ArrayList<>(Arrays.asList("-hide_banner", "-nostdin", "-loglevel", "warning",
                    "-y", "-abort_on", "empty_output", "-probesize", "32M", "-analyzeduration", "30000000",
                    "-i", inputFile.getAbsolutePath(), "-map", "0:a:0", "-vn", "-sn", "-dn",
                    "-map_metadata", "0", "-c:a", format.codec));
            command.addAll(Arrays.asList(format.options));
            command.add(destination.getAbsolutePath());
            FFmpegSession session = FFmpegSession.create(command.toArray(new String[0]), null,
                    log -> android.util.Log.w("DoroFFmpeg", log.getMessage()), statistics -> {
                if (source.duration > 0) {
                    int percent = (int) Math.min(99, 10 + statistics.getTime() * 89 / (source.duration * 1000));
                    progress.update(source.path, Math.max(10, percent));
                }
            }, LogRedirectionStrategy.NEVER_PRINT_LOGS);
            sessionId = session.getSessionId();
            checkCancelled();
            progress.update(source.path + "\n正在编码", 10);
            FFmpegKitConfig.ffmpegExecute(session);
            checkCancelled();
            if (!ReturnCode.isSuccess(session.getReturnCode()) || !destination.isFile() || destination.length() == 0) {
                String nativeLog = session.getAllLogsAsString();
                String failureTrace = session.getFailStackTrace();
                boolean noFrames = nativeLog.contains("after EOF") || nativeLog.contains("empty_output")
                        || nativeLog.contains("Output file is empty") || nativeLog.contains("Nothing was written");
                throw new IOException((noFrames ? "未解码出有效音频数据，源文件可能不完整、损坏或仍包含加密数据。\n" : "")
                        + "文件  " + source.path
                        + "\n输入格式  " + source.type
                        + "\n实际读取字节  " + inputFile.length()
                        + "\n文件头  " + header
                        + "\n目标格式  " + format.title + " / " + format.codec
                        + "\n返回码  " + session.getReturnCode() + " / " + session.getState()
                        + "\n输出字节  " + destination.length()
                        + "\nFFmpeg  " + FFmpegKitConfig.getFFmpegVersion()
                        + "\n命令  " + session.getCommand()
                        + "\n\n引擎日志\n" + (nativeLog.isBlank() ? "引擎未返回日志" : nativeLog)
                        + (failureTrace == null || failureTrace.isBlank() ? "" : "\n\n" + failureTrace));
            }
            success = true;
            return new Output(destination, name);
        } finally {
            sessionId = 0;
            if (inputFile.exists() && !inputFile.delete()) {
                log("音频源文件缓存清理失败", source.path, new IOException("无法删除 " + inputFile));
            }
            if (!success && destination.exists() && !destination.delete()) {
                android.util.Log.w("DoroAudio", "无法清理转换未完成的文件 " + destination);
            }
        }
    }

    private String copySource(Source source, File destination, Progress progress) throws IOException {
        long expected = sourceSize(source.uri);
        progress.update(source.path + "\n正在读取源文件", 0);
        try (InputStream input = context.getContentResolver().openInputStream(source.uri);
                OutputStream output = new FileOutputStream(destination)) {
            if (input == null) throw new IOException("系统未返回源文件数据 " + source.path);
            byte[] header = readHeader(input);
            String signature = inspectHeader(header, source.path);
            output.write(header);
            byte[] buffer = new byte[256 * 1024];
            long done = header.length;
            int previous = -1;
            int length;
            while ((length = input.read(buffer)) != -1) {
                checkCancelled();
                output.write(buffer, 0, length);
                done += length;
                int percent = expected > 0 ? (int) Math.min(9, done * 9.0 / expected) : 0;
                if (percent != previous) {
                    previous = percent;
                    progress.update(source.path + "\n正在读取源文件", percent);
                }
            }
            checkCancelled();
            return signature;
        }
    }

    private long sourceSize(Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0);
        } catch (RuntimeException error) { android.util.Log.w("DoroAudio", "无法获取源文件大小", error); }
        return -1;
    }

    private byte[] readHeader(InputStream input) throws IOException {
        byte[] header = new byte[16];
        int done = 0;
        while (done < header.length) {
            checkCancelled();
            int length = input.read(header, done, header.length - done);
            if (length < 0) break;
            done += length;
        }
        return Arrays.copyOf(header, done);
    }

    private String inspectHeader(byte[] header, String path) throws IOException {
        if (header.length == 0) throw new IOException("源文件为空，或文件提供程序返回了空内容\n文件  " + path);
        StringBuilder signature = new StringBuilder(header.length * 2);
        for (byte value : header) signature.append(String.format(Locale.ROOT, "%02X", value & 255));
        String result = signature.toString();
        // Compare file signatures, including files whose extension has been changed.
        if (result.equals("7CD532EB86027F4BA8AFA68E0FFF9914") || result.equals("0528BC96E9E45A4391AABDD07AF53631")) {
            throw new IOException("文件内容为 KGM/VPR 加密音乐，请使用来源播放器导出的普通音频文件进行转换。\n文件  " + path);
        }
        return result;
    }

    String documentName(Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        }
        return "音频文件";
    }

    static String cleanName(String name) {
        if (name == null || name.isBlank()) return "未命名";
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return cleaned.equals(".") || cleaned.equals("..") || cleaned.isEmpty() ? "未命名" : cleaned;
    }

    private static String outputName(String source, String extension, Set<String> names) {
        int slash = source.lastIndexOf('/');
        String parent = source.substring(0, slash + 1);
        String base = source.substring(slash + 1);
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        String result = parent + base + "." + extension;
        int suffix = 2;
        while (!names.add(result.toLowerCase(Locale.ROOT))) {
            result = parent + base + " (" + suffix++ + ")." + extension;
        }
        return result;
    }

    void log(String title, String file, Throwable error) {
        try { ErrorLogStore.save(context, ErrorLogStore.describe(context, title,
                "文件  " + file + "\n\n" + android.util.Log.getStackTraceString(error))); }
        catch (IOException logError) { android.util.Log.e("DoroAudio", "日志保存失败", logError); }
    }

    private static final class Directory {
        final String id;
        final String path;
        Directory(String id, String path) { this.id = id; this.path = path; }
    }
}
