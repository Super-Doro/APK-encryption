package com.function.fixture;
public final class FixtureApplication extends android.app.Application {
    static boolean ready;
    @Override public void onCreate() {
        super.onCreate();
        ready = true;
    }
}
