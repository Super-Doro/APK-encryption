package com.function.cardissuer;

import android.content.Context;
import android.os.Build;
import android.util.AtomicFile;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import android.os.Handler;
import android.os.Looper;

final class ErrorLogStore {
    private static final int MAX_LOG_BYTES = 256 * 1024;
    private static final int MAX_RECORD_FILES = 200;
    private static final long MAX_RECORD_BYTES = 8L * 1024 * 1024;
    private static final int MAX_RUNTIME_FILES = 16;
    private static final Set<Runnable> listeners = new CopyOnWriteArraySet<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean notificationPending = new AtomicBoolean();
    private static final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(256), task -> {
                Thread thread = new Thread(task, "DoroLogWriter");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.DiscardOldestPolicy());
    private static File runtimeFile;
    private static FileOutputStream runtimeOutput;
    private static long runtimeBytes;
    private static long generation;
    private ErrorLogStore() {}

    static String versionName(Context context) {
        try { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (android.content.pm.PackageManager.NameNotFoundException error) { return "未知"; }
    }

    static String describe(Context context, String title, String body) {
        return title + "\nDoro加密 v" + versionName(context)
                + "\n时间  " + DateFormat.getDateTimeInstance().format(new Date())
                + "\n设备  " + Build.MANUFACTURER + " " + Build.MODEL
                + "\nAndroid  " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT
                + "\n\n" + body;
    }

    static synchronized void save(Context context, String report) throws IOException {
        android.util.Log.e("DoroEncrypt", report);
        writeRecord(context, report, "error");
    }

    static void event(Context context, String component, String title, String detail) {
        Context app = context.getApplicationContext();
        String report = describe(app, title, "模块  " + component + "\n" + detail);
        long queuedGeneration;
        synchronized (ErrorLogStore.class) { queuedGeneration = generation; }
        writer.execute(() -> {
            synchronized (ErrorLogStore.class) {
                if (queuedGeneration != generation) return;
                try { writeRecord(app, report, "event"); }
                catch (IOException error) { android.util.Log.e("DoroLogs", "无法保存操作日志", error); }
            }
        });
    }

    private static File directory(Context context) throws IOException {
        File directory = new File(context.getFilesDir(), "error-logs");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建日志目录");
        return directory;
    }

    private static byte[] limited(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_LOG_BYTES) return bytes;
        int chars = Math.min(text.length() / 2, MAX_LOG_BYTES / 8);
        return (text.substring(0, chars) + "\n\n[日志过长，已保留开头和结尾]\n\n"
                + text.substring(text.length() - chars)).getBytes(StandardCharsets.UTF_8);
    }

    private static void writeRecord(Context context, String report, String kind) throws IOException {
        File directory = directory(context);
        File destination = new File(directory, System.currentTimeMillis() + "-" + UUID.randomUUID() + "-" + kind + ".log");
        AtomicFile atomic = new AtomicFile(destination);
        FileOutputStream output = null;
        try {
            output = atomic.startWrite();
            output.write(limited(report));
            atomic.finishWrite(output);
            output = null;
            if (!destination.isFile()) throw new IOException("无法提交日志文件");
        } catch (IOException error) {
            if (output != null) atomic.failWrite(output);
            throw error;
        }
        prune(directory, false);
        changed();
    }

    static synchronized void appendRuntime(Context context, String line) throws IOException {
        byte[] bytes = limited(line + "\n");
        if (runtimeOutput == null || runtimeBytes + bytes.length > MAX_LOG_BYTES) {
            closeRuntime();
            File directory = directory(context);
            runtimeFile = new File(directory, System.currentTimeMillis() + "-" + UUID.randomUUID() + "-runtime.log");
            runtimeOutput = new FileOutputStream(runtimeFile);
            byte[] header = (describe(context, "进程运行日志", "进程  " + android.os.Process.myPid())
                    + "\n\n").getBytes(StandardCharsets.UTF_8);
            runtimeOutput.write(header);
            runtimeBytes = header.length;
            prune(directory, true);
        }
        runtimeOutput.write(bytes);
        runtimeBytes += bytes.length;
        changed();
    }

    private static void closeRuntime() throws IOException {
        if (runtimeOutput != null) {
            try { runtimeOutput.close(); }
            finally { runtimeOutput = null; runtimeFile = null; runtimeBytes = 0; }
        }
    }

    private static void prune(File directory, boolean runtime) {
        File[] files = directory.listFiles(file -> file.isFile() && file.getName().endsWith(".log")
                && file.getName().endsWith("-runtime.log") == runtime);
        if (files == null) return;
        java.util.Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        long bytes = 0;
        int limit = runtime ? MAX_RUNTIME_FILES : MAX_RECORD_FILES;
        for (int i = 0; i < files.length; i++) {
            bytes += files[i].length();
            if ((i >= limit || (!runtime && bytes > MAX_RECORD_BYTES)) && !files[i].equals(runtimeFile)) {
                if (!files[i].delete()) android.util.Log.w("DoroLogs", "无法清理旧日志 " + files[i].getName());
            }
        }
    }

    static synchronized void clear(Context context) throws IOException {
        generation++;
        closeRuntime();
        File[] files = directory(context).listFiles();
        IOException failure = null;
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && !file.delete()) {
                    IOException error = new IOException("无法删除日志 " + file.getName());
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                }
            }
        }
        changed();
        if (failure != null) throw failure;
    }

    static void subscribe(Runnable listener) { listeners.add(listener); }
    static void unsubscribe(Runnable listener) { listeners.remove(listener); }

    private static void changed() {
        if (!notificationPending.compareAndSet(false, true)) return;
        main.postDelayed(() -> {
            notificationPending.set(false);
            for (Runnable listener : listeners) listener.run();
        }, 700);
    }

    static synchronized List<Entry> list(Context context) {
        File directory = new File(context.getFilesDir(), "error-logs");
        File[] files = directory.listFiles(file -> file.isFile() && file.getName().endsWith(".log"));
        List<Entry> entries = new ArrayList<>();
        if (files == null) return entries;
        for (File file : files) {
            String title = "应用日志";
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8))) {
                String firstLine = reader.readLine();
                if (firstLine != null && !firstLine.isEmpty()) title = firstLine;
            } catch (IOException error) { android.util.Log.w("DoroEncrypt", "无法读取日志标题", error); }
            entries.add(new Entry(file, title));
        }
        entries.sort(Comparator.comparingLong((Entry entry) -> entry.createdAt).reversed());
        return entries;
    }

    static synchronized String read(Entry entry) throws IOException {
        return new String(Files.readAllBytes(entry.file.toPath()), StandardCharsets.UTF_8);
    }

    static final class Entry {
        final File file;
        final String title;
        final long createdAt;
        final String kind;
        final long size;
        Entry(File file, String title) {
            this.file = file;
            this.title = title;
            createdAt = file.lastModified();
            size = file.length();
            kind = file.getName().endsWith("-runtime.log") ? "运行"
                    : file.getName().endsWith("-event.log") ? "操作" : "错误";
        }
    }
}
