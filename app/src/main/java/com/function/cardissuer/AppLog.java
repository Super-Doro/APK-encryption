package com.function.cardissuer;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

final class AppLog {
    private static final AtomicBoolean started = new AtomicBoolean();
    private static volatile String captureStatus = "正在启动进程日志采集";

    private AppLog() {}

    static String captureStatus() { return captureStatus; }

    static void event(Context context, String component, String title, String detail) {
        ErrorLogStore.event(context, component, title, detail);
    }

    static void start(Application application) {
        if (!started.compareAndSet(false, true)) return;
        event(application, "应用", "应用启动", "进程  " + android.os.Process.myPid());
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            private void lifecycle(Activity activity, String event) {
                AppLog.event(application, "页面", event, activity.getClass().getSimpleName());
            }
            @Override public void onActivityCreated(Activity activity, Bundle state) { lifecycle(activity, "页面创建"); }
            @Override public void onActivityStarted(Activity activity) { lifecycle(activity, "页面进入前台"); }
            @Override public void onActivityResumed(Activity activity) { lifecycle(activity, "页面可交互"); }
            @Override public void onActivityPaused(Activity activity) { lifecycle(activity, "页面暂停"); }
            @Override public void onActivityStopped(Activity activity) { lifecycle(activity, "页面进入后台"); }
            @Override public void onActivityDestroyed(Activity activity) { lifecycle(activity, "页面销毁"); }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
        });
        Thread collector = new Thread(() -> capture(application), "DoroLogcat");
        collector.setDaemon(true);
        collector.start();
    }

    private static void capture(Context context) {
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/logcat", "-b", "main", "-b", "system", "-b", "crash",
                    "-v", "threadtime", "--pid=" + android.os.Process.myPid(), "-T", "1", "*:V")
                    .redirectErrorStream(true).start();
            captureStatus = "正在采集本 App 进程的运行日志";
            event(context, "日志", "运行日志采集启动", "进程  " + android.os.Process.myPid());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) ErrorLogStore.appendRuntime(context, line);
            }
            int result = process.waitFor();
            throw new IOException("Logcat 采集退出，返回码 " + result);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            captureStatus = "进程日志采集已停止";
        } catch (IOException | RuntimeException error) {
            captureStatus = "进程日志采集不可用，操作及错误日志正常记录";
            try {
                ErrorLogStore.save(context, ErrorLogStore.describe(context, "运行日志采集受限",
                        android.util.Log.getStackTraceString(error)));
            } catch (IOException logError) { android.util.Log.e("DoroLogs", "无法保存采集状态", logError); }
        } finally {
            if (process != null) process.destroy();
        }
    }
}
