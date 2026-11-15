package com.function.cardissuer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int SELECT = 10;
    private static final int EXPORT = 11;
    private static final int AUDIO_CONTAINER = 0x00d07001;
    private static final int LOGS_CONTAINER = 0x00d07002;
    private static final int BLUE = Color.rgb(0, 122, 255);
    private static final int INK = Color.rgb(28, 28, 30);
    private static final int GRAY = Color.rgb(142, 142, 147);
    private static final int BACKGROUND = Color.WHITE;
    private static final int LINE = Color.rgb(229, 229, 234);
    private static final String[] POLICY_LABELS = {"自动分析", "保留原初始化", "基础初始化"};
    private static final String[] POLICY_VALUES = {IsolationPlanner.AUTO, IsolationPlanner.ORIGINAL, IsolationPlanner.MINIMAL};
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<ApkProtector.Result> records = new ArrayList<>();
    private final List<ApkProtector.Result> filtered = new ArrayList<>();
    private final RecordAdapter adapter = new RecordAdapter();
    private View homePage;
    private View recordsPage;
    private View audioPage;
    private View logsPage;
    private View fileRow;
    private View policyRow;
    private TextView fileName;
    private TextView fileDetail;
    private TextView fileAction;
    private TextView policyValue;
    private TextView validityValue;
    private TextView validityTitle;
    private TextView validityHint;
    private View validityRow;
    private View usageRow;
    private TextView usageValue;
    private TextView notice;
    private TextView recordCount;
    private TextView empty;
    private EditText search;
    private Button start;
    private LinearLayout progressPanel;
    private TextView progressStage;
    private TextView progressPercent;
    private ProgressBar progressBar;
    private LiquidGlassNavigation navigation;
    private File inputFile;
    private String inputName;
    private File pendingExport;
    private int policyIndex;
    private int validHours = 24;
    private int usageHours;
    private int currentTab;
    private boolean busy;
    private volatile String lastStage;
    private int lastPercent = -2;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            String path = state.getString("inputPath");
            if (path != null && new File(path).isFile()) {
                inputFile = new File(path);
                inputName = state.getString("inputName");
            }
            String export = state.getString("pendingExport");
            if (export != null) pendingExport = new File(export);
            policyIndex = Math.max(0, Math.min(2, state.getInt("isolationPolicy", 0)));
            validHours = Math.max(1, Math.min(720, state.getInt("validHours", 24)));
            usageHours = Math.max(0, Math.min(720, state.getInt("usageHours", 0)));
            currentTab = state.getInt("tab", 0);
        }
        buildLayout();
        if (state != null) search.setText(state.getString("search", ""));
        reloadRecords();
        refreshForm();
        setTab(currentTab);
        worker.execute(this::cleanDeletedRecords);
    }

    private void buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BACKGROUND);
        root.setClipToPadding(false);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            root.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            boolean keyboard = android.os.Build.VERSION.SDK_INT >= 30
                    ? insets.isVisible(android.view.WindowInsets.Type.ime()) : insets.getSystemWindowInsetBottom() > dp(150);
            if (navigation != null) navigation.setVisibility(keyboard ? View.GONE : View.VISIBLE);
            return insets;
        });
        LinearLayout content = column();
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout pages = new FrameLayout(this);
        content.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1));
        homePage = buildHome();
        recordsPage = buildRecords();
        pages.addView(homePage, new FrameLayout.LayoutParams(-1, -1));
        pages.addView(recordsPage, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout audio = new FrameLayout(this);
        audio.setId(AUDIO_CONTAINER);
        audioPage = audio;
        pages.addView(audio, new FrameLayout.LayoutParams(-1, -1));
        if (getFragmentManager().findFragmentByTag("audio-conversion") == null) {
            getFragmentManager().beginTransaction()
                    .add(AUDIO_CONTAINER, new AudioConversionFragment(), "audio-conversion").commit();
        }
        FrameLayout logs = new FrameLayout(this);
        logs.setId(LOGS_CONTAINER);
        logsPage = logs;
        pages.addView(logs, new FrameLayout.LayoutParams(-1, -1));
        if (getFragmentManager().findFragmentByTag("app-logs") == null) {
            getFragmentManager().beginTransaction().add(LOGS_CONTAINER, new LogsFragment(), "app-logs").commit();
        }

        progressPanel = column();
        progressPanel.setPadding(dp(18), dp(16), dp(18), dp(16));
        progressPanel.setBackground(shape(Color.WHITE, 18));
        LinearLayout.LayoutParams progressLayout = new LinearLayout.LayoutParams(-1, -2);
        progressLayout.setMargins(dp(20), dp(8), dp(20), LiquidGlassNavigation.contentBottomInset(this));
        content.addView(progressPanel, progressLayout);
        LinearLayout progressHeader = row();
        progressStage = label("", 14, INK, true);
        progressStage.setMaxLines(2);
        progressHeader.addView(progressStage, new LinearLayout.LayoutParams(0, -2, 1));
        progressPercent = label("0%", 14, BLUE, true);
        progressHeader.addView(progressPercent);
        progressPanel.addView(progressHeader);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgressTintList(ColorStateList.valueOf(BLUE));
        progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(LINE));
        LinearLayout.LayoutParams barLayout = new LinearLayout.LayoutParams(-1, dp(6));
        barLayout.topMargin = dp(12);
        progressPanel.addView(progressBar, barLayout);
        progressPanel.setVisibility(View.GONE);

        navigation = new LiquidGlassNavigation(this);
        navigation.setBackdropSource(content);
        navigation.setOnTabSelectedListener(index -> setTab(index == 2 ? 3 : index));
        FrameLayout.LayoutParams dock = new FrameLayout.LayoutParams(-1, navigation.preferredHeight(),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dock.setMargins(dp(20), 0, dp(20), dp(12));
        root.addView(navigation, dock);
        root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int available = root.getWidth() - root.getPaddingLeft() - root.getPaddingRight() - dp(40);
            int width = Math.max(1, Math.min(dp(356), available));
            FrameLayout.LayoutParams layout = (FrameLayout.LayoutParams) navigation.getLayoutParams();
            if (layout.width != width) { layout.width = width; navigation.setLayoutParams(layout); }
        });
        setContentView(root);
    }

    private View buildHome() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, LiquidGlassNavigation.contentBottomInset(this));
        LinearLayout page = column();
        page.setPadding(dp(20), dp(22), dp(20), dp(24));
        scroll.addView(page);
        LinearLayout heading = row();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(label("Doro加密", 34, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView history = label("记录", 15, BLUE, false);
        history.setPadding(dp(14), dp(12), 0, dp(12));
        history.setOnClickListener(view -> { reloadRecords(); setTab(2); });
        heading.addView(history);
        add(page, heading, 0);
        add(page, label("每份文件使用独立密钥 · v" + versionName(), 14, GRAY, false), 8);

        LinearLayout file = row();
        file.setGravity(Gravity.CENTER_VERTICAL);
        file.setPadding(dp(18), dp(24), dp(18), dp(24));
        file.setBackground(shape(Color.WHITE, 20));
        add(page, file, 30);
        ImageView document = new ImageView(this);
        document.setImageResource(R.drawable.ic_file_outline);
        document.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        document.setPadding(dp(11), dp(12), dp(11), dp(12));
        document.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        document.setBackground(shape(Color.rgb(238, 245, 255), 13));
        file.addView(document, new LinearLayout.LayoutParams(dp(46), dp(52)));
        LinearLayout fileText = column();
        LinearLayout.LayoutParams fileTextLayout = new LinearLayout.LayoutParams(0, -2, 1);
        fileTextLayout.leftMargin = dp(14);
        file.addView(fileText, fileTextLayout);
        fileName = label("添加文件", 17, INK, true);
        fileName.setMaxLines(2);
        fileName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        fileText.addView(fileName);
        fileDetail = label("APK 或 SH", 13, GRAY, false);
        add(fileText, fileDetail, 5);
        fileAction = label("选择", 15, BLUE, false);
        fileAction.setPadding(dp(12), dp(12), 0, dp(12));
        file.addView(fileAction);
        fileRow = file;
        file.setOnClickListener(view -> { if (!busy) selectFile(); });

        LinearLayout policy = row();
        policy.setGravity(Gravity.CENTER_VERTICAL);
        policy.setPadding(dp(18), dp(18), dp(18), dp(18));
        policy.setBackground(shape(Color.WHITE, 18));
        policy.addView(label("隔离服务策略", 15, INK, false), new LinearLayout.LayoutParams(0, -2, 1));
        policyValue = label("自动分析", 14, GRAY, false);
        policy.addView(policyValue);
        TextView arrow = label("›", 25, GRAY, false);
        arrow.setPadding(dp(10), 0, 0, 0);
        policy.addView(arrow);
        add(page, policy, 14);
        policyRow = policy;
        policy.setOnClickListener(view -> { if (!busy) showPolicy(); });

        LinearLayout validity = row();
        validity.setGravity(Gravity.CENTER_VERTICAL);
        validity.setPadding(dp(18), dp(18), dp(18), dp(18));
        validity.setBackground(shape(Color.WHITE, 18));
        validityTitle = label("有效期", 15, INK, false);
        validity.addView(validityTitle, new LinearLayout.LayoutParams(0, -2, 1));
        validityValue = label("1 天  ›", 14, GRAY, false);
        validity.addView(validityValue);
        add(page, validity, 14);
        validityRow = validity;
        validity.setOnClickListener(view -> { if (!busy) showValidity(false); });
        LinearLayout usage = row();
        usage.setGravity(Gravity.CENTER_VERTICAL);
        usage.setPadding(dp(18), dp(18), dp(18), dp(18));
        usage.setBackground(shape(Color.WHITE, 18));
        usage.addView(label("使用有效期", 15, INK, false), new LinearLayout.LayoutParams(0, -2, 1));
        usageValue = label("不限  ›", 14, GRAY, false);
        usage.addView(usageValue);
        add(page, usage, 14);
        usageRow = usage;
        usage.setOnClickListener(view -> { if (!busy) showValidity(true); });
        validityHint = label("", 12, GRAY, false);
        add(page, validityHint, 10);

        start = action("开始加密", true, this::encryptFile);
        add(page, start, 24);
        TextView destination = label("生成的文件和密钥会保存到记录", 12, GRAY, false);
        destination.setGravity(Gravity.CENTER);
        add(page, destination, 12);
        notice = label("", 13, Color.rgb(215, 55, 55), false);
        notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(page, notice, 20);
        return scroll;
    }

    private View buildRecords() {
        LinearLayout page = column();
        LinearLayout heading = row();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(20), dp(22), dp(20), 0);
        heading.addView(label("记录", 34, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        recordCount = label("0 项", 13, GRAY, false);
        heading.addView(recordCount);
        TextView back = label("返回", 15, BLUE, false);
        back.setPadding(dp(16), dp(12), 0, dp(12));
        back.setOnClickListener(view -> setTab(0));
        heading.addView(back);
        page.addView(heading);
        search = new EditText(this);
        search.setId(View.generateViewId());
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setTextColor(INK);
        search.setHintTextColor(GRAY);
        search.setHint("搜索应用标识、文件名或生成编号");
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setPadding(dp(15), 0, dp(15), 0);
        search.setBackground(shape(Color.WHITE, 12));
        LinearLayout.LayoutParams searchLayout = new LinearLayout.LayoutParams(-1, dp(46));
        searchLayout.setMargins(dp(20), dp(22), dp(20), dp(12));
        page.addView(search, searchLayout);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int offset, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int offset, int before, int count) { filterRecords(); }
            @Override public void afterTextChanged(Editable text) {}
        });
        FrameLayout listFrame = new FrameLayout(this);
        page.addView(listFrame, new LinearLayout.LayoutParams(-1, 0, 1));
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(12) + LiquidGlassNavigation.contentBottomInset(this));
        list.setAdapter(adapter);
        listFrame.addView(list, new FrameLayout.LayoutParams(-1, -1));
        empty = label("还没有加密记录", 15, GRAY, false);
        empty.setGravity(Gravity.CENTER);
        listFrame.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        list.setEmptyView(empty);
        return page;
    }

    private void setTab(int tab) {
        currentTab = Math.max(0, Math.min(3, tab));
        homePage.setVisibility(currentTab == 0 ? View.VISIBLE : View.GONE);
        audioPage.setVisibility(currentTab == 1 ? View.VISIBLE : View.GONE);
        recordsPage.setVisibility(currentTab == 2 ? View.VISIBLE : View.GONE);
        logsPage.setVisibility(currentTab == 3 ? View.VISIBLE : View.GONE);
        navigation.select(currentTab == 3 ? 2 : currentTab == 1 ? 1 : 0);
        AppLog.event(this, "导航", "切换页面", new String[]{"加密", "转换", "记录", "日志"}[currentTab]);
        if (currentTab == 3) {
            LogsFragment logs = (LogsFragment) getFragmentManager().findFragmentByTag("app-logs");
            if (logs != null) logs.refresh();
        }
        if (currentTab != 2) {
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(search.getWindowToken(), 0);
        }
    }

    @Override public void onBackPressed() {
        if (currentTab != 0) setTab(0);
        else super.onBackPressed();
    }

    private void selectFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, SELECT);
    }

    @Override protected void onActivityResult(int request, int response, Intent data) {
        super.onActivityResult(request, response, data);
        if (response != RESULT_OK || data == null || data.getData() == null) {
            if (request == EXPORT) {
                pendingExport = null;
                AppLog.event(this, "导出", "取消导出", "已关闭保存位置选择器");
            }
            return;
        }
        android.net.Uri uri = data.getData();
        if (request == SELECT) importFile(uri);
        else if (request == EXPORT && pendingExport != null) saveExport(uri);
    }

    private void importFile(android.net.Uri uri) {
        AppLog.event(this, "加密", "开始添加文件", "从系统文件选择器读取文件");
        String displayName = uri.getLastPathSegment();
        long reportedSize = -1;
        try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameColumn >= 0) displayName = cursor.getString(nameColumn);
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) reportedSize = cursor.getLong(sizeColumn);
            }
        } catch (Exception error) { showError("读取失败", error); return; }
        String name = displayName == null ? "" : displayName;
        String extension = name.toLowerCase(Locale.ROOT);
        if (!extension.endsWith(".apk") && !extension.endsWith(".sh")) {
            notice.setText("请选择 APK 或 SH 文件");
            return;
        }
        long total = reportedSize;
        setBusy(true);
        publish("正在添加文件", total > 0 ? 0 : -1);
        worker.execute(() -> {
            File saved = new File(getCacheDir(), "input-" + java.util.UUID.randomUUID());
            try {
                try (InputStream input = getContentResolver().openInputStream(uri);
                    OutputStream output = new FileOutputStream(saved)) {
                if (input == null) throw new java.io.IOException("无法读取所选文件");
                ApkProtector.copy(input, output, done -> publish("正在添加文件",
                        total > 0 ? ProtectionProgress.percent(done, total, 0, 100) : -1));
                }
                AppLog.event(this, "加密", "文件添加完成", name + "\n大小  " + saved.length() + " 字节");
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    if (inputFile != null) inputFile.delete();
                    inputFile = saved;
                    inputName = name;
                    setBusy(false);
                    notice.setText("");
                });
            } catch (Exception error) {
                saved.delete();
                showError("读取失败", error);
            }
        });
    }

    private void encryptFile() {
        if (busy || inputFile == null) return;
        File source = inputFile;
        String name = inputName;
        String policy = POLICY_VALUES[policyIndex];
        long issuedAt = System.currentTimeMillis();
        long expiresAt = issuedAt + validHours * 3_600_000L;
        long usageExpiresAt = usageHours == 0 ? 0 : issuedAt + usageHours * 3_600_000L;
        AppLog.event(this, "加密", "开始加密", "文件  " + name + "\n大小  " + source.length()
                + "\n隔离策略  " + policy + "\n激活小时  " + validHours + "\n使用小时  " + usageHours);
        setBusy(true);
        notice.setText("");
        publish("准备加密", 0);
        worker.execute(() -> {
            try {
                ApkProtector.Result generated = name.toLowerCase(Locale.ROOT).endsWith(".sh")
                        ? ScriptProtector.protect(this, source, name, issuedAt, expiresAt, usageExpiresAt, this::publish)
                        : ApkProtector.protect(this, source, name, policy, issuedAt, expiresAt, usageExpiresAt, this::publish);
                source.delete();
                AppLog.event(this, "加密", "加密完成", "文件  " + name + "\n生成编号  " + generated.id
                        + "\n输出字节  " + generated.apk.length());
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    inputFile = null;
                    inputName = null;
                    policyIndex = 0;
                    validHours = 24;
                    usageHours = 0;
                    setBusy(false);
                    reloadRecords();
                    showCompleted(generated);
                });
            } catch (OutOfMemoryError error) {
                showError("设备内存不足", error);
            } catch (Exception error) { showError("加密失败", error); }
        });
    }

    private void publish(String stage, int percent) {
        if (percent == lastPercent && stage.equals(lastStage)) return;
        if (!stage.equals(lastStage)) AppLog.event(this, "加密", "处理阶段", stage);
        lastStage = stage;
        lastPercent = percent;
        runOnUiThread(() -> {
            if (isDestroyed()) return;
            progressStage.setText(stage);
            progressPercent.setText(percent < 0 ? "" : Math.max(0, Math.min(100, percent)) + "%");
            progressBar.setIndeterminate(percent < 0);
            if (percent >= 0) progressBar.setProgress(Math.max(0, Math.min(100, percent)));
        });
    }

    private void showCompleted(ApkProtector.Result result) {
        Dialog sheet = sheet();
        LinearLayout content = sheetContent(sheet);
        add(content, label("加密完成", 23, INK, true), 0);
        add(content, label(result.sourceName, 14, GRAY, false), 10);
        TextView id = label("生成编号  " + result.id, 12, GRAY, false);
        id.setTextIsSelectable(true);
        add(content, id, 8);
        add(content, label(expiryText(result), 14, GRAY, false), 10);
        add(content, action("导出加密文件", true, () -> { sheet.dismiss(); exportFile(result); }), 24);
        add(content, action("复制密钥", false, () -> copyKey(result)), 10);
        add(content, action("查看记录", false, () -> {
            sheet.dismiss();
            setTab(2);
            search.setText(result.id);
        }), 8);
        add(content, textAction("完成", sheet::dismiss), 10);
        sheet.show();
        sizeSheet(sheet);
    }

    private void showValidity(boolean usage) {
        int initialHours = usage ? Math.max(1, usageHours == 0 ? 24 : usageHours) : validHours;
        Dialog dialog = sheet();
        ScrollView scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout content = column();
        content.setPadding(dp(24), dp(24), dp(24), dp(24));
        content.setBackground(shape(Color.WHITE, 24));
        scroll.addView(content);
        dialog.setContentView(scroll);

        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(label(usage ? "使用有效期" : "激活有效期", 23, INK, true),
                new LinearLayout.LayoutParams(0, -2, 1));
        TextView cancel = label("取消", 15, BLUE, false);
        cancel.setPadding(dp(16), dp(12), 0, dp(12));
        cancel.setMinHeight(dp(48));
        cancel.setGravity(Gravity.CENTER);
        cancel.setOnClickListener(view -> dialog.dismiss());
        header.addView(cancel);
        content.addView(header);
        add(content, label("从开始加密起算，最长30天", 13, GRAY, false), 4);

        android.widget.NumberPicker days = durationPicker(30, initialHours / 24);
        android.widget.NumberPicker hours = durationPicker(days.getValue() == 30 ? 0 : 23, initialHours % 24);
        hours.setEnabled(days.getValue() < 30);
        hours.setAlpha(days.getValue() == 30 ? 0.4f : 1f);
        days.setOnValueChangedListener((picker, previous, current) -> {
            hours.setMaxValue(current == 30 ? 0 : 23);
            hours.setEnabled(current < 30);
            hours.setAlpha(current == 30 ? 0.4f : 1f);
        });

        FrameLayout pickerPanel = new FrameLayout(this);
        pickerPanel.setBackground(shape(BACKGROUND, 18));
        View selectedBand = new View(this);
        selectedBand.setBackground(shape(Color.WHITE, 10));
        FrameLayout.LayoutParams bandLayout = new FrameLayout.LayoutParams(-1, dp(44), Gravity.CENTER);
        bandLayout.leftMargin = dp(10);
        bandLayout.rightMargin = dp(10);
        pickerPanel.addView(selectedBand, bandLayout);
        LinearLayout selectors = row();
        selectors.setGravity(Gravity.CENTER);
        selectors.addView(durationColumn(days, "天"), new LinearLayout.LayoutParams(0, -1, 1));
        selectors.addView(durationColumn(hours, "小时"), new LinearLayout.LayoutParams(0, -1, 1));
        pickerPanel.addView(selectors, new FrameLayout.LayoutParams(-1, dp(180)));
        add(content, pickerPanel, 22);

        Button confirm = action("确定", true, () -> {
            days.clearFocus();
            hours.clearFocus();
            int selectedHours = days.getValue() * 24 + hours.getValue();
            if (selectedHours == 0) { toast("请设置至少1小时"); return; }
            if (usage) usageHours = selectedHours;
            else validHours = selectedHours;
            refreshForm();
            dialog.dismiss();
        });
        add(content, confirm, 24);
        if (usage) {
            TextView unlimited = textAction("设为不限", () -> {
                usageHours = 0;
                refreshForm();
                dialog.dismiss();
            });
            unlimited.setTextColor(BLUE);
            unlimited.setMinHeight(dp(48));
            add(content, unlimited, 8);
        }
        dialog.show();
        sizeSheet(dialog);
        int width = getResources().getDisplayMetrics().widthPixels;
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int maxHeight = Math.round(getResources().getDisplayMetrics().heightPixels * 0.85f);
        dialog.getWindow().setLayout(-1, Math.min(content.getMeasuredHeight(), maxHeight));
    }

    private android.widget.NumberPicker durationPicker(int maximum, int value) {
        android.widget.NumberPicker picker = new android.widget.NumberPicker(this);
        picker.setMinValue(0);
        picker.setMaxValue(maximum);
        picker.setValue(value);
        picker.setWrapSelectorWheel(false);
        picker.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        picker.setSelectionDividerHeight(0);
        picker.setTextColor(INK);
        picker.setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
                24, getResources().getDisplayMetrics()));
        return picker;
    }

    private LinearLayout durationColumn(android.widget.NumberPicker picker, String unit) {
        LinearLayout column = row();
        column.setGravity(Gravity.CENTER);
        column.addView(picker, new LinearLayout.LayoutParams(dp(76), dp(180)));
        column.addView(label(unit, 15, GRAY, false));
        return column;
    }

    private String durationText(int totalHours) {
        int days = totalHours / 24;
        int hours = totalHours % 24;
        return days == 0 ? hours + " 小时" : days + " 天" + (hours == 0 ? "" : " " + hours + " 小时");
    }

    private String expiryText(ApkProtector.Result result) {
        String activation = result.expiresAt == 0 ? "未设置激活有效期" : (result.permanentActivation ? "激活截止  " : "到期  ")
                + DateFormat.getDateTimeInstance().format(new Date(result.expiresAt));
        return activation + (result.usageExpiresAt == 0 ? "" : "\n使用截止  "
                + DateFormat.getDateTimeInstance().format(new Date(result.usageExpiresAt)));
    }

    private void showPolicy() {
        Dialog sheet = sheet();
        LinearLayout content = sheetContent(sheet);
        add(content, label("隔离服务策略", 22, INK, true), 0);
        String[] details = {"根据可识别的服务依赖选择初始化方式", "执行原应用的 Application 初始化", "使用基础 Application 初始化隔离进程"};
        for (int i = 0; i < POLICY_LABELS.length; i++) {
            int choice = i;
            Button option = action(POLICY_LABELS[i] + (policyIndex == i ? "  ✓" : ""), false, () -> {
                policyIndex = choice;
                refreshForm();
                sheet.dismiss();
            });
            add(content, option, 18);
            add(content, label(details[i], 12, GRAY, false), 6);
        }
        add(content, textAction("取消", sheet::dismiss), 16);
        sheet.show();
        sizeSheet(sheet);
    }

    private void reloadRecords() {
        records.clear();
        records.addAll(ApkProtector.history(this));
        filterRecords();
    }

    private void filterRecords() {
        if (search == null) return;
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        filtered.clear();
        for (ApkProtector.Result record : records) {
            if (query.isEmpty() || record.packageName.toLowerCase(Locale.ROOT).contains(query)
                    || record.id.toLowerCase(Locale.ROOT).contains(query)
                    || record.sourceName.toLowerCase(Locale.ROOT).contains(query)) filtered.add(record);
        }
        if (recordCount != null) recordCount.setText(filtered.size() + " 项");
        if (empty != null) empty.setText(query.isEmpty() ? "还没有加密记录" : "未找到匹配标识");
        adapter.notifyDataSetChanged();
    }

    private void copyKey(ApkProtector.Result result) {
        AppLog.event(this, "记录", "复制密钥", "生成编号  " + result.id);
        ClipData data = ClipData.newPlainText("本次生成文件的专属密钥", result.key);
        android.os.PersistableBundle extras = new android.os.PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        data.getDescription().setExtras(extras);
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(data);
        toast("密钥已复制");
    }

    private void exportFile(ApkProtector.Result result) {
        String extension = result.script ? ".sh" : ".apk";
        export(result.apk, safeName(result.sourceName) + "-" + result.id + extension,
                result.script ? "text/x-shellscript" : "application/vnd.android.package-archive");
    }

    private void exportKey(ApkProtector.Result result) {
        export(new File(result.apk.getParentFile(), "activation-key.txt"),
                safeName(result.sourceName) + "-" + result.id + "-key.txt", "text/plain");
    }

    private void confirmDelete(ApkProtector.Result result) {
        if (busy) { toast("当前任务完成后即可删除"); return; }
        new AlertDialog.Builder(this).setTitle("删除记录和文件")
                .setMessage("永久删除本条记录、软件内部的加密文件及对应密钥，释放占用空间。\n\n"
                        + result.sourceName + "\n" + result.id)
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> deleteRecord(result)).show();
    }

    private void deleteRecord(ApkProtector.Result result) {
        if (busy) { toast("当前任务完成后即可删除"); return; }
        AppLog.event(this, "记录", "开始删除记录和文件", result.sourceName + "\n生成编号  " + result.id);
        setBusy(true);
        publish("正在删除记录和文件", -1);
        worker.execute(() -> {
            try {
                ApkProtector.deleteRecord(this, result);
                AppLog.event(this, "记录", "记录和文件删除完成", "生成编号  " + result.id);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    if (pendingExport != null && pendingExport.getParentFile().equals(result.apk.getParentFile())) {
                        pendingExport = null;
                    }
                    setBusy(false);
                    reloadRecords();
                    toast("记录及对应文件已删除");
                });
            } catch (Exception error) {
                runOnUiThread(() -> { if (!isDestroyed()) reloadRecords(); });
                showError("删除失败", new java.io.IOException("删除 " + result.sourceName + "（" + result.id
                        + "）失败。已标记的待删除文件将在下次启动时继续清理。", error));
            }
        });
    }

    private void cleanDeletedRecords() {
        try { ApkProtector.cleanDeletedRecords(this); }
        catch (java.io.IOException error) {
            try {
                ErrorLogStore.save(this, ErrorLogStore.describe(this, "待删除记录清理失败",
                        android.util.Log.getStackTraceString(error)));
            } catch (java.io.IOException logError) { android.util.Log.e("DoroEncrypt", "无法保存清理日志", logError); }
        }
    }

    private void export(File source, String name, String mime) {
        if (busy) { toast("当前任务完成后即可导出"); return; }
        AppLog.event(this, "导出", "选择保存位置", "文件  " + name + "\n类型  " + mime);
        pendingExport = source;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(mime);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(intent, EXPORT);
    }

    private void saveExport(android.net.Uri uri) {
        File source = pendingExport;
        AppLog.event(this, "导出", "开始导出", "文件  " + source.getName() + "\n字节  " + source.length());
        pendingExport = null;
        setBusy(true);
        publish("正在导出文件", 0);
        worker.execute(() -> {
            try {
                try (InputStream input = new FileInputStream(source);
                    OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new java.io.IOException("无法打开保存位置");
                ApkProtector.copy(input, output, done -> publish("正在导出文件",
                        ProtectionProgress.percent(done, source.length(), 0, 100)));
                output.flush();
                }
                AppLog.event(this, "导出", "导出完成", "文件  " + source.getName() + "\n字节  " + source.length());
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    setBusy(false);
                    toast("文件已保存");
                });
            } catch (Exception error) { showError("保存失败", error); }
        });
    }

    private void setBusy(boolean value) {
        busy = value;
        lastStage = null;
        lastPercent = -2;
        progressPanel.setVisibility(value ? View.VISIBLE : View.GONE);
        setRequestedOrientation(value ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        if (value) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        refreshForm();
    }

    private void refreshForm() {
        boolean selected = inputFile != null;
        fileName.setText(selected ? inputName : "添加文件");
        fileDetail.setText(selected ? (inputName.toLowerCase(Locale.ROOT).endsWith(".sh") ? "SH" : "APK")
                + " · " + android.text.format.Formatter.formatFileSize(this, inputFile.length()) : "APK 或 SH");
        fileAction.setText(selected ? "更换" : "选择");
        fileRow.setAlpha(busy ? 0.55f : 1f);
        policyValue.setText(POLICY_LABELS[policyIndex]);
        policyRow.setVisibility(selected && inputName.toLowerCase(Locale.ROOT).endsWith(".apk") ? View.VISIBLE : View.GONE);
        policyRow.setAlpha(busy ? 0.55f : 1f);
        validityValue.setText(durationText(validHours) + "  ›");
        validityTitle.setText("激活有效期");
        validityHint.setText(usageHours == 0 ? "从开始加密起算，期限内激活后本机长期使用"
                : "从开始加密起算，使用期限到达后，已激活设备也会拦截");
        usageValue.setText((usageHours == 0 ? "不限" : durationText(usageHours)) + "  ›");
        usageRow.setAlpha(busy ? 0.55f : 1f);
        validityRow.setAlpha(busy ? 0.55f : 1f);
        start.setEnabled(selected && !busy);
        start.setAlpha(selected && !busy ? 1f : 0.38f);
        start.setText(busy ? "处理中…" : "开始加密");
    }

    private void showError(String action, Throwable error) {
        String stage = lastStage == null ? "准备处理" : lastStage;
        String summary = action + " · v" + versionName() + "\n阶段  " + stage
                + "\n" + error.getClass().getName() + "\n"
                + (error.getMessage() == null ? "未提供错误说明" : error.getMessage());
        String body = "阶段  " + stage + "\n文件  " + (inputName == null ? "未选择" : inputName)
                + "\n大小  " + (inputFile == null ? "未知" : inputFile.length() + " 字节")
                + "\n\n" + android.util.Log.getStackTraceString(error);
        String report = ErrorLogStore.describe(this, action, body);
        try { ErrorLogStore.save(this, report); }
        catch (java.io.IOException logError) {
            android.util.Log.e("DoroEncrypt", "无法保存错误日志", logError);
            report += "\n\n日志保存失败\n" + android.util.Log.getStackTraceString(logError);
        }
        String detail = report;
        runOnUiThread(() -> {
            if (isDestroyed()) return;
            setBusy(false);
            notice.setText(summary);
            notice.setTextIsSelectable(true);
            new AlertDialog.Builder(this).setTitle(action).setMessage(detail)
                    .setPositiveButton("复制错误详情", (dialog, which) -> {
                        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                                .setPrimaryClip(ClipData.newPlainText("Doro加密错误详情", detail));
                        toast("错误详情已复制");
                    }).setNeutralButton("查看日志", (dialog, which) -> openLogs())
                    .setNegativeButton("关闭", null).show();
        });
    }

    private String versionName() {
        return ErrorLogStore.versionName(this);
    }

    private void openLogs() { setTab(3); }

    private String safeName(String name) { return name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_"); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); return view; }
    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(dp(2), 1);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }
    private void add(LinearLayout parent, View view, int margin) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(margin);
        parent.addView(view, layout);
    }
    private Button action(String text, boolean primary, Runnable callback) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setTypeface(Typeface.DEFAULT, primary ? Typeface.BOLD : Typeface.NORMAL);
        button.setTextColor(primary ? Color.WHITE : BLUE);
        button.setBackgroundTintList(null);
        button.setBackground(shape(primary ? BLUE : Color.rgb(238, 245, 255), 14));
        button.setMinHeight(dp(54));
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setElevation(0);
        button.setStateListAnimator(null);
        button.setOnClickListener(view -> callback.run());
        return button;
    }
    private TextView textAction(String text, Runnable callback) {
        TextView view = label(text, 14, GRAY, false);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(10), dp(12), dp(10), dp(12));
        view.setOnClickListener(item -> callback.run());
        return view;
    }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private Dialog sheet() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        return dialog;
    }
    private LinearLayout sheetContent(Dialog dialog) {
        LinearLayout content = column();
        content.setPadding(dp(24), dp(26), dp(24), dp(20));
        content.setBackground(shape(Color.WHITE, 24));
        dialog.setContentView(content);
        return content;
    }
    private void sizeSheet(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setGravity(Gravity.BOTTOM);
        window.setLayout(-1, -2);
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        android.view.WindowManager.LayoutParams layout = window.getAttributes();
        layout.dimAmount = 0.25f;
        window.setAttributes(layout);
    }

    @Override public void onSaveInstanceState(Bundle state) {
        if (inputFile != null) state.putString("inputPath", inputFile.getPath());
        state.putString("inputName", inputName);
        if (pendingExport != null) state.putString("pendingExport", pendingExport.getPath());
        state.putInt("isolationPolicy", policyIndex);
        state.putInt("validHours", validHours);
        state.putInt("usageHours", usageHours);
        state.putInt("tab", currentTab);
        state.putString("search", search.getText().toString());
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }

    private final class RecordAdapter extends BaseAdapter {
        @Override public int getCount() { return filtered.size(); }
        @Override public ApkProtector.Result getItem(int position) { return filtered.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            RecordRow holder;
            if (recycled == null) {
                holder = new RecordRow();
                recycled = holder.outer;
                recycled.setTag(holder);
            } else holder = (RecordRow) recycled.getTag();
            holder.bind(getItem(position));
            return recycled;
        }
    }

    private final class RecordRow {
        final LinearLayout outer = column();
        final TextView name = label("", 16, INK, true);
        final TextView identity = label("", 13, INK, false);
        final TextView details = label("", 12, GRAY, false);
        final TextView keyPreview = label("", 12, GRAY, false);
        final TextView copy = label("复制密钥", 14, BLUE, false);
        final TextView export = label("导出文件", 14, BLUE, false);
        final TextView exportKey = label("导出密钥", 14, BLUE, false);
        final TextView delete = label("删除", 14, Color.rgb(215, 55, 55), false);
        ApkProtector.Result record;
        RecordRow() {
            outer.setPadding(dp(20), dp(7), dp(20), dp(7));
            LinearLayout card = column();
            card.setPadding(dp(18), dp(18), dp(18), dp(10));
            card.setBackground(shape(Color.WHITE, 18));
            outer.addView(card);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(name);
            add(card, identity, 8);
            add(card, details, 6);
            keyPreview.setTypeface(Typeface.MONOSPACE);
            keyPreview.setMaxLines(2);
            keyPreview.setEllipsize(android.text.TextUtils.TruncateAt.END);
            add(card, keyPreview, 14);
            LinearLayout actions = row();
            actions.setGravity(Gravity.CENTER_VERTICAL);
            for (TextView item : new TextView[]{copy, export, exportKey, delete}) {
                item.setGravity(Gravity.CENTER);
                item.setMinHeight(dp(48));
                item.setPadding(0, dp(14), 0, dp(8));
                actions.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
            }
            add(card, actions, 4);
            copy.setOnClickListener(view -> copyKey(record));
            export.setOnClickListener(view -> exportFile(record));
            exportKey.setOnClickListener(view -> MainActivity.this.exportKey(record));
            delete.setOnClickListener(view -> confirmDelete(record));
        }
        void bind(ApkProtector.Result record) {
            this.record = record;
            name.setText(record.sourceName);
            identity.setText(record.packageName + "\n" + record.id);
            details.setText((record.script ? "SH" : "APK") + " · "
                    + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(record.createdAt))
                    + "\n" + expiryText(record));
            keyPreview.setText(record.key);
        }
    }

}
