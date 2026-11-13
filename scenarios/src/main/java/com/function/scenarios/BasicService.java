package com.function.scenarios;

public final class BasicService extends android.app.Service {
    @Override public android.os.IBinder onBind(android.content.Intent intent) {
        return new android.os.Binder() {
            @Override protected boolean onTransact(int code, android.os.Parcel request, android.os.Parcel response, int flags) {
                response.writeNoException();
                response.writeBoolean(android.os.Process.isIsolated()
                        && getApplication().getClass() == android.app.Application.class);
                return true;
            }
        };
    }
}
