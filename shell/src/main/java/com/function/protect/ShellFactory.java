package com.function.protect;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Intent;
import android.content.pm.ApplicationInfo;

public final class ShellFactory extends AppComponentFactory {
    private AppComponentFactory original = new AppComponentFactory();
    private boolean originalReady;

    @Override public ClassLoader instantiateClassLoader(ClassLoader cl, ApplicationInfo info) {
        if (Application.getProcessName().endsWith(":function_protect_restart_v2")) return cl;
        ClassLoader loaded = ShellRuntime.prepare(cl, info);
        if (ShellRuntime.unlockedLoader != null) {
            try {
                prepareOriginal(loaded);
                return original.instantiateClassLoader(loaded, info);
            } catch (Exception error) {
                ShellRuntime.unlockedLoader = null;
                ShellRuntime.loadError = "原应用启动工厂加载失败，请联系打包方检查兼容性";
                android.util.Log.e("FunctionProtect", "Original component factory failed", error);
            }
        }
        return cl;
    }

    @Override public Application instantiateApplication(ClassLoader cl, String name)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (android.os.Process.isIsolated()) {
            ShellRuntime.applicationClassName = name;
            android.util.Log.i("FunctionProtect", "Isolated Application requested: " + name);
            return new IsolatedApplication();
        }
        if (!unlocked()) return new Application();
        Application application = original.instantiateApplication(cl, name);
        ShellRuntime.application = application;
        IsolatedBindings.observe(application);
        return application;
    }

    @Override public Activity instantiateActivity(ClassLoader cl, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (name.equals(RestartActivity.class.getName())) return new RestartActivity();
        if (!unlocked() || name.equals(GateActivity.class.getName())) return new GateActivity();
        return original.instantiateActivity(cl, name, intent);
    }

    @Override public Service instantiateService(ClassLoader cl, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        if (android.os.Process.isIsolated()) {
            return new IsolatedProxyService(name);
        }
        if (unlocked() && ShellRuntime.application != null) IsolatedBindings.warmup(ShellRuntime.application);
        return unlocked() ? original.instantiateService(cl, name, intent) : new LockedService();
    }

    @Override public BroadcastReceiver instantiateReceiver(ClassLoader cl, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return unlocked() ? original.instantiateReceiver(cl, name, intent) : new LockedReceiver();
    }

    @Override public ContentProvider instantiateProvider(ClassLoader cl, String name)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return unlocked() ? original.instantiateProvider(cl, name) : new LockedProvider();
    }

    private boolean unlocked() { return ShellRuntime.unlockedLoader != null; }

    private void prepareOriginal(ClassLoader loaded) throws Exception {
        if (originalReady) return;
        original = ShellRuntime.createFactory(loaded);
        originalReady = true;
    }

    public static final class LockedService extends Service {
        @Override public android.os.IBinder onBind(Intent intent) { return null; }
        @Override public int onStartCommand(Intent intent, int flags, int startId) {
            stopSelf();
            return START_NOT_STICKY;
        }
    }
    public static final class LockedReceiver extends BroadcastReceiver {
        @Override public void onReceive(android.content.Context context, Intent intent) {}
    }
    public static final class LockedProvider extends ContentProvider {
        @Override public boolean onCreate() { return true; }
        @Override public android.database.Cursor query(android.net.Uri uri, String[] projection,
                String selection, String[] args, String sort) { return null; }
        @Override public String getType(android.net.Uri uri) { return null; }
        @Override public android.net.Uri insert(android.net.Uri uri, android.content.ContentValues values) { return null; }
        @Override public int delete(android.net.Uri uri, String selection, String[] args) { return 0; }
        @Override public int update(android.net.Uri uri, android.content.ContentValues values,
                String selection, String[] args) { return 0; }
    }
}
