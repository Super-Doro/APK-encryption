package com.function.cardissuer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ErrorLogsActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<ErrorLogStore.Entry> entries = new ArrayList<>();
    private final LogAdapter adapter = new LogAdapter();
    private TextView count;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = column();
        root.setBackgroundColor(Color.rgb(242, 242, 247));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            root.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(22), dp(20), dp(8));
        TextView title = label("错误日志", 30, Color.rgb(28, 28, 30));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView back = label("返回", 15, Color.rgb(0, 122, 255));
        back.setPadding(dp(16), dp(12), 0, dp(12));
        back.setOnClickListener(view -> finish());
        header.addView(back);
        root.addView(header);
        count = label("正在读取", 13, Color.rgb(142, 142, 147));
        count.setPadding(dp(20), 0, dp(20), dp(16));
        root.addView(count);
        FrameLayout frame = new FrameLayout(this);
        root.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(16));
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> showLog(entries.get(position)));
        frame.addView(list, new FrameLayout.LayoutParams(-1, -1));
        TextView empty = label("暂无错误日志", 15, Color.rgb(142, 142, 147));
        empty.setGravity(Gravity.CENTER);
        frame.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        list.setEmptyView(empty);
        setContentView(root);
    }

    @Override public void onResume() {
        super.onResume();
        worker.execute(() -> {
            List<ErrorLogStore.Entry> saved = ErrorLogStore.list(this);
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                entries.clear();
                entries.addAll(saved);
                count.setText(entries.size() + " 条");
                adapter.notifyDataSetChanged();
            });
        });
    }

    private void showLog(ErrorLogStore.Entry entry) {
        worker.execute(() -> {
            String report;
            try { report = ErrorLogStore.read(entry); }
            catch (java.io.IOException error) { report = "读取日志失败\n" + android.util.Log.getStackTraceString(error); }
            String detail = report;
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                TextView text = label(detail, 13, Color.rgb(28, 28, 30));
                text.setTextIsSelectable(true);
                text.setPadding(dp(22), dp(16), dp(22), dp(16));
                ScrollView scroll = new ScrollView(this);
                scroll.addView(text);
                new AlertDialog.Builder(this).setTitle(entry.title).setView(scroll)
                        .setPositiveButton("复制日志", (dialog, which) -> {
                            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                                    .setPrimaryClip(ClipData.newPlainText("Doro加密错误日志", detail));
                            Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show();
                        }).setNegativeButton("关闭", null).show();
            });
        });
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }
    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }

    private final class LogAdapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public ErrorLogStore.Entry getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout outer;
            if (recycled == null) {
                outer = column();
                outer.setPadding(dp(20), dp(6), dp(20), dp(6));
                LinearLayout card = column();
                card.setPadding(dp(18), dp(18), dp(18), dp(18));
                GradientDrawable background = new GradientDrawable();
                background.setColor(Color.WHITE);
                background.setCornerRadius(dp(18));
                card.setBackground(background);
                card.addView(label("", 16, Color.rgb(28, 28, 30)));
                TextView time = label("", 12, Color.rgb(142, 142, 147));
                time.setPadding(0, dp(8), 0, 0);
                card.addView(time);
                outer.addView(card);
            } else outer = (LinearLayout) recycled;
            LinearLayout card = (LinearLayout) outer.getChildAt(0);
            ErrorLogStore.Entry entry = getItem(position);
            ((TextView) card.getChildAt(0)).setText(entry.title);
            ((TextView) card.getChildAt(1)).setText(DateFormat.getDateTimeInstance().format(new Date(entry.createdAt)));
            return outer;
        }
    }
}
