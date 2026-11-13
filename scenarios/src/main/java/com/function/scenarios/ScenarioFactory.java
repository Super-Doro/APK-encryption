package com.function.scenarios;

public final class ScenarioFactory extends android.app.AppComponentFactory {
    @Override public android.app.Application instantiateApplication(ClassLoader loader, String name) {
        return new StateApplication(true);
    }
}
