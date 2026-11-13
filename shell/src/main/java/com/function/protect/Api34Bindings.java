package com.function.protect;

import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import java.util.concurrent.Executor;

@android.annotation.TargetApi(34)
public final class Api34Bindings {
    private Api34Bindings() {}
    public static boolean bindService(Context context, Intent intent, ServiceConnection connection, Context.BindServiceFlags flags) {
        return context.bindService(IsolatedBindings.authorize(context, intent), connection, flags);
    }
    public static boolean bindService(Context context, Intent intent, Context.BindServiceFlags flags,
            Executor executor, ServiceConnection connection) {
        return context.bindService(IsolatedBindings.authorize(context, intent), flags, executor, connection);
    }
    public static boolean bindIsolatedService(Context context, Intent intent, Context.BindServiceFlags flags,
            String instance, Executor executor, ServiceConnection connection) {
        return context.bindIsolatedService(IsolatedBindings.authorize(context, intent), flags, instance, executor, connection);
    }
}
