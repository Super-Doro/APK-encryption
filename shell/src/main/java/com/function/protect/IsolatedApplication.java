package com.function.protect;

public final class IsolatedApplication extends android.app.Application {
    static IsolatedApplication instance;
    @Override protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base);
        instance = this;
    }
    @Override public android.content.Context getApplicationContext() {
        return ShellRuntime.application == null ? this : ShellRuntime.application;
    }
}
