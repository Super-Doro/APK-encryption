package com.function.fixture;

public final class FixtureIsolatedService extends android.app.Service {
    @Override public android.os.IBinder onBind(android.content.Intent intent) {
        return new android.os.Binder() {
            @Override protected boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) {
                reply.writeNoException();
                reply.writeBoolean(android.os.Process.isIsolated());
                reply.writeBoolean(FixtureApplication.ready);
                reply.writeBoolean(getApplication() instanceof FixtureApplication);
                reply.writeBoolean(getApplicationContext() instanceof FixtureApplication);
                reply.writeInt(android.os.Process.myUid());
                return true;
            }
        };
    }
}
