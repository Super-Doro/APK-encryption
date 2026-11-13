package com.function.scenarios;

public final class PreferencesApplication extends android.app.Application {
    static boolean ready;
    @Override public void onCreate() {
        super.onCreate();
        getSharedPreferences("startup", 0).edit().putBoolean("created", true).apply();
        ready = true;
    }
}
