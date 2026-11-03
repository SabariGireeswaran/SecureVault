package com.sabari.photovault.ui;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.sabari.photovault.crypto.Session;
import com.sabari.photovault.data.BitmapUtils;
import com.sabari.photovault.data.VaultStore;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full-screen photo. Decrypted only into RAM; nothing decrypted is written to storage. */
public class ViewerActivity extends SecureActivity {

    public static final String EXTRA_ID = "id";

    private String id;
    private VaultStore store;
    private ZoomImageView image;
    private LinearLayout bar;
    private Bitmap bitmap;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Session.isUnlocked()) return;
        id = getIntent().getStringExtra(EXTRA_ID);
        store = new VaultStore(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        image = new ZoomImageView(this);
        root.addView(image, new FrameLayout.LayoutParams(-1, -1));

        ProgressBar spinner = new ProgressBar(this);
        root.addView(spinner, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        bar = new LinearLayout(this);
        bar.setBackgroundColor(Color.parseColor("#CC0E1116"));
        bar.setGravity(Gravity.CENTER);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        bar.setPadding(pad, pad, pad, pad * 2);
        bar.addView(barButton("Restore to gallery", v -> confirmExport()));
        bar.addView(barButton("Delete", v -> confirmDelete()));
        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        image.setOnSingleTap(() -> bar.setVisibility(bar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));

        setContentView(root);

        int maxSide = Math.max(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels) * 2;
        worker.execute(() -> {
            try {
                byte[] data = store.loadPhotoBytes(id);
                Bitmap b = BitmapUtils.decode(data, maxSide);
                Arrays.fill(data, (byte) 0);
                main.post(() -> {
                    spinner.setVisibility(View.GONE);
                    if (b == null || isFinishing()) return;
                    bitmap = b;
                    image.setImageBitmap(b);
                });
            } catch (Exception e) {
                main.post(() -> {
                    spinner.setVisibility(View.GONE);
                    Toast.makeText(this, "Could not open photo", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Leaving the screen? Close the photo so it doesn't sit decrypted in memory.
        if (!Session.isUnlocked()) finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
        if (image != null) image.setImageDrawable(null);
        if (bitmap != null) { bitmap.recycle(); bitmap = null; }
    }

    private Button barButton(String text, View.OnClickListener l) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.parseColor("#E8EDF2"));
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        return b;
    }

    private void confirmExport() {
        new AlertDialog.Builder(this)
                .setTitle("Restore to gallery?")
                .setMessage("A normal, unencrypted copy will be saved in Pictures/Restored. "
                        + "Anyone using your phone's gallery will be able to see it. The vault copy stays too.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restore", (d, w) -> worker.execute(() -> {
                    try {
                        store.exportToGallery(id);
                        main.post(() -> Toast.makeText(this, "Saved to Pictures/Restored", Toast.LENGTH_SHORT).show());
                    } catch (Exception e) {
                        main.post(() -> Toast.makeText(this, "Could not restore", Toast.LENGTH_SHORT).show());
                    }
                }))
                .show();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("Delete permanently?")
                .setMessage("This photo will be removed from the vault. This cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    store.delete(id);
                    finish();
                })
                .show();
    }
}
