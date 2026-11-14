package com.function.script;

import android.os.Process;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.system.Os;
import android.util.AtomicFile;
import com.function.protocol.ActivationKeys;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.security.KeyStore;
import java.security.Security;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Device-local activation state, scoped to the current Android UID and encrypted by Keystore. */
final class ScriptActivation implements AutoCloseable {
    private final String alias;
    private final byte[] aad;
    private final AtomicFile record;
    private final FileChannel channel;
    private final FileLock lock;

    ScriptActivation(byte[] id, String identity) throws Exception {
        String buildId = ActivationKeys.encode(id);
        alias = "doro-sh-activation-v1-" + buildId;
        aad = ActivationKeys.dexAad(id, "Doro/SH-activation/v1/" + identity);
        File directory = activationDirectory();
        record = new AtomicFile(new File(directory, buildId + ".activation"));
        channel = FileChannel.open(new File(directory, buildId + ".lock").toPath(),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try { lock = channel.lock(); }
        catch (Exception error) { channel.close(); throw error; }
    }

    String restore() throws Exception {
        byte[] sealed;
        try (FileInputStream input = record.openRead(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 16384) throw new IOException("本机激活记录过大");
                output.write(buffer, 0, count);
            }
            sealed = output.toByteArray();
        } catch (java.io.FileNotFoundException error) {
            if (record.getBaseFile().exists()) throw error;
            return null;
        }
        if (sealed.length < 28) throw new IOException("本机激活记录损坏");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, storageKey(false),
                new GCMParameterSpec(128, Arrays.copyOf(sealed, 12)));
        cipher.updateAAD(aad);
        byte[] plain = cipher.doFinal(sealed, 12, sealed.length - 12);
        try { return new String(plain, StandardCharsets.UTF_8); }
        finally { Arrays.fill(plain, (byte) 0); }
    }

    void save(String token, ActivationKeys.Policy keyPolicy) throws Exception {
        ActivationKeys.ensureValidTime(token, keyPolicy);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, storageKey(true));
        cipher.updateAAD(aad);
        byte[] plain = token.replaceAll("\\s", "").getBytes(StandardCharsets.UTF_8);
        byte[] encrypted;
        try { encrypted = cipher.doFinal(plain); }
        finally { Arrays.fill(plain, (byte) 0); }
        FileOutputStream output = null;
        try {
            output = record.startWrite();
            Os.fchmod(output.getFD(), 0600);
            output.write(cipher.getIV());
            output.write(encrypted);
            record.finishWrite(output);
            output = null;
            if (!record.getBaseFile().isFile()) throw new IOException("本机激活记录提交失败");
        } catch (Exception error) {
            if (output != null) record.failWrite(output);
            throw error;
        }
    }

    private SecretKey storageKey(boolean create) throws Exception {
        if (Security.getProvider("AndroidKeyStore") == null) {
            String provider = android.os.Build.VERSION.SDK_INT >= 31
                    ? "android.security.keystore2.AndroidKeyStoreProvider"
                    : "android.security.keystore.AndroidKeyStoreProvider";
            Class.forName(provider).getMethod("install").invoke(null);
        }
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(alias)) return (SecretKey) store.getKey(alias, null);
        if (!create) throw new IOException("本机激活密钥已丢失");
        KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return generator.generateKey();
    }

    private static File activationDirectory() throws Exception {
        Exception failure = null;
        String[] bases = {System.getenv("HOME"), "/data/local/tmp", System.getenv("TMPDIR")};
        for (String base : bases) {
            if (base == null || base.isEmpty()) continue;
            try {
                File directory = new File(base, ".doro-activation-" + Process.myUid());
                if (Files.isSymbolicLink(directory.toPath())) throw new IOException("激活目录不可使用符号链接");
                if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
                    throw new IOException("无法创建激活目录 " + directory);
                }
                if (Os.stat(directory.getPath()).st_uid != Process.myUid()) throw new IOException("激活目录所有者不匹配");
                Os.chmod(directory.getPath(), 0700);
                if ((Os.stat(directory.getPath()).st_mode & 077) != 0) throw new IOException("激活目录权限不受支持");
                return directory;
            } catch (Exception error) { failure = error; }
        }
        throw new IOException("无法创建本机激活目录，请使用具有可写私有目录的运行环境", failure);
    }

    @Override public void close() throws IOException {
        try { lock.release(); }
        finally { channel.close(); }
    }
}
