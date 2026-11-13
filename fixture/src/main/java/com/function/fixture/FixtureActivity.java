package com.function.fixture;
public final class FixtureActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        android.widget.TextView message = new android.widget.TextView(this);
        message.setPadding(60, 160, 60, 60);
        message.setTextSize(24);
        message.setText("原应用已运行\nApplication=" + (FixtureApplication.ready ? "OK" : "FAIL")
                + "\nProvider=" + (FixtureProvider.ready ? "OK" : "FAIL")
                + "\nFactory=" + (FixtureFactory.ready ? "OK" : "FAIL")
                + "\nResource=" + getString(R.string.fixture_message));
        setContentView(message);
        bindService(new android.content.Intent(this, FixtureIsolatedService.class), new android.content.ServiceConnection() {
            @Override public void onServiceConnected(android.content.ComponentName name, android.os.IBinder binder) {
                android.os.Parcel request = android.os.Parcel.obtain();
                android.os.Parcel response = android.os.Parcel.obtain();
                try {
                    binder.transact(1, request, response, 0);
                    response.readException();
                    boolean isolated = response.readBoolean();
                    boolean created = response.readBoolean();
                    boolean app = response.readBoolean();
                    boolean context = response.readBoolean();
                    int uid = response.readInt();
                    message.append("\nIsolated=" + (isolated && created && app && context ? "OK" : "FAIL")
                            + "\nUID=" + uid);
                } catch (Exception error) { message.append("\nIsolated=FAIL " + error); }
                finally { request.recycle(); response.recycle(); }
            }
            @Override public void onServiceDisconnected(android.content.ComponentName name) {}
        }, BIND_AUTO_CREATE);
    }
}
