package com.function.cardissuer;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;

public final class IssuerKeyStore {
    private static final String ALIAS = "function-card-issuer-rsa-v1";

    private IssuerKeyStore() {}

    public static synchronized KeyPair loadOrCreate() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .build());
            return generator.generateKeyPair();
        }
        return new KeyPair(store.getCertificate(ALIAS).getPublicKey(),
                (java.security.PrivateKey) store.getKey(ALIAS, null));
    }
}
