package com.function.cardissuer;

public final class DoroApplication extends android.app.Application {
    @Override public void onCreate() {
        super.onCreate();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                ErrorLogStore.save(this, ErrorLogStore.describe(this, "程序异常退出",
                        "线程  " + thread.getName() + "\n\n" + android.util.Log.getStackTraceString(error)));
            } catch (Throwable logError) { android.util.Log.e("DoroEncrypt", "无法保存异常退出日志", logError); }
            finally {
                if (previous != null) previous.uncaughtException(thread, error);
                else {
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(1);
                }
            }
        });
        AppLog.start(this);
    }
}
