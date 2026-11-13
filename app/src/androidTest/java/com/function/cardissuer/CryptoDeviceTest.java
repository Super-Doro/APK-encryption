package com.function.cardissuer;

import android.app.Instrumentation;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import com.function.protocol.ActivationKeys;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.ZipFile;
import org.json.JSONObject;

public final class CryptoDeviceTest extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments = args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String mode = arguments.getString("mode", "build");
            if (mode.equals("ui")) exerciseGenerator();
            else if (mode.equals("build-target")) buildTarget();
            else if (mode.equals("build-scenarios")) buildScenarios();
            else if (mode.equals("build")) buildFixtures();
            else activate(mode);
            result.putString("stream", "PASS " + mode);
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL " + android.util.Log.getStackTraceString(error));
            finish(0, result);
        }
    }

    private void buildScenarios() throws Exception {
        File external = getTargetContext().getExternalFilesDir(null);
        JSONObject results = new JSONObject();
        for (String scenario : new String[]{"plain", "preferences", "mixed"}) {
            ApkProtector.Result output = ApkProtector.protect(getTargetContext(), new File(external, scenario + ".apk"), message -> {});
            try (ZipFile zip = new ZipFile(output.apk)) {
                JSONObject config = new JSONObject(new String(ApkProtector.read(zip.getInputStream(zip.getEntry(
                        "assets/function-protect/config.json")), 1024 * 1024), StandardCharsets.UTF_8));
                org.json.JSONArray plans = config.getJSONArray("isolationPlans");
                int original = 0;
                int minimal = 0;
                for (int i = 0; i < plans.length(); i++) {
                    String mode = plans.getJSONObject(i).getString("applicationMode");
                    if (mode.equals("original")) original++;
                    if (mode.equals("minimal")) minimal++;
                }
                require(minimal == 1 && original == (scenario.equals("mixed") ? 1 : 0), "Wrong strategy for " + scenario + ": " + plans);
                byte[] shell = ApkProtector.read(zip.getInputStream(zip.getEntry("classes.dex")), 4 * 1024 * 1024);
                org.jf.dexlib2.dexbacked.DexBackedDexFile dex = new org.jf.dexlib2.dexbacked.DexBackedDexFile(null, shell);
                boolean bridge = false;
                for (org.jf.dexlib2.iface.ClassDef definition : dex.getClasses()) {
                    require(!definition.getType().startsWith("Lorg/lsposed/hiddenapibypass/"), "Unrelocated runtime bridge");
                    if (definition.getType().equals("Lcom/function/protect/runtimebridge/HiddenApiBypass;")) bridge = true;
                }
                require(bridge, "Bundled bridge missing");
            }
            results.put(scenario, new JSONObject().put("id", output.id).put("key", output.key).put("packageName", output.packageName));
        }
        for (String policy : new String[]{IsolationPlanner.ORIGINAL, IsolationPlanner.MINIMAL}) {
            ApkProtector.Result output = ApkProtector.protect(getTargetContext(), new File(external, "plain.apk"), policy, message -> {});
            require(output.strategyReport.contains("用户选择"), "Explicit policy was not recorded");
            results.put("forced-" + policy, new JSONObject().put("id", output.id).put("key", output.key).put("packageName", output.packageName));
        }
        Files.write(new File(external, "scenario-results.json").toPath(), results.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void buildTarget() throws Exception {
        File external = getTargetContext().getExternalFilesDir(null);
        ApkProtector.Result output = ApkProtector.protect(getTargetContext(), new File(external, "icebox.apk"), message -> {});
        Files.write(new File(external, "target-result.json").toPath(), new JSONObject().put("id", output.id)
                .put("key", output.key).toString().getBytes(StandardCharsets.UTF_8));
    }

    private void exerciseGenerator() throws Exception {
        MainActivity activity = (MainActivity) startActivitySync(new android.content.Intent(getTargetContext(), MainActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        for (String extension : new String[]{"apk", "sh"}) {
            runOnMainSync(() -> activity.onActivityResult(10, -1, new android.content.Intent().setData(
                    android.net.Uri.parse("content://com.function.cardissuer.test.files/sample." + extension))));
            awaitMainText(activity, "文件已添加");
            if (extension.equals("apk")) runOnMainSync(() -> mainSpinner(activity.getWindow().getDecorView()).setSelection(1));
            runOnMainSync(() -> ((android.widget.Button) mainText(activity.getWindow().getDecorView(),
                    "加密并生成专属密钥")).performClick());
            awaitMainText(activity, "加密完成。");
            if (extension.equals("apk")) {
                require(ApkProtector.history(getTargetContext()).get(0).strategyReport.contains("用户选择"), "UI policy selection was ignored");
                runOnMainSync(() -> ((android.widget.Button) mainText(activity.getWindow().getDecorView(), "查看处理策略")).performClick());
                awaitText("用户选择的初始化策略", 3000);
                awaitText("关闭", 3000).performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            for (String label : new String[]{"导出加密文件", "保存密钥文件"}) {
                runOnMainSync(() -> ((android.widget.Button) mainText(activity.getWindow().getDecorView(), label)).performClick());
                require(awaitText("SAVE", 5000).performAction(AccessibilityNodeInfo.ACTION_CLICK), "Cannot save document");
                awaitMainText(activity, "文件已保存");
            }
        }
        runOnMainSync(() -> {
            android.view.ViewGroup content = activity.findViewById(android.R.id.content);
            ((android.widget.ScrollView) content.getChildAt(0)).fullScroll(android.view.View.FOCUS_UP);
        });
        android.os.SystemClock.sleep(300);
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new File(
                getTargetContext().getExternalFilesDir(null), "generator.png"))) {
            getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        }
    }

    private android.widget.Spinner mainSpinner(android.view.View view) {
        if (view instanceof android.widget.Spinner) return (android.widget.Spinner) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.Spinner spinner = mainSpinner(group.getChildAt(i));
                if (spinner != null) return spinner;
            }
        }
        return null;
    }

    private void awaitMainText(MainActivity activity, String text) throws Exception {
        long deadline = android.os.SystemClock.uptimeMillis() + 15000;
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            java.util.concurrent.atomic.AtomicBoolean found = new java.util.concurrent.atomic.AtomicBoolean();
            runOnMainSync(() -> found.set(mainText(activity.getWindow().getDecorView(), text) != null));
            if (found.get()) return;
            android.os.SystemClock.sleep(50);
        }
        throw new AssertionError("Generator UI timed out " + text);
    }
    private android.view.View mainText(android.view.View node, String text) {
        if (node instanceof android.widget.TextView
                && ((android.widget.TextView) node).getText().toString().contains(text)) return node;
        if (node instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) node;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.view.View found = mainText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void buildFixtures() throws Exception {
        File external = getTargetContext().getExternalFilesDir(null);
        File source = new File(external, "fixture.apk");
        ApkProtector.Result first = ApkProtector.protect(getTargetContext(), source, message -> {});
        ApkProtector.Result second = ApkProtector.protect(getTargetContext(), source, message -> {});
        require(!first.id.equals(second.id) && !first.key.equals(second.key), "Repeated encryption reused key");
        byte[] expected;
        try (ZipFile zip = new ZipFile(first.apk)) {
            JSONObject config = new JSONObject(new String(ApkProtector.read(zip.getInputStream(
                    zip.getEntry("assets/function-protect/config.json")), 1024 * 1024), StandardCharsets.UTF_8));
            expected = java.util.Base64.getUrlDecoder().decode(config.getString("id"));
            byte[] key = ActivationKeys.open(first.key, expected, first.packageName, IssuerKeyStore.loadOrCreate().getPublic());
            byte[] encrypted = ApkProtector.read(zip.getInputStream(zip.getEntry("assets/function-protect/classes.dex.enc")), 1024 * 1024);
            java.io.ByteArrayOutputStream recovered = new java.io.ByteArrayOutputStream();
            ActivationKeys.decryptStream(new java.io.ByteArrayInputStream(encrypted), recovered,
                    key, ActivationKeys.dexAad(expected, "classes.dex"));
            byte[] decrypted = recovered.toByteArray();
            try (ZipFile original = new ZipFile(source)) {
                require(decrypted[0] == 'd' && decrypted[1] == 'e' && decrypted[2] == 'x', "Invalid rewritten DEX");
            }
            try {
                ActivationKeys.open(second.key, expected, first.packageName, IssuerKeyStore.loadOrCreate().getPublic());
                throw new AssertionError("Cross APK key accepted");
            } catch (java.security.GeneralSecurityException correct) {}
        }
        File sh = new File(external, "fixture.sh");
        Files.write(sh.toPath(), "#!/system/bin/sh\nprintf 'SCRIPT_OK:%s\\n' \"$1\"\nexit 7\n".getBytes(StandardCharsets.UTF_8));
        ApkProtector.Result script = ScriptProtector.protect(getTargetContext(), sh, "fixture.sh", message -> {});
        ApkProtector.Result script2 = ScriptProtector.protect(getTargetContext(), sh, "fixture.sh", message -> {});
        require(!script.key.equals(script2.key) && !script.id.equals(script2.id), "SH encryption reused a key");
        assertWrongKey(script.key, second);
        assertWrongKey(first.key, script);
        assertWrongKey(script.key, script2);
        assertWrongKey(script2.key, script);
        File large = new File(external, "large.sh");
        try (java.io.OutputStream output = new java.io.FileOutputStream(large)) {
            byte[] comment = ("#" + "x".repeat(1022) + "\n").getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < 28 * 1024; i++) output.write(comment);
            output.write("printf 'LARGE_SCRIPT_OK\\n'\nexit 9\n".getBytes(StandardCharsets.UTF_8));
        }
        ApkProtector.Result largeScript = ScriptProtector.protect(getTargetContext(), large, "large.sh", message -> {});
        streamChecks();
        Files.copy(first.apk.toPath(), new File(external, "protected-a.apk").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(second.apk.toPath(), new File(external, "protected-b.apk").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(script.apk.toPath(), new File(external, "protected.sh").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        JSONObject results = new JSONObject().put("a", first.key).put("b", second.key).put("sh", script.key)
                .put("sh2", script2.key).put("shLarge", largeScript.key);
        Files.write(new File(external, "test-keys.json").toPath(), results.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void assertWrongKey(String key, ApkProtector.Result file) throws Exception {
        byte[] id = java.util.Base64.getUrlDecoder().decode(file.id);
        try {
            ActivationKeys.open(key, id, file.packageName, IssuerKeyStore.loadOrCreate().getPublic());
            throw new AssertionError("A key unlocked another generated file");
        } catch (java.security.GeneralSecurityException expected) {}
    }

    private void streamChecks() throws Exception {
        byte[] plain = ActivationKeys.random(2 * 1024 * 1024 + 31);
        byte[] key = ActivationKeys.random(32);
        byte[] aad = ActivationKeys.random(24);
        java.io.ByteArrayOutputStream encrypted = new java.io.ByteArrayOutputStream();
        ActivationKeys.encryptStream(new java.io.ByteArrayInputStream(plain), encrypted, key, aad, done -> {});
        byte[] bytes = encrypted.toByteArray();
        java.io.ByteArrayOutputStream restored = new java.io.ByteArrayOutputStream();
        ActivationKeys.decryptStream(new java.io.ByteArrayInputStream(bytes), restored, key, aad);
        require(Arrays.equals(plain, restored.toByteArray()), "Stream recovery failed");
        byte[] modified = bytes.clone();
        modified[100] ^= 1;
        expectInvalidStream(modified, key, aad);
        expectInvalidStream(Arrays.copyOf(bytes, bytes.length - 10), key, aad);
        expectInvalidStream(Arrays.copyOf(bytes, bytes.length + 1), key, aad);
        expectInvalidStream(bytes, ActivationKeys.random(32), aad);
    }
    private void expectInvalidStream(byte[] bytes, byte[] key, byte[] aad) throws Exception {
        try {
            ActivationKeys.decryptStream(new java.io.ByteArrayInputStream(bytes), new java.io.ByteArrayOutputStream(), key, aad);
        } catch (java.security.GeneralSecurityException | java.io.IOException expected) { return; }
        throw new AssertionError("Corrupted stream accepted");
    }

    private void activate(String mode) throws Exception {
        String packageName = arguments.getString("package", "com.function.fixture");
        android.content.Intent launch = new android.content.Intent().setComponent(new android.content.ComponentName(
                packageName, "com.function.protect.GateActivity"))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        getTargetContext().startActivity(launch);
        if (mode.equals("restored")) {
            if (packageName.equals("com.function.fixture")) awaitText("Application=OK", 15000);
            else if (packageName.startsWith("com.function.scenarios.")) awaitScenario(packageName);
            else awaitOriginal(packageName);
        } else {
            AccessibilityNodeInfo input = awaitClass("android.widget.EditText", 15000);
            Bundle text = new Bundle();
            text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, arguments.getString("key"));
            require(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text), "Cannot enter key");
            AccessibilityNodeInfo button = awaitText("解锁并启动", 3000);
            require(button.performAction(AccessibilityNodeInfo.ACTION_CLICK), "Cannot click unlock");
            if (mode.equals("wrong")) awaitText("解锁失败", 10000);
            else if (packageName.equals("com.function.fixture")) awaitText("Application=OK", 20000);
            else if (packageName.startsWith("com.function.scenarios.")) {
                awaitScenario(packageName);
            }
            else awaitOriginal(packageName);
        }
        if (!mode.equals("wrong") && packageName.equals("com.function.fixture")) {
            awaitText("Provider=OK", 3000);
            awaitText("Factory=OK", 3000);
            awaitText("资源读取正常", 3000);
            awaitText("Isolated=OK", 15000);
        }
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new File(
                getTargetContext().getExternalFilesDir(null), mode + ".png"))) {
            getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        }
    }

    private void awaitScenario(String packageName) throws Exception {
        awaitText("BASIC_OK", 20000);
        if (packageName.endsWith("mixed")) awaitText("STATEFUL_OK", 15000);
        if (packageName.endsWith("preferences")) awaitText("MainPreferences=true", 3000);
    }

    private void awaitOriginal(String packageName) throws Exception {
        long deadline = android.os.SystemClock.uptimeMillis() + 25000;
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root != null && packageName.contentEquals(root.getPackageName()) && root.getChildCount() > 0
                    && find(root, "解锁并启动", false) == null && find(root, "正在启动软件", false) == null) {
                android.os.SystemClock.sleep(1500);
                return;
            }
            android.os.SystemClock.sleep(100);
        }
        throw new AssertionError("Original target UI did not open");
    }

    private AccessibilityNodeInfo awaitClass(String name, int millis) throws Exception {
        return await(name, true, millis);
    }
    private AccessibilityNodeInfo awaitText(String value, int millis) throws Exception {
        return await(value, false, millis);
    }
    private AccessibilityNodeInfo await(String value, boolean className, int millis) throws Exception {
        long deadline = android.os.SystemClock.uptimeMillis() + millis;
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            AccessibilityNodeInfo found = find(getUiAutomation().getRootInActiveWindow(), value, className);
            if (found != null) return found;
            android.os.SystemClock.sleep(100);
        }
        throw new AssertionError("UI timed out waiting for " + value);
    }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String value, boolean className) {
        if (node == null) return null;
        CharSequence text = className ? node.getClassName() : node.getText();
        if (text != null && (className ? text.toString().equals(value) : text.toString().contains(value))) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = find(node.getChild(i), value, className);
            if (found != null) return found;
        }
        return null;
    }
    private static void require(boolean okay, String message) { if (!okay) throw new AssertionError(message); }
}
