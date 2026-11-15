package com.function.cardissuer;

import android.app.Activity;
import android.os.Bundle;
import android.widget.FrameLayout;

/** Compatibility entry point for existing internal log links. */
public final class ErrorLogsActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        root.setId(0x00d07003);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
        if (getFragmentManager().findFragmentByTag("app-logs") == null) {
            getFragmentManager().beginTransaction().add(root.getId(), new LogsFragment(), "app-logs").commit();
        }
    }
}
