package com.sabari.photovault.ui;

import android.app.Activity;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.CancellationSignal;

import javax.crypto.Cipher;

/** Small wrapper around Android's built-in fingerprint dialog. */
public final class Fingerprint {

    public interface Callback {
        void onSuccess(Cipher approvedCipher);
        void onFailed(String message);   // user cancelled, too many tries, etc.
    }

    private Fingerprint() {}

    public static boolean isAvailable(Activity a) {
        BiometricManager bm = a.getSystemService(BiometricManager.class);
        return bm != null
                && bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS;
    }

    /**
     * Shows the fingerprint dialog. The cipher is "unlocked" by the security chip ONLY if the
     * fingerprint matches; a fake success is impossible because the chip itself checks it.
     */
    public static void prompt(Activity a, String title, Cipher cipher, Callback cb) {
        BiometricPrompt prompt = new BiometricPrompt.Builder(a)
                .setTitle(title)
                .setSubtitle("Touch the fingerprint sensor")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButton("Use PIN", a.getMainExecutor(), (d, w) -> cb.onFailed(null))
                .setConfirmationRequired(false)
                .build();

        prompt.authenticate(new BiometricPrompt.CryptoObject(cipher), new CancellationSignal(),
                a.getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                        BiometricPrompt.CryptoObject co = r.getCryptoObject();
                        if (co != null && co.getCipher() != null) cb.onSuccess(co.getCipher());
                        else cb.onFailed("Fingerprint error");
                    }

                    @Override
                    public void onAuthenticationError(int code, CharSequence msg) {
                        boolean userCancelled = code == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                                || code == BiometricPrompt.BIOMETRIC_ERROR_CANCELED;
                        cb.onFailed(userCancelled ? null : String.valueOf(msg));
                    }
                });
    }
}
