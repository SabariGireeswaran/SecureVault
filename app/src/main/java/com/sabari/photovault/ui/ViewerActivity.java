package com.sabari.photovault.ui;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.sabari.photovault.R;
import com.sabari.photovault.crypto.Session;
import com.sabari.photovault.data.BitmapUtils;
import com.sabari.photovault.data.VaultStore;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen photo with swipe left/right, pinch-zoom, info, move to album, restore, delete.
 * Photos are decrypted only into RAM; nothing decrypted is written to storage.
 */
public class ViewerActivity extends SecureActivity {

    public static final String EXTRA_IDS = "ids";
    public static final String EXTRA_INDEX = "index";

    private ArrayList<String> ids;
    private int index;
    private VaultStore store;
    private ZoomImageView image;
    private LinearLayout topBar, bottomBar;
    private TextView name, details, counter;
    private ProgressBar spinner;
    private Bitmap bitmap;
    private int origW, origH;
    private int loadToken = 0;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Session.isUnlocked()) return;
        ids = getIntent().getStringArrayListExtra(EXTRA_IDS);
        index = getIntent().getIntExtra(EXTRA_INDEX, 0);
        if (ids == null || ids.isEmpty()) { finish(); return; }
        store = new VaultStore(this);
        getWindow().setStatusBarColor(0xFF000000);
        getWindow().setNavigationBarColor(0xFF000000);
        setContentView(buildUi());
        show(0);
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        image = new ZoomImageView(this);
        image.setOnSingleTap(this::toggleBars);
        image.setOnSwipe(this::show);
        root.addView(image, new FrameLayout.LayoutParams(-1, -1));

        spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(t.accent));
        root.addView(spinner, new FrameLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44), Gravity.CENTER));

        // ---------- top bar ----------
        topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 24));
        topBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xCC000000, 0x00000000}));
        topBar.addView(Ui.iconButton(this, R.drawable.ic_back, 0xFFFFFFFF, v -> finish()));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        name = Ui.text(this, "", 16, 0xFFFFFFFF, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        details = Ui.text(this, "", 12, 0xFFBBBBBB, false);
        titles.addView(name);
        titles.addView(details);
        topBar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        counter = Ui.text(this, "", 12, 0xFF0B0B12, true);
        counter.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
        counter.setBackground(Ui.gradient(t.accent, t.accent2, Ui.dp(this, 12), GradientDrawable.Orientation.LEFT_RIGHT));
        topBar.addView(counter);
        root.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // ---------- bottom bar ----------
        bottomBar = new LinearLayout(this);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(Ui.dp(this, 8), Ui.dp(this, 28), Ui.dp(this, 8), Ui.dp(this, 20));
        bottomBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xDD000000, 0x00000000}));
        bottomBar.addView(action(R.drawable.ic_info, "Info", 0xFF4DABF7, v -> showInfo()));
        bottomBar.addView(action(R.drawable.ic_folder, "Album", 0xFFFFA94D, v -> moveToAlbum()));
        bottomBar.addView(action(R.drawable.ic_download, "Restore", 0xFF69DB7C, v -> confirmExport()));
        bottomBar.addView(action(R.drawable.ic_delete, "Delete", t.danger, v -> confirmDelete()));
        root.addView(bottomBar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        return root;
    }

    private View action(int icon, String label, int color, View.OnClickListener l) {
        LinearLayout a = new LinearLayout(this);
        a.setOrientation(LinearLayout.VERTICAL);
        a.setGravity(Gravity.CENTER);
        FrameLayout bubble = new FrameLayout(this);
        bubble.setBackground(Ui.circle(Ui.alpha(color, 0.22f), Ui.alpha(color, 0.10f)));
        ImageView ic = new ImageView(this);
        ic.setImageResource(icon);
        ic.setImageTintList(ColorStateList.valueOf(color));
        bubble.addView(ic, new FrameLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24), Gravity.CENTER));
        a.addView(bubble, new LinearLayout.LayoutParams(Ui.dp(this, 50), Ui.dp(this, 50)));
        TextView tv = Ui.text(this, label, 12, 0xFFEEEEEE, false);
        tv.setPadding(0, Ui.dp(this, 6), 0, 0);
        a.addView(tv);
        a.setBackground(Ui.ripple(Ui.round(0x00000000, Ui.dp(this, 16)), 0x33FFFFFF));
        a.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        a.setOnClickListener(l);
        a.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        return a;
    }

    /** direction: 0 = current, +1 = next, -1 = previous. */
    private void show(int direction) {
        int next = index + direction;
        if (next < 0 || next >= ids.size()) {
            if (direction != 0) {     // small "bounce" at the ends
                image.animate().translationX(direction * -40f).setDuration(90)
                        .withEndAction(() -> image.animate().translationX(0).setDuration(120).start()).start();
            }
            return;
        }
        index = next;
        String id = ids.get(index);
        int token = ++loadToken;

        if (direction != 0) {
            image.setTranslationX(direction * Ui.dp(this, 80));
            image.setAlpha(0f);
        }
        JSONObject meta = store.loadMeta(id);
        name.setText(meta.optString("name", "Photo"));
        String album = meta.optString("album", "");
        long added = meta.optLong("added", 0);
        details.setText((added > 0 ? DateFormat.format("d MMM yyyy, h:mm a", added) : "")
                + "  ·  " + Ui.size(store.sizeOf(id)) + (album.isEmpty() ? "" : "  ·  📁 " + album));
        counter.setText((index + 1) + " / " + ids.size());
        spinner.setVisibility(View.VISIBLE);

        int maxSide = Math.max(getResources().getDisplayMetrics().widthPixels,
                getResources().getDisplayMetrics().heightPixels) * 2;
        worker.execute(() -> {
            try {
                byte[] data = store.loadPhotoBytes(id);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                Bitmap b = BitmapUtils.decode(data, maxSide);
                Arrays.fill(data, (byte) 0);
                main.post(() -> {
                    if (token != loadToken || isFinishing()) { if (b != null) b.recycle(); return; }
                    spinner.setVisibility(View.GONE);
                    if (b == null) { toast("Could not open photo"); return; }
                    Bitmap old = bitmap;
                    bitmap = b;
                    origW = o.outWidth;
                    origH = o.outHeight;
                    image.setImageBitmap(b);
                    image.animate().translationX(0).alpha(1f).setDuration(200).start();
                    if (old != null) old.recycle();
                });
            } catch (Exception e) {
                main.post(() -> { spinner.setVisibility(View.GONE); toast("Could not open photo"); });
            }
        });
    }

    private void toggleBars() {
        boolean show = topBar.getVisibility() != View.VISIBLE;
        for (View v : new View[]{topBar, bottomBar}) {
            if (show) { v.setVisibility(View.VISIBLE); v.setAlpha(0f); v.animate().alpha(1f).setDuration(150).start(); }
            else v.animate().alpha(0f).setDuration(150).withEndAction(() -> v.setVisibility(View.GONE)).start();
        }
    }

    private void showInfo() {
        String id = ids.get(index);
        JSONObject m = store.loadMeta(id);
        long added = m.optLong("added", 0);
        String album = m.optString("album", "");
        String msg = "📄  Name\n" + m.optString("name", "—")
                + "\n\n📅  Added to vault\n" + (added > 0 ? DateFormat.format("EEEE, d MMMM yyyy  h:mm a", added) : "—")
                + "\n\n📐  Resolution\n" + (origW > 0 ? origW + " × " + origH + "  (" + String.format("%.1f", origW * (double) origH / 1e6) + " MP)" : "—")
                + "\n\n💾  Size\n" + Ui.size(store.sizeOf(id))
                + "\n\n🖼  Type\n" + m.optString("mime", "image")
                + "\n\n📁  Album\n" + (album.isEmpty() ? "None" : album)
                + "\n\n🔐  Protection\nAES-256-GCM, stored only on this phone";
        Ui.show(Ui.dialog(this).setTitle("Photo details").setMessage(msg).setPositiveButton("OK", null), t);
    }

    private void moveToAlbum() {
        String id = ids.get(index);
        java.util.Set<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (JSONObject m : Session.META.values()) {
            String a = m.optString("album", "");
            if (!a.isEmpty()) names.add(a);
        }
        AlbumPicker.show(this, t, names, album -> worker.execute(() -> {
            try {
                store.setAlbum(id, album);
                main.post(() -> { toast(album.isEmpty() ? "Removed from album" : "Moved to " + album); show(0); });
            } catch (Exception e) {
                main.post(() -> toast("Could not move photo"));
            }
        }));
    }

    private void confirmExport() {
        String id = ids.get(index);
        Ui.show(Ui.dialog(this)
                .setTitle("Restore to gallery?")
                .setMessage("A normal, unencrypted copy will be saved in Pictures/Restored. "
                        + "Anyone using your phone's gallery will be able to see it. The vault copy stays too.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restore", (d, w) -> worker.execute(() -> {
                    try {
                        store.exportToGallery(id);
                        main.post(() -> toast("Saved to Pictures/Restored"));
                    } catch (Exception e) {
                        main.post(() -> toast("Could not restore"));
                    }
                })), t);
    }

    private void confirmDelete() {
        Ui.show(Ui.dialog(this)
                .setTitle("Delete permanently?")
                .setMessage("This photo will be removed from the vault. This cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    store.delete(ids.remove(index));
                    toast("Deleted");
                    if (ids.isEmpty()) { finish(); return; }
                    if (index >= ids.size()) index = ids.size() - 1;
                    show(0);
                }), t);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!Session.isUnlocked()) finish();   // don't keep a decrypted photo in memory after locking
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
        if (image != null) image.setImageDrawable(null);
        if (bitmap != null) { bitmap.recycle(); bitmap = null; }
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }
}
