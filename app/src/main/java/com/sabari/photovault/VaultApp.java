package com.sabari.photovault;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.sabari.photovault.crypto.Session;
import com.sabari.photovault.data.AppSettings;

/**
 * AUTO-LOCK.
 * Watches all our screens. When the app leaves the screen (Home button, switching apps),
 * the master key is wiped from memory — immediately, or after the delay chosen in Settings.
 * Turning the screen OFF always locks immediately.
 * Exception: while the user is inside Android's own file/photo picker.
 */
public class VaultApp extends Application {

    private int visibleScreens = 0;
    private static volatile boolean pickerOpen = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable lockNow = Session::lock;

    /** Call just before opening the system photo/file picker. */
    public static void allowPicker() { pickerOpen = true; }

    @Override
    public void onCreate() {
        super.onCreate();
        AppSettings settings = new AppSettings(this);

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity a) {
                visibleScreens++;
                pickerOpen = false;               // we're back in our own app
                handler.removeCallbacks(lockNow); // came back before the timer ran out
            }
            @Override public void onActivityStopped(Activity a) {
                visibleScreens--;
                if (visibleScreens <= 0 && !pickerOpen && !a.isChangingConfigurations()) {
                    int delay = settings.autoLockSeconds();
                    if (delay <= 0) Session.lock();
                    else handler.postDelayed(lockNow, delay * 1000L);
                }
            }
            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityResumed(Activity a) { }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });

        // Screen turned off -> lock immediately, always.
        registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                pickerOpen = false;
                handler.removeCallbacks(lockNow);
                Session.lock();
            }
        }, new IntentFilter(Intent.ACTION_SCREEN_OFF));
    }
}
