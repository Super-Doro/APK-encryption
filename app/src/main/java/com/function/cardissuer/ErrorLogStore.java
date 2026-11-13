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

final class ErrorLogStore {
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

    static void save(Context context, String report) throws IOException {
        android.util.Log.e("DoroEncrypt", report);
        File directory = new File(context.getFilesDir(), "error-logs");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建日志目录");
        File destination = new File(directory, System.currentTimeMillis() + "-" + UUID.randomUUID() + ".log");
        AtomicFile atomic = new AtomicFile(destination);
        FileOutputStream output = null;
        try {
            output = atomic.startWrite();
            output.write(report.getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(output);
            output = null;
            if (!destination.isFile()) throw new IOException("无法提交日志文件");
        } catch (IOException error) {
            if (output != null) atomic.failWrite(output);
            throw error;
        }
    }

    static List<Entry> list(Context context) {
        File directory = new File(context.getFilesDir(), "error-logs");
        File[] files = directory.listFiles(file -> file.isFile() && file.getName().endsWith(".log"));
        List<Entry> entries = new ArrayList<>();
        if (files == null) return entries;
        for (File file : files) {
            String title = "错误日志";
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

    static String read(Entry entry) throws IOException {
        return new String(Files.readAllBytes(entry.file.toPath()), StandardCharsets.UTF_8);
    }

    static final class Entry {
        final File file;
        final String title;
        final long createdAt;
        Entry(File file, String title) {
            this.file = file;
            this.title = title;
            createdAt = file.lastModified();
        }
    }
}
