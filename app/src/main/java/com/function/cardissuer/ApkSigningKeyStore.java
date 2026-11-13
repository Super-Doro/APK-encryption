package com.function.cardissuer;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import com.android.apksig.ApkSigner;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;
import javax.security.auth.x500.X500Principal;

final class ApkSigningKeyStore {
    private static final String ALIAS = "function-apk-signing-v2";

    private ApkSigningKeyStore() {}

    static synchronized ApkSigner.SignerConfig signer() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(new X500Principal("CN=Function APK Signing"))
                    .setCertificateSerialNumber(BigInteger.ONE).build());
            generator.generateKeyPair();
        }
        return new ApkSigner.SignerConfig.Builder("function",
                (PrivateKey) store.getKey(ALIAS, null),
                Collections.singletonList((X509Certificate) store.getCertificate(ALIAS))).build();
    }
}
