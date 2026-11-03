package com.sabari.photovault.crypto;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.StrongBoxUnavailableException;

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Android Keystore = a locked box inside the phone's security chip (TEE / StrongBox).
 * Keys created here can be USED by our app but can NEVER be copied out, not even by us.
 *
 * We use two hardware keys:
 *  1. DEVICE key  - wraps the PIN-protected data. So even if someone copies the vault files
 *                   to a computer, they cannot even start guessing PINs there. They need THIS phone.
 *  2. BIOMETRIC key - can only be used right after a successful fingerprint scan.
 *                   Adding a new fingerprint to the phone destroys this key automatically.
 */
public final class KeyStoreHelper {

    private static final String PROVIDER = "AndroidKeyStore";
    private static final String DEVICE_ALIAS = "pv_device_key";
    private static final String BIO_ALIAS = "pv_bio_key";
    private static final String AES_GCM = "AES/GCM/NoPadding";

    private KeyStoreHelper() {}

    private static KeyStore keyStore() throws GeneralSecurityException {
        try {
            KeyStore ks = KeyStore.getInstance(PROVIDER);
            ks.load(null);
            return ks;
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException(e);
        }
    }

    // ---------------- device-binding key ----------------

    private static SecretKey deviceKey() throws GeneralSecurityException {
        KeyStore ks = keyStore();
        if (ks.containsAlias(DEVICE_ALIAS)) return (SecretKey) ks.getKey(DEVICE_ALIAS, null);
        return generate(DEVICE_ALIAS, false);
    }

    public static byte[] deviceEncrypt(byte[] plain) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance(AES_GCM);
        c.init(Cipher.ENCRYPT_MODE, deviceKey());   // Keystore picks a random IV itself
        return CryptoManager.concat(c.getIV(), c.doFinal(plain));
    }

    public static byte[] deviceDecrypt(byte[] blob) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance(AES_GCM);
        c.init(Cipher.DECRYPT_MODE, deviceKey(),
                new GCMParameterSpec(CryptoManager.TAG_BITS, blob, 0, CryptoManager.IV_LEN));
        return c.doFinal(blob, CryptoManager.IV_LEN, blob.length - CryptoManager.IV_LEN);
    }

    // ---------------- biometric key ----------------

    public static void createBioKey() throws GeneralSecurityException {
        deleteBioKey();
        generate(BIO_ALIAS, true);
    }

    public static void deleteBioKey() {
        try {
            KeyStore ks = keyStore();
            if (ks.containsAlias(BIO_ALIAS)) ks.deleteEntry(BIO_ALIAS);
        } catch (GeneralSecurityException ignored) { }
    }

    /** Cipher to give to the fingerprint prompt. Throws KeyPermanentlyInvalidatedException if fingerprints changed. */
    public static Cipher bioEncryptCipher() throws GeneralSecurityException {
        Cipher c = Cipher.getInstance(AES_GCM);
        c.init(Cipher.ENCRYPT_MODE, (SecretKey) keyStore().getKey(BIO_ALIAS, null));
        return c;
    }

    public static Cipher bioDecryptCipher(byte[] iv) throws GeneralSecurityException {
        SecretKey k = (SecretKey) keyStore().getKey(BIO_ALIAS, null);
        if (k == null) throw new GeneralSecurityException("Fingerprint key missing");
        Cipher c = Cipher.getInstance(AES_GCM);
        c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(CryptoManager.TAG_BITS, iv));
        return c;
    }

    public static byte[] ivOf(byte[] blob) {
        return Arrays.copyOfRange(blob, 0, CryptoManager.IV_LEN);
    }

    public static byte[] bodyOf(byte[] blob) {
        return Arrays.copyOfRange(blob, CryptoManager.IV_LEN, blob.length);
    }

    // ---------------- key generation ----------------

    private static SecretKey generate(String alias, boolean needsFingerprint) throws GeneralSecurityException {
        try {
            return generate(alias, needsFingerprint, true);   // try the extra-secure StrongBox chip first
        } catch (StrongBoxUnavailableException e) {
            return generate(alias, needsFingerprint, false);  // phone has no StrongBox -> normal TEE
        } catch (GeneralSecurityException | RuntimeException e) {
            if (e.getCause() instanceof StrongBoxUnavailableException || e instanceof java.security.ProviderException) {
                return generate(alias, needsFingerprint, false);
            }
            throw e;
        }
    }

    private static SecretKey generate(String alias, boolean needsFingerprint, boolean strongBox)
            throws GeneralSecurityException {
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true);
        if (strongBox) b.setIsStrongBoxBacked(true);
        if (needsFingerprint) {
            b.setUserAuthenticationRequired(true)
             // 0 seconds = a fresh fingerprint is needed for EVERY single use
             .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
             .setInvalidatedByBiometricEnrollment(true);
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
        kg.init(b.build());
        return kg.generateKey();
    }
}
