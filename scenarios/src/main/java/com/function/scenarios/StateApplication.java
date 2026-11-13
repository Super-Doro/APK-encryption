package com.function.scenarios;

public final class StateApplication extends android.app.Application {
    final boolean fromFactory;
    boolean ready;
    public StateApplication() { this(false); }
    public StateApplication(boolean fromFactory) { this.fromFactory = fromFactory; }
    @Override public void onCreate() { super.onCreate(); ready = true; }
}
