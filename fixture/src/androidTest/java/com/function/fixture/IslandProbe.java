package com.function.fixture;

public final class IslandProbe extends android.app.Instrumentation {
    private boolean expectLocked;
    @Override public void onCreate(android.os.Bundle args) {
        super.onCreate(args);
        expectLocked = "locked".equals(args.getString("expect"));
        start();
    }
    @Override public void onStart() {
        android.os.Bundle result = new android.os.Bundle();
        android.content.ServiceConnection connection = null;
        boolean bound = false;
        try {
            java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<String> descriptor = new java.util.concurrent.atomic.AtomicReference<>();
            connection = new android.content.ServiceConnection() {
                @Override public void onServiceConnected(android.content.ComponentName component, android.os.IBinder binder) {
                    try {
                        if (expectLocked) {
                            android.os.Parcel request = android.os.Parcel.obtain();
                            android.os.Parcel response = android.os.Parcel.obtain();
                            try {
                                request.writeInterfaceToken("com.catchingnow.island_isolated.Communication");
                                request.writeInt(0);
                                binder.transact(1, request, response, 0);
                                response.readException();
                                descriptor.set("UNEXPECTED_ACCESS");
                            } finally { request.recycle(); response.recycle(); }
                        } else descriptor.set(binder.getInterfaceDescriptor());
                    }
                    catch (Exception error) { descriptor.set(error.toString()); }
                    ready.countDown();
                }
                @Override public void onServiceDisconnected(android.content.ComponentName component) {}
                @Override public void onNullBinding(android.content.ComponentName component) { descriptor.set("NULL"); ready.countDown(); }
            };
            android.content.Intent intent = new android.content.Intent().setComponent(new android.content.ComponentName(
                    "com.catchingnow.icebox", "com.catchingnow.island_isolated.service.IslandIsolatedService"));
            bound = getTargetContext().bindService(intent, connection, android.content.Context.BIND_AUTO_CREATE);
            if (!bound) throw new AssertionError("External caller cannot resolve Island service");
            if (!ready.await(15, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("External connection timed out");
            if (expectLocked) {
                if (descriptor.get() == null || !descriptor.get().contains("SecurityException")) {
                    throw new AssertionError("Unauthenticated external caller was not rejected: " + descriptor.get());
                }
            } else if (!"com.catchingnow.island_isolated.Communication".equals(descriptor.get())) {
                throw new AssertionError("Original Binder unavailable: " + descriptor.get());
            }
            result.putString("stream", expectLocked ? "PASS unauthenticated external caller rejected"
                    : "PASS external UID " + android.os.Process.myUid() + " connected to original Island Binder");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL " + android.util.Log.getStackTraceString(error));
            finish(0, result);
        } finally {
            if (bound) getTargetContext().unbindService(connection);
        }
    }
}
