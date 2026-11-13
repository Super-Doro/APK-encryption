package com.function.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class ActivationKeys {
    public static final String PREFIX = "FA2.";
    private static final byte[] DOMAIN = "Function/APK-activation/v2\0".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int CHUNK_SIZE = 1024 * 1024;
    private static final int STREAM_MAGIC = 0x46435331;

    private ActivationKeys() {}

    public static byte[] random(int count) {
        byte[] bytes = new byte[count];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    public static String issue(byte[] id, byte[] key, String packageName, PrivateKey issuer)
            throws GeneralSecurityException {
        if (id.length != 16 || key.length != 32) throw new GeneralSecurityException("密钥参数长度无效");
        byte[] payload = ByteBuffer.allocate(80).put(id).put(key).put(packageHash(packageName)).array();
        Signature signature = signature();
        signature.initSign(issuer);
        signature.update(DOMAIN);
        signature.update(payload);
        byte[] signed = signature.sign();
        if (signed.length != 256) throw new GeneralSecurityException("签发密钥必须为 RSA-2048");
        return PREFIX + encode(ByteBuffer.allocate(336).put(payload).put(signed).array());
    }

    public static byte[] open(String input, byte[] expectedId, String packageName, PublicKey issuer)
            throws GeneralSecurityException {
        if (input == null || input.length() > 8192) throw new GeneralSecurityException("密钥格式错误");
        String token = input.replaceAll("\\s", "");
        if (!token.startsWith(PREFIX)) throw new GeneralSecurityException("请输入 FA2 开头的专属密钥");
        byte[] bytes;
        try { bytes = Base64.getUrlDecoder().decode(token.substring(4)); }
        catch (IllegalArgumentException error) { throw new GeneralSecurityException("密钥编码错误", error); }
        if (bytes.length != 336 || !encode(bytes).equals(token.substring(4))) {
            throw new GeneralSecurityException("密钥长度或编码错误");
        }
        if (!MessageDigest.isEqual(expectedId, Arrays.copyOf(bytes, 16))
                || !MessageDigest.isEqual(packageHash(packageName), Arrays.copyOfRange(bytes, 48, 80))) {
            throw new GeneralSecurityException("此密钥不属于当前加密包");
        }
        Signature signature = signature();
        signature.initVerify(issuer);
        signature.update(DOMAIN);
        signature.update(bytes, 0, 80);
        if (!signature.verify(Arrays.copyOfRange(bytes, 80, bytes.length))) {
            throw new GeneralSecurityException("密钥签名无效");
        }
        return Arrays.copyOfRange(bytes, 16, 48);
    }

    public static byte[] encrypt(byte[] plain, byte[] key, byte[] aad) throws GeneralSecurityException {
        byte[] iv = random(12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(aad);
        byte[] encrypted = cipher.doFinal(plain);
        return ByteBuffer.allocate(12 + encrypted.length).put(iv).put(encrypted).array();
    }

    public static byte[] decrypt(byte[] encrypted, byte[] key, byte[] aad) throws GeneralSecurityException {
        if (encrypted.length < 28) throw new GeneralSecurityException("加密数据已损坏");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, Arrays.copyOf(encrypted, 12)));
        cipher.updateAAD(aad);
        return cipher.doFinal(encrypted, 12, encrypted.length - 12);
    }

    /** Independent authenticated chunks keep memory bounded and authenticate the end of the stream. */
    public static void encryptStream(java.io.InputStream input, java.io.OutputStream output, byte[] key,
            byte[] aad, java.util.function.LongConsumer progress) throws Exception {
        java.io.DataOutputStream data = new java.io.DataOutputStream(output);
        data.writeInt(STREAM_MAGIC);
        byte[] buffer = new byte[CHUNK_SIZE];
        long index = 0;
        long total = 0;
        try {
            int count;
            while ((count = readChunk(input, buffer)) > 0) {
                byte[] plain = count == buffer.length ? buffer : Arrays.copyOf(buffer, count);
                byte[] encrypted = encrypt(plain, key, chunkAad(aad, index++, false));
                data.writeInt(encrypted.length);
                data.write(encrypted);
                total += count;
                progress.accept(total);
                if (plain != buffer) Arrays.fill(plain, (byte) 0);
            }
            byte[] terminal = encrypt(new byte[0], key, chunkAad(aad, index, true));
            data.writeInt(terminal.length);
            data.write(terminal);
        } finally { Arrays.fill(buffer, (byte) 0); }
    }

    public static void decryptStream(java.io.InputStream input, java.io.OutputStream output, byte[] key,
            byte[] aad) throws Exception {
        java.io.DataInputStream data = new java.io.DataInputStream(input);
        if (data.readInt() != STREAM_MAGIC) throw new GeneralSecurityException("加密流格式无效");
        long index = 0;
        while (true) {
            int length;
            try { length = data.readInt(); }
            catch (java.io.EOFException error) { throw new GeneralSecurityException("加密数据缺少结束校验", error); }
            if (length < 28 || length > CHUNK_SIZE + 28) throw new GeneralSecurityException("加密分块损坏");
            byte[] encrypted = new byte[length];
            data.readFully(encrypted);
            boolean terminal = length == 28;
            byte[] plain = decrypt(encrypted, key, chunkAad(aad, index++, terminal));
            if (terminal) {
                if (data.read() != -1) throw new GeneralSecurityException("加密数据包含多余内容");
                return;
            }
            output.write(plain);
            Arrays.fill(plain, (byte) 0);
        }
    }

    private static int readChunk(java.io.InputStream input, byte[] buffer) throws java.io.IOException {
        int total = 0;
        while (total < buffer.length) {
            int count = input.read(buffer, total, buffer.length - total);
            if (count == -1) break;
            if (count == 0) continue;
            total += count;
        }
        return total;
    }

    private static byte[] chunkAad(byte[] aad, long index, boolean terminal) {
        return ByteBuffer.allocate(4 + aad.length + 8 + 1).putInt(STREAM_MAGIC).put(aad)
                .putLong(index).put((byte) (terminal ? 1 : 0)).array();
    }

    public static byte[] dexAad(byte[] id, String dexName) {
        byte[] name = dexName.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(id.length + name.length).put(id).put(name).array();
    }

    public static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] packageHash(String name) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
    }

    private static Signature signature() throws GeneralSecurityException {
        try { return Signature.getInstance("SHA256withRSA/PSS"); }
        catch (java.security.NoSuchAlgorithmException error) {
            Signature result = Signature.getInstance("RSASSA-PSS");
            result.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
            return result;
        }
    }
}
