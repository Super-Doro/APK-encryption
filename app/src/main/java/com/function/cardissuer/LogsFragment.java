package com.function.cardissuer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Fragment;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
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

public final class LogsFragment extends Fragment {
    private static final int BLUE = Color.rgb(0, 122, 255);
    private static final int INK = Color.rgb(28, 28, 30);
    private static final int GRAY = Color.rgb(142, 142, 147);
    private static final String[] FILTERS = {"全部", "错误", "操作", "运行"};
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<ErrorLogStore.Entry> entries = new ArrayList<>();
    private final List<ErrorLogStore.Entry> filtered = new ArrayList<>();
    private final LogAdapter adapter = new LogAdapter();
    private LiquidGlassNavigation filters;
    private final Runnable listener = this::refresh;
    private Context app;
    private TextView count;
    private TextView captureStatus;
    private TextView empty;
    private int filter;
    private boolean loading;
    private boolean destroyed;
    private Dialog logDialog;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        app = getActivity().getApplicationContext();
        if (state != null) filter = Math.max(0, Math.min(3, state.getInt("filter")));
    }

    @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        LinearLayout page = column();
        page.setBackgroundColor(Color.WHITE);
        LinearLayout header = new LinearLayout(getActivity());
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(24), dp(20), dp(24), dp(8));
        header.addView(label("日志", 30, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView clear = label("清空", 15, BLUE, false);
        clear.setPadding(dp(16), dp(12), 0, dp(12));
        clear.setOnClickListener(view -> confirmClear());
        header.addView(clear);
        page.addView(header);
        count = label("正在读取日志", 13, GRAY, false);
        count.setPadding(dp(24), 0, dp(24), dp(4));
        page.addView(count);
        captureStatus = label("", 11, GRAY, false);
        captureStatus.setPadding(dp(24), 0, dp(24), 0);
        captureStatus.setMaxLines(2);
        page.addView(captureStatus);
        filters = new LiquidGlassNavigation(getActivity(), FILTERS, false);
        FrameLayout frame = new FrameLayout(getActivity());
        page.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));
        ListView list = new ListView(getActivity());
        list.setDivider(null);
        list.setClipToPadding(false);
        list.setPadding(0, filters.preferredHeight() + dp(28), 0,
                dp(16) + LiquidGlassNavigation.contentBottomInset(getActivity()));
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> showLog(filtered.get(position)));
        frame.addView(list, new FrameLayout.LayoutParams(-1, -1));
        empty = label("暂无日志", 15, GRAY, false);
        empty.setGravity(Gravity.CENTER);
        frame.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        list.setEmptyView(empty);
        filters.setOnTabSelectedListener(index -> {
            if (filter == index) return;
            filter = index;
            render();
            list.setSelection(0);
        });
        FrameLayout.LayoutParams segmented = new FrameLayout.LayoutParams(-1, filters.preferredHeight(), Gravity.TOP);
        segmented.setMargins(dp(24), dp(12), dp(24), 0);
        frame.addView(filters, segmented);
        filters.setBackdropSource(list);
        render();
        return page;
    }

    @Override public void onStart() { super.onStart(); ErrorLogStore.subscribe(listener); }
    @Override public void onResume() { super.onResume(); refresh(); }
    @Override public void onStop() { ErrorLogStore.unsubscribe(listener); super.onStop(); }

    void refresh() {
        if (destroyed || loading || getView() == null || !getView().isShown()) return;
        loading = true;
        worker.execute(() -> {
            List<ErrorLogStore.Entry> saved = ErrorLogStore.list(app);
            post(() -> {
                loading = false;
                entries.clear();
                entries.addAll(saved);
                render();
            });
        });
    }

    private void render() {
        if (count == null) return;
        filtered.clear();
        long bytes = 0;
        for (ErrorLogStore.Entry entry : entries) {
            if (filter == 0 || FILTERS[filter].equals(entry.kind)) {
                filtered.add(entry);
                bytes += entry.size;
            }
        }
        count.setText(filtered.size() + " 条 · " + android.text.format.Formatter.formatShortFileSize(app, bytes));
        captureStatus.setText(AppLog.captureStatus());
        filters.select(filter);
        empty.setText(filter == 0 ? "暂无日志" : "暂无" + FILTERS[filter] + "日志");
        adapter.notifyDataSetChanged();
    }

    private void showLog(ErrorLogStore.Entry entry) {
        worker.execute(() -> {
            String report;
            try { report = ErrorLogStore.read(entry); }
            catch (java.io.IOException error) { report = "读取日志失败\n" + android.util.Log.getStackTraceString(error); }
            String detail = report;
            post(() -> {
                Activity activity = getActivity();
                if (logDialog != null) logDialog.dismiss();
                Dialog dialog = new Dialog(activity);
                logDialog = dialog;
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
                dialog.setOnDismissListener(closed -> { if (logDialog == dialog) logDialog = null; });
                LinearLayout content = column();
                GradientDrawable background = new GradientDrawable();
                background.setColor(Color.WHITE);
                background.setCornerRadii(new float[]{dp(26), dp(26), dp(26), dp(26), 0, 0, 0, 0});
                content.setBackground(background);
                LinearLayout header = new LinearLayout(activity);
                header.setGravity(Gravity.CENTER_VERTICAL);
                header.setPadding(dp(24), dp(16), dp(24), dp(10));
                TextView title = label(entry.title, 17, INK, true);
                title.setSingleLine(true);
                title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
                TextView copy = label("复制", 15, BLUE, false);
                copy.setPadding(dp(16), dp(12), dp(14), dp(12));
                copy.setOnClickListener(view -> {
                    ((ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE))
                            .setPrimaryClip(ClipData.newPlainText("Doro加密日志", detail));
                    Toast.makeText(activity, "日志已复制", Toast.LENGTH_SHORT).show();
                });
                header.addView(copy);
                TextView close = label("完成", 15, BLUE, false);
                close.setPadding(dp(8), dp(12), 0, dp(12));
                close.setOnClickListener(view -> dialog.dismiss());
                header.addView(close);
                content.addView(header);
                TextView subtitle = label(entry.kind + " · "
                        + DateFormat.getDateTimeInstance().format(new Date(entry.createdAt)), 12, GRAY, false);
                subtitle.setPadding(dp(24), 0, dp(24), dp(12));
                content.addView(subtitle);
                TextView text = label(detail, 12, INK, false);
                text.setTypeface(Typeface.MONOSPACE);
                text.setTextIsSelectable(true);
                text.setPadding(dp(24), dp(12), dp(24), dp(28));
                ScrollView scroll = new ScrollView(activity);
                scroll.addView(text);
                content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
                dialog.setContentView(content);
                dialog.show();
                Window window = dialog.getWindow();
                if (window != null) {
                    window.setBackgroundDrawableResource(android.R.color.transparent);
                    window.setGravity(Gravity.BOTTOM);
                    window.setLayout(-1, Math.round(getResources().getDisplayMetrics().heightPixels * 0.88f));
                    window.setDimAmount(0.18f);
                }
            });
        });
    }

    private void confirmClear() {
        new AlertDialog.Builder(getActivity()).setTitle("清空日志")
                .setMessage("删除已保存的操作、错误和运行日志。新的运行日志会继续记录。")
                .setNegativeButton("取消", null).setPositiveButton("清空", (dialog, which) -> worker.execute(() -> {
                    try {
                        ErrorLogStore.clear(app);
                        post(() -> { entries.clear(); render(); refresh(); });
                    } catch (java.io.IOException error) {
                        post(() -> Toast.makeText(getActivity(), "清空失败\n" + error.getMessage(), Toast.LENGTH_LONG).show());
                    }
                })).show();
    }

    private void post(Runnable action) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            Activity activity = getActivity();
            if (!destroyed && activity != null && !activity.isFinishing() && getView() != null) action.run();
        });
    }

    @Override public void onSaveInstanceState(Bundle state) { state.putInt("filter", filter); super.onSaveInstanceState(state); }
    @Override public void onDestroyView() {
        if (logDialog != null) { logDialog.dismiss(); logDialog = null; }
        count = null;
        captureStatus = null;
        filters = null;
        empty = null;
        super.onDestroyView();
    }
    @Override public void onDestroy() { destroyed = true; worker.shutdown(); super.onDestroy(); }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(getActivity());
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }
    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(getActivity());
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(dp(3), 1);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }
    private int dp(int value) { return Math.round(value * app.getResources().getDisplayMetrics().density); }

    private final class LogAdapter extends BaseAdapter {
        @Override public int getCount() { return filtered.size(); }
        @Override public ErrorLogStore.Entry getItem(int position) { return filtered.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout outer;
            if (recycled == null) {
                outer = column();
                outer.setPadding(dp(24), 0, dp(24), 0);
                LinearLayout content = column();
                content.setPadding(0, dp(20), 0, dp(20));
                content.setBackground(new FlatDividerDrawable(getActivity()));
                content.addView(label("", 16, INK, true));
                TextView subtitle = label("", 12, GRAY, false);
                subtitle.setPadding(0, dp(8), 0, 0);
                content.addView(subtitle);
                outer.addView(content);
            } else outer = (LinearLayout) recycled;
            LinearLayout content = (LinearLayout) outer.getChildAt(0);
            ErrorLogStore.Entry entry = getItem(position);
            ((TextView) content.getChildAt(0)).setText(entry.title);
            ((TextView) content.getChildAt(1)).setText(entry.kind + " · "
                    + DateFormat.getDateTimeInstance().format(new Date(entry.createdAt)));
            return outer;
        }
    }
}
