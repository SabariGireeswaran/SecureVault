package com.sabari.photovault.crypto;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Manages the master key and how it is locked.
 *
 * KEY CHAIN (how your photos are protected):
 *
 *   photos  <--encrypted by--  MASTER KEY (random 256-bit, never stored in plain form)
 *
 *   MASTER KEY is stored twice, each copy locked differently:
 *     copy A: AES-GCM( key = PBKDF2(PIN) ), then wrapped again by the hardware DEVICE key
 *     copy B: AES-GCM( key = hardware BIOMETRIC key )   [only if fingerprint is turned on]
 *
 * The PIN itself is NEVER stored anywhere. A wrong PIN simply fails to decrypt copy A.
 */
public final class VaultKeys {

    public static final int MIN_PIN = 6;
    public static final int MAX_PIN = 16;
    private static final int FREE_ATTEMPTS = 5;
    private static final long MAX_LOCKOUT_MS = 60L * 60 * 1000; // 1 hour

    private static final String PREFS = "vault_keys";
    private static final String K_SALT = "salt";
    private static final String K_PIN_WRAP = "pin_wrap";
    private static final String K_BIO_WRAP = "bio_wrap";
    private static final String K_FAILS = "fails";
    private static final String K_LOCK_UNTIL = "lock_until";
    private static final String AAD_MASTER = "photovault-master-v1";

    public static class WrongPinException extends Exception {
        public WrongPinException() { super("Wrong PIN"); }
    }

    private final SharedPreferences prefs;

    public VaultKeys(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isSetUp() {
        return prefs.contains(K_PIN_WRAP);
    }

    // ---------------- PIN ----------------

    /** First-time setup: makes a new master key and locks it with the PIN. */
    public SecretKey setup(char[] pin) throws GeneralSecurityException {
        SecretKey master = CryptoManager.newMasterKey();
        savePinWrap(master, pin);
        return master;
    }

    public SecretKey unlockWithPin(char[] pin) throws WrongPinException, GeneralSecurityException {
        byte[] salt = b64(prefs.getString(K_SALT, null));
        byte[] outer = b64(prefs.getString(K_PIN_WRAP, null));
        byte[] inner = KeyStoreHelper.deviceDecrypt(outer);
        SecretKey pinKey = CryptoManager.deriveKeyFromPin(pin, salt);
        try {
            byte[] raw = CryptoManager.decrypt(pinKey, inner, AAD_MASTER);
            SecretKey master = new SecretKeySpec(raw, "AES");
            Arrays.fill(raw, (byte) 0);
            return master;
        } catch (AEADBadTagException e) {
            throw new WrongPinException();
        }
    }

    public void changePin(SecretKey master, char[] newPin) throws GeneralSecurityException {
        savePinWrap(master, newPin);
    }

    private void savePinWrap(SecretKey master, char[] pin) throws GeneralSecurityException {
        byte[] salt = CryptoManager.randomBytes(16);
        SecretKey pinKey = CryptoManager.deriveKeyFromPin(pin, salt);
        byte[] raw = master.getEncoded();
        byte[] inner = CryptoManager.encrypt(pinKey, raw, AAD_MASTER);
        Arrays.fill(raw, (byte) 0);
        byte[] outer = KeyStoreHelper.deviceEncrypt(inner);
        prefs.edit()
                .putString(K_SALT, b64(salt))
                .putString(K_PIN_WRAP, b64(outer))
                .commit();
    }

    // ---------------- Fingerprint ----------------

    public boolean isBioEnabled() {
        return prefs.contains(K_BIO_WRAP);
    }

    /** Call with a cipher that the fingerprint prompt has just approved. */
    public void enableBio(Cipher approvedEncryptCipher, SecretKey master) throws GeneralSecurityException {
        byte[] raw = master.getEncoded();
        byte[] ct = approvedEncryptCipher.doFinal(raw);
        Arrays.fill(raw, (byte) 0);
        byte[] blob = CryptoManager.concat(approvedEncryptCipher.getIV(), ct);
        prefs.edit().putString(K_BIO_WRAP, b64(blob)).commit();
    }

    public byte[] bioIv() {
        return KeyStoreHelper.ivOf(b64(prefs.getString(K_BIO_WRAP, null)));
    }

    public SecretKey unlockWithBio(Cipher approvedDecryptCipher) throws GeneralSecurityException {
        byte[] body = KeyStoreHelper.bodyOf(b64(prefs.getString(K_BIO_WRAP, null)));
        byte[] raw = approvedDecryptCipher.doFinal(body);
        SecretKey master = new SecretKeySpec(raw, "AES");
        Arrays.fill(raw, (byte) 0);
        return master;
    }

    public void disableBio() {
        prefs.edit().remove(K_BIO_WRAP).commit();
        KeyStoreHelper.deleteBioKey();
    }

    // ---------------- Brute-force protection ----------------

    /** How many ms the user must wait before the next try (0 = can try now). */
    public long lockoutRemainingMs() {
        long until = prefs.getLong(K_LOCK_UNTIL, 0);
        long left = until - System.currentTimeMillis();
        // If someone moves the phone clock backwards, the wait can't grow beyond the max.
        return Math.max(0, Math.min(left, MAX_LOCKOUT_MS));
    }

    public int failedAttempts() {
        return prefs.getInt(K_FAILS, 0);
    }

    /** After 5 wrong PINs: wait 30s, then 1m, 2m, 4m ... up to 1 hour per try. */
    public void recordFailure() {
        int fails = prefs.getInt(K_FAILS, 0) + 1;
        long lockMs = 0;
        if (fails >= FREE_ATTEMPTS) {
            int step = Math.min(fails - FREE_ATTEMPTS, 7);
            lockMs = Math.min(30_000L << step, MAX_LOCKOUT_MS);
        }
        prefs.edit()
                .putInt(K_FAILS, fails)
                .putLong(K_LOCK_UNTIL, System.currentTimeMillis() + lockMs)
                .commit();
    }

    public void resetFailures() {
        prefs.edit().putInt(K_FAILS, 0).putLong(K_LOCK_UNTIL, 0).commit();
    }

    // ---------------- helpers ----------------

    private static String b64(byte[] b) { return Base64.encodeToString(b, Base64.NO_WRAP); }
    private static byte[] b64(String s) { return Base64.decode(s, Base64.NO_WRAP); }
}
