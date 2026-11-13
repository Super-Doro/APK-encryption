package com.function.fixture;
public final class FixtureFactory extends android.app.AppComponentFactory {
    static boolean ready;
    public FixtureFactory() { ready = true; }
}
