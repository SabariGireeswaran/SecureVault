package com.sabari.photovault;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

import com.sabari.photovault.crypto.Session;

/**
 * AUTO-LOCK.
 * Watches all our screens. The moment the app leaves the screen (Home button,
 * switching apps, screen off), the master key is wiped from memory.
 * The only exception: while the user is inside Android's photo picker to add photos.
 */
public class VaultApp extends Application {

    private int visibleScreens = 0;
    private static volatile boolean pickerOpen = false;

    /** Call just before opening the system photo picker. */
    public static void allowPicker() { pickerOpen = true; }

    @Override
    public void onCreate() {
        super.onCreate();

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity a) {
                visibleScreens++;
                pickerOpen = false;   // we're back in our own app
            }
            @Override public void onActivityStopped(Activity a) {
                visibleScreens--;
                if (visibleScreens <= 0 && !pickerOpen && !a.isChangingConfigurations()) {
                    Session.lock();
                }
            }
            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityResumed(Activity a) { }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });

        // Screen turned off -> lock immediately, even if the picker is open.
        registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                pickerOpen = false;
                Session.lock();
            }
        }, new IntentFilter(Intent.ACTION_SCREEN_OFF));
    }
}
