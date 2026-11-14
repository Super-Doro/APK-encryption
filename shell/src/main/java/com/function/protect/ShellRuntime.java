package com.function.protect;

import android.content.pm.ApplicationInfo;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import com.function.protocol.ActivationKeys;
import dalvik.system.InMemoryDexClassLoader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

final class ShellRuntime {
    static final String CONFIG = "assets/function-protect/config.json";
    static JSONObject config;
    static ApplicationInfo info;
    static ClassLoader unlockedLoader;
    static String loadError;
    static String activeToken;
    static android.app.Application application;
    static String applicationClassName;

    private ShellRuntime() {}

    static ClassLoader prepare(ClassLoader parent, ApplicationInfo applicationInfo) {
        info = applicationInfo;
        try (ZipFile zip = new ZipFile(info.sourceDir)) {
            config = new JSONObject(new String(read(zip.getInputStream(zip.getEntry(CONFIG)), 1024 * 1024),
                    StandardCharsets.UTF_8));
            if (android.os.Process.isIsolated()) return parent;
            File stored = activationFile();
            if (stored.exists()) {
                byte[] sealed;
                try (InputStream input = new FileInputStream(stored)) { sealed = read(input, 16384); }
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                if (sealed.length < 28) throw new java.io.IOException("激活记录损坏，请重新输入密钥");
                cipher.init(Cipher.DECRYPT_MODE, storageKey(),
                        new GCMParameterSpec(128, java.util.Arrays.copyOf(sealed, 12)));
                cipher.updateAAD(id());
                String token = new String(cipher.doFinal(sealed, 12, sealed.length - 12), StandardCharsets.UTF_8);
                unlockedLoader = load(token, parent, true);
                activeToken = token;
                UsageGuard.start(keyPolicy());
                return unlockedLoader;
            }
        } catch (Exception error) {
            loadError = error.getMessage();
            android.util.Log.e("FunctionProtect", "Activation could not be restored", error);
        }
        return parent;
    }

    static ClassLoader load(String token, ClassLoader parent) throws Exception {
        return load(token, parent, false);
    }

