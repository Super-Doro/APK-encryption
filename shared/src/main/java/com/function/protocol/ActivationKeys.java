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
    private static final byte[] TIMED_DOMAIN = "Doro/activation/v3\0".getBytes(StandardCharsets.UTF_8);
    private static final byte[] COMPACT_DOMAIN = "Doro/compact-activation/v1\0".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int CHUNK_SIZE = 1024 * 1024;
    private static final int STREAM_MAGIC = 0x46435331;

    private ActivationKeys() {}

    /** Signed metadata stored with the encrypted file. The user supplies only its random AES key. */
    public static final class Policy {
        public final long issuedAt;
        public final long expiresAt;
        public final String keyHash;
        public final String signature;

        public Policy(long issuedAt, long expiresAt, String keyHash, String signature) {
            this.issuedAt = issuedAt;
            this.expiresAt = expiresAt;
            this.keyHash = keyHash;
            this.signature = signature;
        }
    }

    public static Policy issueCompact(byte[] id, byte[] key, String packageName, PrivateKey issuer,
            long issuedAt, long expiresAt) throws GeneralSecurityException {
        if (id.length != 16 || key.length != 32 || issuedAt <= 0 || expiresAt <= issuedAt) {
            throw new GeneralSecurityException("短密钥参数无效");
        }
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(key);
        Signature signer = signature();
        signer.initSign(issuer);
        signer.update(COMPACT_DOMAIN);
        signer.update(compactPayload(id, packageName, issuedAt, expiresAt, hash));
        return new Policy(issuedAt, expiresAt, Base64.getEncoder().encodeToString(hash),
                Base64.getEncoder().encodeToString(signer.sign()));
    }

    public static String compactToken(byte[] key) throws GeneralSecurityException {
        if (key.length != 32) throw new GeneralSecurityException("短密钥长度无效");
        return "DK1." + encode(key);
    }

    public static byte[] open(String input, byte[] expectedId, String packageName, PublicKey issuer, Policy policy)
            throws GeneralSecurityException {
        return openWithPolicy(input, expectedId, packageName, issuer, policy, true);
    }

    public static byte[] openStoredActivation(String input, byte[] expectedId, String packageName,
            PublicKey issuer, Policy policy) throws GeneralSecurityException {
        return openWithPolicy(input, expectedId, packageName, issuer, policy, false);
    }

    private static byte[] openWithPolicy(String input, byte[] id, String packageName, PublicKey issuer,
            Policy policy, boolean checkActivationWindow) throws GeneralSecurityException {
        if (input == null || input.length() > 8192) throw new GeneralSecurityException("密钥格式错误");
        String token = input.replaceAll("\\s", "");
        if (!token.startsWith("DK1.")) return open(input, id, packageName, issuer, checkActivationWindow);
        if (policy == null || id.length != 16) throw new GeneralSecurityException("加密文件缺少短密钥授权信息");
        byte[] key = null;
        boolean accepted = false;
        try {
            key = Base64.getUrlDecoder().decode(token.substring(4));
            if (key.length != 32 || !encode(key).equals(token.substring(4))) {
                throw new GeneralSecurityException("短密钥长度或编码错误");
            }
            byte[] hash = Base64.getDecoder().decode(policy.keyHash);
            byte[] signed = Base64.getDecoder().decode(policy.signature);
            if (hash.length != 32 || !MessageDigest.isEqual(hash, MessageDigest.getInstance("SHA-256").digest(key))) {
                throw new GeneralSecurityException("此密钥不属于当前加密文件");
            }
            Signature verifier = signature();
            verifier.initVerify(issuer);
            verifier.update(COMPACT_DOMAIN);
            verifier.update(compactPayload(id, packageName, policy.issuedAt, policy.expiresAt, hash));
            if (!verifier.verify(signed)) throw new GeneralSecurityException("短密钥授权签名无效");
            if (checkActivationWindow) checkWindow(policy.issuedAt, policy.expiresAt);
            accepted = true;
            return key;
        } catch (IllegalArgumentException error) {
            throw new GeneralSecurityException("短密钥或授权信息编码错误", error);
        } finally {
            if (!accepted && key != null) Arrays.fill(key, (byte) 0);
        }
    }

    public static void ensureValidTime(String token, Policy policy) throws GeneralSecurityException {
        if (token != null && token.replaceAll("\\s", "").startsWith("DK1.")) {
            if (policy == null) throw new GeneralSecurityException("缺少短密钥有效期信息");
            checkWindow(policy.issuedAt, policy.expiresAt);
        } else ensureValidTime(token);
    }

    private static byte[] compactPayload(byte[] id, String packageName, long issuedAt, long expiresAt, byte[] hash)
            throws GeneralSecurityException {
        return ByteBuffer.allocate(96).put(id).put(hash).put(packageHash(packageName))
                .putLong(issuedAt).putLong(expiresAt).array();
    }

    public static byte[] random(int count) {
        byte[] bytes = new byte[count];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    public static String issue(byte[] id, byte[] key, String packageName, PrivateKey issuer)
            throws GeneralSecurityException {
        if (id.length != 16 || key.length != 32) throw new GeneralSecurityException("密钥参数长度无效");
        byte[] payload = ByteBuffer.allocate(80).put(id).put(key).put(packageHash(packageName)).array();
        return signToken(PREFIX, DOMAIN, payload, issuer);
    }

    public static String issue(byte[] id, byte[] key, String packageName, PrivateKey issuer,
            long issuedAt, long expiresAt) throws GeneralSecurityException {
        if (expiresAt == 0) return issue(id, key, packageName, issuer);
        if (id.length != 16 || key.length != 32 || issuedAt <= 0 || expiresAt <= issuedAt) {
            throw new GeneralSecurityException("有效期参数无效");
        }
        byte[] payload = ByteBuffer.allocate(96).put(id).put(key).put(packageHash(packageName))
                .putLong(issuedAt).putLong(expiresAt).array();
        return signToken("FA3.", TIMED_DOMAIN, payload, issuer);
    }

    private static String signToken(String prefix, byte[] domain, byte[] payload, PrivateKey issuer)
            throws GeneralSecurityException {
        Signature signature = signature();
        signature.initSign(issuer);
        signature.update(domain);
        signature.update(payload);
        byte[] signed = signature.sign();
        if (signed.length != 256) throw new GeneralSecurityException("签发密钥必须为 RSA-2048");
        return prefix + encode(ByteBuffer.allocate(payload.length + signed.length).put(payload).put(signed).array());
    }

    public static byte[] open(String input, byte[] expectedId, String packageName, PublicKey issuer)
            throws GeneralSecurityException {
        return open(input, expectedId, packageName, issuer, true);
    }

    /** For an activation recovered from authenticated device-local storage or its private Binder broker. */
    public static byte[] openStoredActivation(String input, byte[] expectedId, String packageName, PublicKey issuer)
            throws GeneralSecurityException {
        return open(input, expectedId, packageName, issuer, false);
    }

    private static byte[] open(String input, byte[] expectedId, String packageName, PublicKey issuer,
            boolean checkActivationWindow) throws GeneralSecurityException {
        byte[] bytes = tokenBytes(input);
        int payloadLength = bytes.length - 256;
        if (!MessageDigest.isEqual(expectedId, Arrays.copyOf(bytes, 16))
                || !MessageDigest.isEqual(packageHash(packageName), Arrays.copyOfRange(bytes, 48, 80))) {
            throw new GeneralSecurityException("此密钥不属于当前加密包");
        }
        Signature signature = signature();
        signature.initVerify(issuer);
        signature.update(payloadLength == 96 ? TIMED_DOMAIN : DOMAIN);
        signature.update(bytes, 0, payloadLength);
        if (!signature.verify(Arrays.copyOfRange(bytes, payloadLength, bytes.length))) {
            throw new GeneralSecurityException("密钥签名无效");
        }
        if (checkActivationWindow) checkWindow(bytes);
        return Arrays.copyOfRange(bytes, 16, 48);
    }

    /** Recheck a token whose signature was already verified by open(). */
    public static void ensureValidTime(String verifiedToken) throws GeneralSecurityException {
        checkWindow(tokenBytes(verifiedToken));
    }

    private static byte[] tokenBytes(String input) throws GeneralSecurityException {
        if (input == null || input.length() > 8192) throw new GeneralSecurityException("密钥格式错误");
        String token = input.replaceAll("\\s", "");
        boolean timed = token.startsWith("FA3.");
        if (!timed && !token.startsWith(PREFIX)) throw new GeneralSecurityException("请输入 FA2 或 FA3 开头的专属密钥");
        byte[] bytes;
        try { bytes = Base64.getUrlDecoder().decode(token.substring(4)); }
        catch (IllegalArgumentException error) { throw new GeneralSecurityException("密钥编码错误", error); }
        if (bytes.length != (timed ? 352 : 336) || !encode(bytes).equals(token.substring(4))) {
            throw new GeneralSecurityException("密钥长度或编码错误");
        }
        return bytes;
    }

    private static void checkWindow(byte[] bytes) throws GeneralSecurityException {
        if (bytes.length == 336) return;
        ByteBuffer window = ByteBuffer.wrap(bytes, 80, 16);
        long issuedAt = window.getLong();
        long expiresAt = window.getLong();
        checkWindow(issuedAt, expiresAt);
    }

    private static void checkWindow(long issuedAt, long expiresAt) throws GeneralSecurityException {
        if (issuedAt <= 0 || expiresAt <= issuedAt) throw new GeneralSecurityException("有效期无效");
        long now = System.currentTimeMillis();
        if (now < issuedAt) throw new GeneralSecurityException("设备时间早于文件生成时间，请校准系统时间");
        if (now >= expiresAt) throw new GeneralSecurityException("文件已过期，到期时间 "
                + java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(expiresAt)));
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
