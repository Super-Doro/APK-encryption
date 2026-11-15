package com.function.cardissuer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Fragment;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keeps the current conversion and temporary results across Activity recreation. */
public final class AudioConversionFragment extends Fragment {
    private static final int SELECT_FILE = 101;
    private static final int SELECT_FOLDER = 102;
    private static final int EXPORT_FILE = 103;
    private static final int EXPORT_FOLDER = 104;
    private static final int BLUE = Color.rgb(0, 122, 255);
    private static final int INK = Color.rgb(28, 28, 30);
    private static final int GRAY = Color.rgb(142, 142, 147);
    private static final int BACKGROUND = Color.WHITE;
    private enum Phase { STARTING, IDLE, SCANNING, CONVERTING, READY, PICKING, EXPORTING, CLEANING }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<AudioConverter.Source> sources = new ArrayList<>();
    private final List<AudioConverter.Output> outputs = new ArrayList<>();
    private Context app;
    private AudioFiles files;
    private volatile AudioConverter converter;
    private volatile boolean cancelled;
    private volatile boolean destroyed;
    private Phase phase = Phase.STARTING;
    private Uri selectionUri;
    private boolean folder;
    private boolean pickerOpen;
    private boolean initialized;
    private String jobName;
    private String selectionText = "选择音频或包含音频的文件夹";
    private String message = "";
    private String stage = "正在准备";
    private String completionSummary = "";
    private int percent = -1;
    private int formatIndex;
    private int deferredRequest;
    private int deferredResponse;
    private Intent deferredData;
    private Dialog completionDialog;
    private Dialog formatDialog;
    private Button fileButton;
    private Button folderButton;
    private Button convertButton;
    private TextView selectionLabel;
    private TextView formatValue;
    private TextView status;
    private TextView progressLabel;
    private TextView cancelButton;
    private LinearLayout formatRow;
    private LinearLayout preview;
    private LinearLayout progressPanel;
    private ProgressBar progressBar;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setRetainInstance(true);
        app = getActivity().getApplicationContext();
        files = new AudioFiles(app);
        String restoreJob = null;
        if (state != null) {
            String uri = state.getString("source");
            if (uri != null) selectionUri = Uri.parse(uri);
            folder = state.getBoolean("folder");
            formatIndex = Math.max(0, Math.min(AudioConverter.Format.values().length - 1, state.getInt("format")));
            restoreJob = state.getString("job");
            pickerOpen = state.getBoolean("pickerOpen");
            completionSummary = state.getString("summary", "转换完成");
        }
        String previousUri = app.getSharedPreferences("audio-source", Context.MODE_PRIVATE).getString("uri", null);
        if (previousUri != null && (selectionUri == null || !previousUri.equals(selectionUri.toString()))) {
            releaseSourcePermission(Uri.parse(previousUri));
            app.getSharedPreferences("audio-source", Context.MODE_PRIVATE).edit().remove("uri").apply();
        }
        String keptJob = restoreJob;
        worker.execute(() -> initialize(keptJob));
    }

    private void initialize(String keptJob) {
        List<AudioConverter.Output> restored = new ArrayList<>();
        String errorMessage = "";
        try {
            files.clearAbandoned(keptJob);
            if (keptJob != null) {
                File job = files.job(keptJob);
                if (new File(job, "outputs.json").isFile()) restored = files.restore(job);
                else files.delete(job);
            }
        } catch (Exception error) {
            log("音频临时文件恢复失败", error);
            errorMessage = "上次任务未完成，请重新转换。详细信息已保存到日志。";
            try { if (keptJob != null) files.delete(files.job(keptJob)); }
            catch (IOException cleanupError) { log("音频临时文件清理失败", cleanupError); }
        }
        List<AudioConverter.Output> ready = restored;
        String notice = errorMessage;
        post(() -> {
            initialized = true;
            outputs.addAll(ready);
            jobName = ready.isEmpty() ? null : keptJob;
            phase = ready.isEmpty() ? Phase.IDLE : Phase.READY;
            message = notice;
            if (deferredRequest != 0) {
                int request = deferredRequest;
                deferredRequest = 0;
                handleResult(request, deferredResponse, deferredData);
                deferredData = null;
                return;
            }
            if (!ready.isEmpty()) {
                if (!pickerOpen) maybeShowCompleted();
            } else if (selectionUri != null && !pickerOpen) scanSelection();
            render();
        });
    }

    @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        ScrollView scroll = new ScrollView(getActivity());
        scroll.setBackgroundColor(BACKGROUND);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, LiquidGlassNavigation.contentBottomInset(getActivity()));
        LinearLayout page = column();
        page.setPadding(dp(24), dp(24), dp(24), dp(24));
        scroll.addView(page);
        add(page, label("音频转换", 32, INK, true), 0);

        LinearLayout selection = section();
        selectionLabel = label(selectionText, 16, INK, true);
        selectionLabel.setMaxLines(3);
        selection.addView(selectionLabel);
        LinearLayout actions = row();
        fileButton = button("选择文件", false, () -> select(false));
        folderButton = button("选择文件夹", false, () -> select(true));
        actions.addView(fileButton, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, -2, 1);
        second.leftMargin = dp(10);
        actions.addView(folderButton, second);
        add(selection, actions, 12);
        add(page, selection, 28);

        formatRow = row();
        formatRow.setGravity(Gravity.CENTER_VERTICAL);
        formatRow.setPadding(0, dp(20), 0, dp(20));
        formatRow.setBackground(new FlatDividerDrawable(getActivity()));
        formatRow.addView(label("转换为", 16, INK, false), new LinearLayout.LayoutParams(0, -2, 1));
        formatValue = label("", 15, BLUE, false);
        formatRow.addView(formatValue);
        formatRow.setOnClickListener(view -> { if (phase == Phase.IDLE) chooseFormat(); });
        add(page, formatRow, 0);

        preview = section();
        add(page, preview, 0);
        convertButton = button("开始转换", true, this::startConversion);
        add(page, convertButton, 24);
        status = label("", 13, GRAY, false);
        status.setTextIsSelectable(true);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(page, status, 14);

        progressPanel = section();
        progressLabel = label(stage, 14, INK, true);
        progressPanel.addView(progressLabel);
        progressBar = new ProgressBar(getActivity(), null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgressTintList(ColorStateList.valueOf(BLUE));
        progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(229, 229, 234)));
        add(progressPanel, progressBar, 12);
        cancelButton = label("取消", 15, BLUE, false);
        cancelButton.setGravity(Gravity.CENTER);
        cancelButton.setPadding(0, dp(16), 0, dp(4));
        cancelButton.setMinHeight(dp(48));
        cancelButton.setOnClickListener(view -> cancelOperation());
        add(progressPanel, cancelButton, 4);
        add(page, progressPanel, 16);
        render();
        return scroll;
    }

    @Override public void onResume() {
        super.onResume();
        maybeShowCompleted();
    }

    private void select(boolean tree) {
        if (phase != Phase.IDLE || pickerOpen) return;
        Intent intent = new Intent(tree ? Intent.ACTION_OPEN_DOCUMENT_TREE : Intent.ACTION_OPEN_DOCUMENT);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (!tree) intent.setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        try {
            pickerOpen = true;
            startActivityForResult(intent, tree ? SELECT_FOLDER : SELECT_FILE);
            render();
        } catch (RuntimeException error) {
            pickerOpen = false;
            fail("无法打开文件选择器", error);
        }
    }

    @Override public void onActivityResult(int request, int response, Intent data) {
        super.onActivityResult(request, response, data);
        if (!initialized) {
            deferredRequest = request;
            deferredResponse = response;
            deferredData = data;
            return;
        }
        handleResult(request, response, data);
    }

    private void handleResult(int request, int response, Intent data) {
        if (request < SELECT_FILE || request > EXPORT_FOLDER) return;
        pickerOpen = false;
        boolean exporting = request == EXPORT_FILE || request == EXPORT_FOLDER;
        if (response != Activity.RESULT_OK || data == null || data.getData() == null) {
            if (exporting) discard("已取消保存，转换后的临时文件已清理");
            else render();
            return;
        }
        Uri uri = data.getData();
        if (exporting) {
            if (outputs.isEmpty()) {
                // A process restart can arrive here after the cache was reclaimed.
                if (request == EXPORT_FILE) deleteEmptyDocument(uri);
                message = "临时音频已被系统清理，请重新转换";
                phase = Phase.IDLE;
                render();
            } else exportTo(uri, request == EXPORT_FOLDER);
            return;
        }
        if (selectionUri != null && !selectionUri.equals(uri)) releaseSourcePermission(selectionUri);
        selectionUri = uri;
        folder = request == SELECT_FOLDER;
        try { app.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException ignored) { }
        app.getSharedPreferences("audio-source", Context.MODE_PRIVATE).edit().putString("uri", uri.toString()).apply();
        scanSelection();
    }

    private void scanSelection() {
        if (selectionUri == null) return;
        AppLog.event(app, "音频", "开始识别音频", folder ? "扫描文件夹及子文件夹" : "识别单个音频文件");
        Uri uri = selectionUri;
        boolean tree = folder;
        sources.clear();
        phase = Phase.SCANNING;
        cancelled = false;
        message = "";
        selectionText = tree ? "正在扫描文件夹和子文件夹" : "正在识别音频";
        updateProgress("正在识别文件内容", -1);
        render();
        worker.execute(() -> {
            try {
                checkCancelled();
                AudioConverter engine = new AudioConverter(app);
                converter = engine;
                if (cancelled) engine.cancel();
                AudioConverter.Selection selection = engine.scan(uri, tree, this::publish);
                checkCancelled();
                String name = tree ? "文件夹" : engine.documentName(uri);
                AppLog.event(app, "音频", "音频识别完成", "文件  " + name + "\n扫描  " + selection.scanned
                        + "\n识别  " + selection.sources.size() + "\n跳过  " + selection.skipped
                        + "\n读取失败  " + selection.unreadable);
                post(() -> {
                    converter = null;
                    if (!cancelled) sources.addAll(selection.sources);
                    selectionText = tree ? "已识别 " + sources.size() + " 个音频文件" : name;
                    message = tree ? "已扫描 " + selection.scanned + " 个文件，跳过 " + selection.skipped
                            + " 个非音频或无法识别的文件"
                            + (selection.unreadable == 0 ? "" : "，另有 " + selection.unreadable + " 项读取失败，详情见日志") : "";
                    if (sources.isEmpty()) message += "\n没有找到可转换的音频";
                    if (cancelled) message = "已取消识别";
                    phase = Phase.IDLE;
                    render();
                });
            } catch (CancellationException error) {
                post(() -> {
                    converter = null;
                    phase = Phase.IDLE;
                    selectionText = "选择音频或包含音频的文件夹";
                    message = "已取消识别";
                    render();
                });
            } catch (Exception | LinkageError error) { post(() -> fail("音频识别失败", error)); }
        });
    }

    private void chooseFormat() {
        if (formatDialog != null) return;
        Dialog dialog = new Dialog(getActivity());
        formatDialog = dialog;
        dialog.setOnDismissListener(closed -> { if (formatDialog == dialog) formatDialog = null; });
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        ScrollView scroll = new ScrollView(getActivity());
        LinearLayout content = column();
        content.setPadding(dp(24), dp(24), dp(24), dp(24));
        content.setBackgroundColor(Color.WHITE);
        content.addView(label("输出格式", 23, INK, true));
        AudioConverter.Format[] formats = AudioConverter.Format.values();
        for (int i = 0; i < formats.length; i++) {
            int choice = i;
            Button option = button(formats[i].title + (formatIndex == i ? "  ✓" : ""), false, () -> {
                formatIndex = choice;
                render();
                dialog.dismiss();
            });
            option.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            option.setPadding(0, dp(14), 0, dp(14));
            option.setBackground(new FlatDividerDrawable(getActivity()));
            add(content, option, 0);
        }
        scroll.addView(content);
        dialog.setContentView(scroll);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(-1, Math.round(getResources().getDisplayMetrics().heightPixels * 0.8f));
        }
    }

    private void startConversion() {
        if (phase != Phase.IDLE || sources.isEmpty()) return;
        List<AudioConverter.Source> inputs = new ArrayList<>(sources);
        AudioConverter.Format format = AudioConverter.Format.values()[formatIndex];
        AppLog.event(app, "音频", "开始音频转换", "文件数  " + inputs.size() + "\n目标格式  " + format.title);
        jobName = "job-" + UUID.randomUUID();
        File directory;
        try { directory = files.job(jobName); }
        catch (IOException error) { fail("无法创建转换任务", error); return; }
        phase = Phase.CONVERTING;
        cancelled = false;
        message = "";
        updateProgress("准备转换 " + inputs.size() + " 个文件", 0);
        render();
        worker.execute(() -> convert(inputs, format, directory));
    }

    private void convert(List<AudioConverter.Source> inputs, AudioConverter.Format format, File directory) {
        List<AudioConverter.Output> converted = new ArrayList<>();
        int failures = 0;
        Throwable firstFailure = null;
        String firstFailedFile = null;
        try {
            checkCancelled();
            files.create(directory);
            AudioConverter engine = new AudioConverter(app);
            converter = engine;
            if (cancelled) engine.cancel();
            HashSet<String> names = new HashSet<>();
            for (int i = 0; i < inputs.size(); i++) {
                checkCancelled();
                int index = i;
                AudioConverter.Source source = inputs.get(i);
                AppLog.event(app, "音频", "正在转换文件", "文件  " + source.path + "\n输入  " + source.type
                        + "\n输出  " + format.title);
                publish("正在转换 " + (i + 1) + "/" + inputs.size() + "\n" + source.path, i * 100 / inputs.size());
                try {
                    converted.add(engine.convert(source, format, directory, i, names, (name, progress) -> {
                        if (!cancelled) publish("正在转换 " + (index + 1) + "/" + inputs.size() + "\n" + name,
                                (index * 100 + progress) / inputs.size());
                    }));
                } catch (IOException | SecurityException error) {
                    failures++;
                    if (firstFailure == null) {
                        firstFailure = error;
                        firstFailedFile = source.path;
                    }
                    engine.log("音频转换失败", source.path, error);
                }
            }
            checkCancelled();
            if (converted.isEmpty()) {
                throw new IOException("所有文件均转换失败\n目标格式  " + format.title
                        + "\n首个失败文件  " + firstFailedFile
                        + "\n\n" + (firstFailure == null ? "未返回失败详情" : firstFailure.getMessage()), firstFailure);
            }
            files.save(directory, converted);
            checkCancelled();
            String summary = "成功转换 " + converted.size() + " 个文件"
                    + (failures == 0 ? "" : "，失败 " + failures + " 个，详情见日志");
            AppLog.event(app, "音频", "音频转换完成", summary);
            post(() -> {
                converter = null;
                outputs.addAll(converted);
                completionSummary = summary;
                phase = Phase.READY;
                if (cancelled) {
                    discard("转换已取消，临时文件已清理");
                    return;
                }
                render();
                maybeShowCompleted();
            });
        } catch (CancellationException error) {
            cleanAfterOperation(directory, "转换已取消，临时文件已清理", null);
        } catch (Exception | LinkageError error) {
            cleanAfterOperation(directory, "音频转换失败", error);
        }
    }

    private void maybeShowCompleted() {
        Activity activity = getActivity();
        if (phase != Phase.READY || outputs.isEmpty() || pickerOpen || completionDialog != null
                || !isResumed() || activity == null || activity.isFinishing()) return;
        boolean batch = folder || outputs.size() > 1;
        completionDialog = new AlertDialog.Builder(activity).setTitle("转换完成")
                .setMessage(completionSummary + "\n\n" + (batch ? "选择文件夹保存转换结果。" : "选择位置保存转换后的音频。")
                        + "取消将自动清理临时文件。")
                .setPositiveButton("导出保存", (dialog, which) -> {
                    completionDialog = null;
                    chooseExport(batch);
                })
                .setNegativeButton("取消并清理", (dialog, which) -> {
                    completionDialog = null;
                    discard("已取消保存，转换后的临时文件已清理");
                }).create();
        completionDialog.setOnCancelListener(dialog -> {
            completionDialog = null;
            discard("已取消保存，转换后的临时文件已清理");
        });
        completionDialog.show();
    }

    private void chooseExport(boolean batch) {
        Intent intent = new Intent(batch ? Intent.ACTION_OPEN_DOCUMENT_TREE : Intent.ACTION_CREATE_DOCUMENT);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (!batch) {
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(AudioConverter.Format.values()[formatIndex].mime);
            String path = outputs.get(0).path;
            intent.putExtra(Intent.EXTRA_TITLE, path.substring(path.lastIndexOf('/') + 1));
        }
        try {
            pickerOpen = true;
            phase = Phase.PICKING;
            startActivityForResult(intent, batch ? EXPORT_FOLDER : EXPORT_FILE);
            render();
        } catch (RuntimeException error) {
            pickerOpen = false;
            phase = Phase.READY;
            log("无法打开音频保存位置", error);
            message = "无法打开保存位置，请重试或取消清理";
            render();
            maybeShowCompleted();
        }
    }

    private void exportTo(Uri uri, boolean batch) {
        AppLog.event(app, "音频导出", "开始导出音频", "文件数  " + outputs.size() + "\n批量  " + batch);
        List<AudioConverter.Output> saved = new ArrayList<>(outputs);
        AudioConverter.Format format = AudioConverter.Format.values()[formatIndex];
        File directory;
        try { directory = files.job(jobName); }
        catch (IOException error) { fail("临时文件不可用", error); return; }
        phase = Phase.EXPORTING;
        cancelled = false;
        updateProgress("正在导出", 0);
        render();
        worker.execute(() -> {
            try {
                files.export(saved, uri, batch, format, this::checkCancelled, this::publish);
                AppLog.event(app, "音频导出", "音频导出完成", "文件数  " + saved.size());
                cleanAfterOperation(directory, "已导出 " + saved.size() + " 个文件，临时文件已清理", null);
            } catch (CancellationException error) {
                cleanAfterOperation(directory, "导出已取消，临时文件已清理", null);
            } catch (Exception error) {
                log("音频导出失败", error);
                post(() -> {
                    phase = Phase.READY;
                    completionSummary = "保存失败，可重新导出。详细原因已保存到日志。";
                    message = completionSummary;
                    render();
                    maybeShowCompleted();
                });
            }
        });
    }

    private void discard(String done) {
        if (phase == Phase.CLEANING) return;
        File directory;
        try { directory = jobName == null ? null : files.job(jobName); }
        catch (IOException error) { fail("临时文件清理失败", error); return; }
        phase = Phase.CLEANING;
        updateProgress("正在清理临时文件", -1);
        render();
        worker.execute(() -> cleanAfterOperation(directory, done, null));
    }

    private void cleanAfterOperation(File directory, String text, Throwable failure) {
        String result = text;
        if (failure != null) {
            log(text, failure);
            result += "\n" + readableError(failure) + "\n详细信息已保存到日志";
        }
        try { files.delete(directory); }
        catch (IOException error) {
            log("音频临时文件清理失败", error);
            result = "临时文件清理失败，下次启动时重试。详细信息已保存到日志。";
        }
        String notice = result;
        AppLog.event(app, "音频", "任务结束并清理临时文件", notice);
        post(() -> {
            converter = null;
            outputs.clear();
            jobName = null;
            phase = Phase.IDLE;
            message = notice;
            render();
            if (getActivity() != null) Toast.makeText(getActivity(), notice.split("\n", 2)[0], Toast.LENGTH_SHORT).show();
        });
    }

    private void cancelOperation() {
        if (phase != Phase.SCANNING && phase != Phase.CONVERTING && phase != Phase.EXPORTING) return;
        AppLog.event(app, "音频", "取消当前任务", "阶段  " + phase.name());
        cancelled = true;
        AudioConverter engine = converter;
        if (engine != null) engine.cancel();
        updateProgress("正在取消并清理", -1);
        render();
    }

    private void checkCancelled() {
        if (cancelled || destroyed || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    private void publish(String text, int value) { post(() -> updateProgress(text, value)); }

    private void updateProgress(String text, int value) {
        stage = text;
        percent = value;
        if (progressLabel != null) {
            progressLabel.setText(text + (value < 0 ? "" : "\n" + value + "%"));
            progressBar.setIndeterminate(value < 0);
            if (value >= 0) progressBar.setProgress(Math.max(0, Math.min(100, value)));
        }
    }

    private void render() {
        if (selectionLabel == null) return;
        boolean idle = phase == Phase.IDLE && !pickerOpen;
        fileButton.setEnabled(idle);
        folderButton.setEnabled(idle);
        fileButton.setAlpha(idle ? 1 : 0.45f);
        folderButton.setAlpha(idle ? 1 : 0.45f);
        selectionLabel.setText(selectionText);
        formatValue.setText(AudioConverter.Format.values()[formatIndex].title + "  ›");
        formatRow.setAlpha(idle ? 1 : 0.55f);
        convertButton.setEnabled(idle && !sources.isEmpty());
        convertButton.setAlpha(idle && !sources.isEmpty() ? 1 : 0.38f);
        status.setText(message);
        status.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        boolean running = phase == Phase.STARTING || phase == Phase.SCANNING || phase == Phase.CONVERTING
                || phase == Phase.EXPORTING || phase == Phase.CLEANING;
        progressPanel.setVisibility(running ? View.VISIBLE : View.GONE);
        progressPanel.setKeepScreenOn(running);
        cancelButton.setVisibility(phase == Phase.SCANNING || phase == Phase.CONVERTING || phase == Phase.EXPORTING
                ? View.VISIBLE : View.GONE);
        cancelButton.setEnabled(!cancelled);
        updateProgress(stage, percent);
        preview.removeAllViews();
        preview.setVisibility(sources.isEmpty() ? View.GONE : View.VISIBLE);
        if (!sources.isEmpty()) {
            Map<String, Integer> types = new LinkedHashMap<>();
            for (AudioConverter.Source source : sources) types.merge(source.type, 1, Integer::sum);
            StringBuilder summary = new StringBuilder("已识别格式");
            for (Map.Entry<String, Integer> entry : types.entrySet()) {
                summary.append("\n").append(entry.getKey()).append("  ").append(entry.getValue()).append(" 个");
            }
            add(preview, label(summary.toString(), 13, GRAY, false), 0);
            for (int i = 0; i < Math.min(8, sources.size()); i++) {
                TextView item = label(sources.get(i).path, 14, INK, false);
                item.setMaxLines(2);
                item.setEllipsize(android.text.TextUtils.TruncateAt.END);
                add(preview, item, 12);
            }
            if (sources.size() > 8) add(preview, label("另有 " + (sources.size() - 8) + " 个文件", 13, GRAY, false), 12);
        }
    }

    private void fail(String title, Throwable error) {
        converter = null;
        log(title, error);
        phase = Phase.IDLE;
        message = title + "\n" + readableError(error) + "\n详细信息已保存到日志";
        render();
    }

    private String readableError(Throwable error) {
        if (error instanceof LinkageError) return "音频引擎加载失败，请使用64位 Android 设备";
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private void log(String title, Throwable error) {
        try { ErrorLogStore.save(app, ErrorLogStore.describe(app, title, android.util.Log.getStackTraceString(error))); }
        catch (IOException logError) { android.util.Log.e("DoroAudio", "日志保存失败", logError); }
    }

    private void deleteEmptyDocument(Uri uri) {
        worker.execute(() -> {
            try {
                if (!android.provider.DocumentsContract.deleteDocument(app.getContentResolver(), uri)) {
                    throw new IOException("无法删除空的导出文件");
                }
            }
            catch (Exception error) { log("未完成的音频导出清理失败", error); }
        });
    }

    private void releaseSourcePermission(Uri uri) {
        try { app.getContentResolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException ignored) { }
    }

    private void post(Runnable action) { main.post(() -> { if (!destroyed) action.run(); }); }

    @Override public void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (selectionUri != null) state.putString("source", selectionUri.toString());
        state.putBoolean("folder", folder);
        state.putInt("format", formatIndex);
        state.putString("job", jobName);
        state.putBoolean("pickerOpen", pickerOpen);
        state.putString("summary", completionSummary);
    }

    @Override public void onDestroyView() {
        if (formatDialog != null) {
            formatDialog.dismiss();
            formatDialog = null;
        }
        if (completionDialog != null) {
            completionDialog.setOnCancelListener(null);
            completionDialog.dismiss();
            completionDialog = null;
        }
        fileButton = null;
        folderButton = null;
        convertButton = null;
        selectionLabel = null;
        formatValue = null;
        formatRow = null;
        preview = null;
        status = null;
        progressPanel = null;
        progressLabel = null;
        progressBar = null;
        cancelButton = null;
        super.onDestroyView();
    }

    @Override public void onDestroy() {
        destroyed = true;
        cancelled = true;
        AudioConverter engine = converter;
        if (engine != null) engine.cancel();
        if (selectionUri != null) releaseSourcePermission(selectionUri);
        String cleanupJob = jobName;
        worker.execute(() -> {
            try { if (cleanupJob != null) files.delete(files.job(cleanupJob)); }
            catch (IOException error) { log("音频临时文件清理失败", error); }
        });
        worker.shutdown();
        super.onDestroy();
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(getActivity());
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }
    private LinearLayout row() { return new LinearLayout(getActivity()); }
    private LinearLayout section() {
        LinearLayout view = column();
        view.setPadding(0, dp(20), 0, dp(20));
        view.setBackground(new FlatDividerDrawable(getActivity()));
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
    private Button button(String text, boolean primary, Runnable action) {
        Button view = new Button(getActivity());
        view.setText(text);
        view.setAllCaps(false);
        view.setTextSize(15);
        view.setTextColor(primary ? Color.WHITE : BLUE);
        view.setTypeface(Typeface.DEFAULT, primary ? Typeface.BOLD : Typeface.NORMAL);
        view.setBackgroundTintList(null);
        view.setBackground(primary ? shape(BLUE, 27) : null);
        view.setPadding(dp(12), dp(12), dp(12), dp(12));
        view.setMinHeight(dp(54));
        view.setElevation(0);
        view.setStateListAnimator(null);
        view.setOnClickListener(item -> action.run());
        return view;
    }
    private void add(LinearLayout parent, View view, int margin) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(margin);
        parent.addView(view, layout);
    }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }
    private int dp(int value) { return Math.round(value * app.getResources().getDisplayMetrics().density); }

}