    private static ClassLoader load(String token, ClassLoader parent, boolean storedActivation) throws Exception {
        PublicKey issuer = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(
                Base64.getDecoder().decode(config.getString("publicKey"))));
        ActivationKeys.Policy keyPolicy = keyPolicy();
        byte[] key = storedActivation
                ? ActivationKeys.openStoredActivation(token, id(), info.packageName, issuer, keyPolicy)
                : ActivationKeys.open(token, id(), info.packageName, issuer, keyPolicy);
        JSONArray names = config.getJSONArray("dex");
        ByteBuffer[] dex = new ByteBuffer[names.length()];
        if (dex.length == 0) throw new java.io.IOException("DEX 数量无效");
        try (ZipFile zip = new ZipFile(info.sourceDir)) {
            for (int i = 0; i < names.length(); i++) {
                String name = names.getString(i);
                ZipEntry entry = zip.getEntry("assets/function-protect/" + name + ".enc");
                if (entry == null) throw new java.io.IOException("缺少加密代码");
                ByteBuffer code;
                if (config.optString("dexFormat").equals("chunked-v1")) {
                    long size = config.getJSONObject("dexSizes").getLong(name);
                    if (size < 8 || size > Integer.MAX_VALUE - 8) {
                        throw new java.io.IOException("DEX 超出当前 Android 运行时的可寻址范围");
                    }
                    code = ByteBuffer.allocateDirect((int) size);
                    try (InputStream input = zip.getInputStream(entry)) {
                        ActivationKeys.decryptStream(input, new java.io.OutputStream() {
                            @Override public void write(int value) throws java.io.IOException {
                                if (!code.hasRemaining()) throw new java.io.IOException("DEX 长度信息错误");
                                code.put((byte) value);
                            }
                            @Override public void write(byte[] bytes, int offset, int length) throws java.io.IOException {
                                if (length > code.remaining()) throw new java.io.IOException("DEX 长度信息错误");
                                code.put(bytes, offset, length);
                            }
                        }, key, ActivationKeys.dexAad(id(), name));
                    }
                    if (code.hasRemaining()) throw new java.io.IOException("DEX 长度不完整");
                    code.flip();
                } else {
                    byte[] encrypted;
                    try (InputStream input = zip.getInputStream(entry)) { encrypted = read(input, Integer.MAX_VALUE - 8); }
                    code = ByteBuffer.wrap(ActivationKeys.decrypt(encrypted, key, ActivationKeys.dexAad(id(), name)));
                }
                if (code.remaining() < 8 || code.get(0) != 'd' || code.get(1) != 'e' || code.get(2) != 'x') {
                    throw new java.io.IOException("解密后的 DEX 格式无效");
                }
                dex[i] = code;
            }
        } finally { java.util.Arrays.fill(key, (byte) 0); }
        String libraries = info.nativeLibraryDir;
        if (!storedActivation) ActivationKeys.ensureValidTime(token, keyPolicy);
        ActivationKeys.ensureUsageTime(keyPolicy);
        return new InMemoryDexClassLoader(dex, libraries, parent);
    }

    static boolean isAuthorized() {
        if (unlockedLoader == null || activeToken == null) return false;
        try { ActivationKeys.ensureUsageTime(keyPolicy()); return true; }
        catch (java.security.GeneralSecurityException error) { loadError = error.getMessage(); return false; }
    }

    static void store(String token) throws Exception {
        ActivationKeys.ensureValidTime(token, keyPolicy());
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, storageKey());
        byte[] iv = cipher.getIV();
        cipher.updateAAD(id());
        byte[] encrypted = cipher.doFinal(token.replaceAll("\\s", "").getBytes(StandardCharsets.UTF_8));
        File destination = activationFile();
        File pending = new File(destination.getPath() + ".tmp");
        try (FileOutputStream stream = new FileOutputStream(pending)) {
            stream.write(iv);
            stream.write(encrypted);
            stream.getFD().sync();
        }
        if (!pending.renameTo(destination)) throw new java.io.IOException("无法保存激活记录");
    }

    private static File activationFile() throws Exception {
        File directory = new File(info.dataDir, "files");
        if (!directory.exists() && !directory.mkdirs()) throw new java.io.IOException("无法创建应用数据目录");
        return new File(directory, ".function-activation-" + config.getString("id"));
    }

    private static SecretKey storageKey() throws Exception {
        String alias = "function-activation-" + config.getString("id");
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(alias)) {
            KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
            return generator.generateKey();
        }
        return (SecretKey) store.getKey(alias, null);
    }

    static byte[] id() throws Exception { return Base64.getUrlDecoder().decode(config.getString("id")); }

    private static ActivationKeys.Policy keyPolicy() {
        return config.has("keySignature") ? new ActivationKeys.Policy(config.optLong("issuedAt"),
                config.optLong("expiresAt"), config.optLong("usageExpiresAt"),
                config.optString("keyHash"), config.optString("keySignature")) : null;
    }

    static void restoreIsolated(android.content.Intent intent, ClassLoader parent, String serviceName) throws Exception {
        if (unlockedLoader != null) { ActivationKeys.ensureUsageTime(keyPolicy()); return; }
        android.os.Bundle extras = intent == null ? null : intent.getExtras();
        android.os.IBinder broker = extras == null ? null : extras.getBinder(IsolatedBindings.BROKER);
        if (broker == null) throw new SecurityException("隔离服务尚未授权，请先打开软件完成解锁");
        android.os.Parcel request = android.os.Parcel.obtain();
        android.os.Parcel response = android.os.Parcel.obtain();
        try {
            request.writeInterfaceToken(IsolatedBindings.DESCRIPTOR);
            request.writeString(config.getString("id"));
            if (!broker.transact(android.os.IBinder.FIRST_CALL_TRANSACTION, request, response, 0)) {
                throw new SecurityException("隔离服务授权通道不可用");
            }
            response.readException();
            String token = response.readString();
            ClassLoader loaded = load(token, parent, true);
            IsolatedApplication bootstrap = IsolatedApplication.instance;
            if (bootstrap == null) throw new IllegalStateException("隔离进程 Application 未初始化");
            String className = applicationClassName == null ? "android.app.Application" : applicationClassName;
            String mode = "original";
            JSONArray plans = config.optJSONArray("isolationPlans");
            if (plans != null) {
                boolean found = false;
                for (int i = 0; i < plans.length(); i++) {
                    JSONObject plan = plans.getJSONObject(i);
                    if (plan.getString("service").equals(serviceName)) {
                        mode = plan.getString("applicationMode");
                        found = true;
                    }
                }
                if (!found) throw new SecurityException("隔离服务缺少初始化策略");
            }
            if (!mode.equals("original") && !mode.equals("minimal")) throw new SecurityException("隔离初始化策略无效");
            RuntimeBridge.prepare();
            android.content.Context context = new android.content.ContextWrapper(bootstrap.getBaseContext()) {
                @Override public ClassLoader getClassLoader() { return loaded; }
                @Override public android.content.Context getApplicationContext() {
                    return application == null ? bootstrap : application;
                }
            };
            try {
                application = mode.equals("minimal") ? new android.app.Application()
                        : createFactory(loaded).instantiateApplication(loaded, className);
                RuntimeBridge.attachApplication(application, context);
                application.onCreate();
                unlockedLoader = loaded;
                activeToken = token;
                UsageGuard.start(keyPolicy());
            } catch (Exception error) {
                application = null;
                unlockedLoader = null;
                activeToken = null;
                Throwable cause = error instanceof java.lang.reflect.InvocationTargetException
                        && error.getCause() != null ? error.getCause() : error;
                throw new IllegalStateException("隔离初始化失败（" + mode + "） " + cause, error);
            }
        } finally {
            request.recycle();
            response.recycle();
        }
    }

    static android.app.AppComponentFactory createFactory(ClassLoader loaded) throws Exception {
        String name = config.optString("factory");
        if (name.isEmpty()) return new android.app.AppComponentFactory();
        if (name.startsWith(".")) name = info.packageName + name;
        else if (!name.contains(".")) name = info.packageName + "." + name;
        return (android.app.AppComponentFactory) loaded.loadClass(name).getDeclaredConstructor().newInstance();
    }

    static byte[] read(InputStream input, int limit) throws java.io.IOException {
        if (input == null) throw new java.io.IOException("缺少保护配置");
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = source.read(buffer)) != -1) {
                if ((long) output.size() + count > limit) throw new java.io.IOException("数据超过支持的大小");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }
}
