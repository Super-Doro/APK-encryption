package com.function.scenarios;

public final class ScenarioActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        android.widget.TextView text = new android.widget.TextView(this);
        text.setTextSize(22);
        text.setPadding(40, 180, 40, 40);
        text.setText("SCENARIO_READY " + BuildConfig.FLAVOR);
        if (BuildConfig.FLAVOR.equals("preferences")) text.append("\nMainPreferences=" + PreferencesApplication.ready);
        setContentView(text);
        bind(text, BasicService.class, "BASIC");
        if (BuildConfig.STATEFUL) bind(text, StatefulService.class, "STATEFUL");
    }

    private void bind(android.widget.TextView text, Class<?> service, String label) {
        android.content.Intent intent = new android.content.Intent(this, service);
        android.content.ServiceConnection connection = new android.content.ServiceConnection() {
            @Override public void onServiceConnected(android.content.ComponentName name, android.os.IBinder binder) {
                android.os.Parcel request = android.os.Parcel.obtain();
                android.os.Parcel response = android.os.Parcel.obtain();
                try {
                    binder.transact(1, request, response, 0);
                    response.readException();
                    text.append("\n" + label + (response.readBoolean() ? "_OK" : "_FAIL"));
                } catch (Exception error) { text.append("\n" + label + "_FAIL " + error); }
                finally { request.recycle(); response.recycle(); }
            }
            @Override public void onServiceDisconnected(android.content.ComponentName name) {}
        };
        if (BuildConfig.FLAVOR.equals("plain")) {
            bindIsolatedService(intent, BIND_AUTO_CREATE, "scenario-instance", getMainExecutor(), connection);
        } else if (BuildConfig.FLAVOR.equals("preferences") && android.os.Build.VERSION.SDK_INT >= 34) {
            bindService(intent, connection, android.content.Context.BindServiceFlags.of(BIND_AUTO_CREATE));
        } else bindService(intent, connection, BIND_AUTO_CREATE);
    }
}
