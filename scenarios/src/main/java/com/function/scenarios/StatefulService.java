package com.function.scenarios;

public final class StatefulService extends android.app.Service {
    @Override public android.os.IBinder onBind(android.content.Intent intent) {
        return new android.os.Binder() {
            @Override protected boolean onTransact(int code, android.os.Parcel request, android.os.Parcel response, int flags) {
                StateApplication application = (StateApplication) getApplication();
                response.writeNoException();
                response.writeBoolean(android.os.Process.isIsolated() && application.ready && application.fromFactory);
                return true;
            }
        };
    }
}
