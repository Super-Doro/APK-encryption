package com.function.protect;

/** A visible helper process can restart the app without background-launch privileges. */
public final class RestartActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        android.widget.TextView status = new android.widget.TextView(this);
        status.setText("正在启动软件…");
        status.setGravity(android.view.Gravity.CENTER);
        setContentView(status);
        int previous = getIntent().getIntExtra("previousPid", -1);
        if (previous > 0 && previous != android.os.Process.myPid()) android.os.Process.killProcess(previous);
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            startActivity(new android.content.Intent(this, GateActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finishAndRemoveTask();
        }, 400);
    }
}
