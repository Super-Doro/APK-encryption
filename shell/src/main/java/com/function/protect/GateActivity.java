package com.function.protect;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class GateActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        if (ShellRuntime.unlockedLoader != null) {
            try {
                Intent original = new Intent().setComponent(new ComponentName(getPackageName(),
                        ShellRuntime.config.getString("launcher")));
                original.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(original);
                finish();
                return;
            } catch (Exception error) { ShellRuntime.loadError = "无法启动原软件，请联系打包方检查兼容性"; }
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(247, 247, 252));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(26), dp(48), dp(26), dp(32));
        scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            scroll.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(scroll);
        TextView title = text(content, getApplicationInfo().loadLabel(getPackageManager()).toString(), 27);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        text(content, "输入此加密包的专属密钥", 17);
        TextView identity = text(content, "包标识  " + (ShellRuntime.config == null ? "配置损坏"
                : ShellRuntime.config.optString("id")), 12);
        identity.setTextIsSelectable(true);
        EditText input = new EditText(this);
        input.setHint("FA2.…");
        input.setTextSize(14);
        input.setMinLines(4);
        input.setMaxLines(8);
        input.setGravity(android.view.Gravity.TOP);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(8192)});
        content.addView(input, new LinearLayout.LayoutParams(-1, -2));
        Button unlock = new Button(this);
        unlock.setText("解锁并启动");
        content.addView(unlock, new LinearLayout.LayoutParams(-1, dp(56)));
        TextView status = text(content, ShellRuntime.loadError == null ? "" : ShellRuntime.loadError, 14);
        status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        if (ShellRuntime.config == null) unlock.setEnabled(false);
        unlock.setOnClickListener(view -> {
            String token = input.getText().toString();
            unlock.setEnabled(false);
            input.setEnabled(false);
            status.setText("正在验证密钥并解密代码…");
            new Thread(() -> {
                try {
                    ShellRuntime.load(token, getClassLoader());
                    ShellRuntime.store(token);
                    runOnUiThread(() -> {
                        if (isDestroyed()) return;
                        status.setText("解锁成功，正在启动软件…");
                        Intent restart = new Intent(this, RestartActivity.class)
                                .putExtra("previousPid", android.os.Process.myPid())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                        startActivity(restart);
                        finish();
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> {
                        if (isDestroyed()) return;
                        unlock.setEnabled(true);
                        input.setEnabled(true);
                        status.setText("解锁失败\n" + error.getMessage());
                    });
                }
            }, "function-unlock").start();
        });
    }

    private TextView text(LinearLayout parent, String value, int size) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(Color.rgb(35, 40, 63));
        text.setPadding(0, 0, 0, dp(20));
        parent.addView(text, new LinearLayout.LayoutParams(-1, -2));
        return text;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
