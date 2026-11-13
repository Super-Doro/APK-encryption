package com.function.protect;

import android.app.Service;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;

/** Defers encrypted Service creation until Android delivers the authorized bind Intent. */
public final class IsolatedProxyService extends Service {
    private final String originalName;
    private Service delegate;
    private volatile IBinder delegateBinder;
    private String failure = "隔离服务尚未授权";
    private final Binder controlBinder = new Binder() {
        @Override protected boolean onTransact(int code, Parcel request, Parcel response, int flags)
                throws android.os.RemoteException {
            if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, request, response, flags);
            request.enforceInterface(IsolatedBindings.STATUS);
            response.writeNoException();
            response.writeInt(delegate != null ? 1 : 0);
            response.writeString(delegate != null ? "ready" : failure);
            return true;
        }
    };
    private final Binder pendingBinder = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws android.os.RemoteException {
            IBinder target = delegateBinder;
            if (target == null) throw new SecurityException("请先打开软件完成解锁");
            return target.transact(code, data, reply, flags);
        }
    };

    public IsolatedProxyService(String originalName) { this.originalName = originalName; }

    @Override public IBinder onBind(Intent intent) {
        boolean warming = intent != null && intent.getAction() != null
                && intent.getAction().equals(IsolatedBindings.WARMUP + ShellRuntime.config.optString("id"));
        try {
            if (delegate == null && intent != null && intent.hasExtra(IsolatedBindings.BROKER)) initialize(intent);
            if (warming) return controlBinder;
            if (delegate == null) return pendingBinder;
            IBinder binder = delegate.onBind(intent);
            if (binder != null) delegateBinder = binder;
            return binder;
        } catch (Exception error) {
            failure = error.toString();
            android.util.Log.e("FunctionProtect", "Isolated service initialization failed", error);
            return warming ? controlBinder : pendingBinder;
        }
    }

    private void initialize(Intent intent) throws Exception {
        ShellRuntime.restoreIsolated(intent, getClassLoader(), originalName);
        ClassLoader loaded = ShellRuntime.unlockedLoader;
        Service service = ShellRuntime.createFactory(loaded).instantiateService(loaded, originalName, intent);
        Context context = new ContextWrapper(getBaseContext()) {
            @Override public ClassLoader getClassLoader() { return loaded; }
            @Override public Context getApplicationContext() { return ShellRuntime.application; }
        };
        RuntimeBridge.attachService(service, this, context, originalName, ShellRuntime.application);
        service.onCreate();
        delegate = service;
        android.util.Log.i("FunctionProtect", "Isolated code initialized: " + originalName
                + ", uid=" + android.os.Process.myUid());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return delegate == null ? START_NOT_STICKY : delegate.onStartCommand(intent, flags, startId);
    }
    @Override public boolean onUnbind(Intent intent) { return delegate != null && delegate.onUnbind(intent); }
    @Override public void onRebind(Intent intent) { if (delegate != null) delegate.onRebind(intent); }
    @Override public void onDestroy() {
        if (delegate != null) delegate.onDestroy();
        super.onDestroy();
    }
}
