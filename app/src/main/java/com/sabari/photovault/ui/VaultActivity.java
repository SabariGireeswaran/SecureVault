package com.sabari.photovault.ui;

import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.sabari.photovault.R;
import com.sabari.photovault.VaultApp;
import com.sabari.photovault.crypto.Session;
import com.sabari.photovault.data.AppSettings;
import com.sabari.photovault.data.PhotoItem;
import com.sabari.photovault.data.VaultStore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Home screen: dashboard, album chips, photo grid, multi-select. */
public class VaultActivity extends SecureActivity {

    private static final int REQ_PICK = 42;
    private static final int DARK = 0xFF0B0B12;

    private VaultStore store;
    private AppSettings settings;
    private BackupFlow backup;

    private final List<PhotoItem> all = new ArrayList<>();
    private final List<PhotoItem> shown = new ArrayList<>();
    private String album = null;                       // null = "All photos"
    private final Set<String> selected = new LinkedHashSet<>();
    private boolean selecting = false;
    private boolean firstLoad = true;

    private LinearLayout normalBar, selBar, chipRow, emptyView;
    private TextView selCount, statCount, statSize, statBackup;
    private GridView grid;
    private FrameLayout fab;
    private GridAdapter adapter;

    private final ExecutorService thumbLoader = Executors.newFixedThreadPool(2);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Session.isUnlocked()) return;
        store = new VaultStore(this);
        settings = new AppSettings(this);
        backup = new BackupFlow(this, t, this::load);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Session.isUnlocked() && store != null) load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        thumbLoader.shutdownNow();
        worker.shutdownNow();
        if (backup != null) backup.destroy();
    }

    @Override
    public void onBackPressed() {
        if (selecting) exitSelect();
        else super.onBackPressed();
    }

    // =================================================================== layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, Ui.dp(this, 8), 0, 0);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // ---------- top bar (normal) ----------
        FrameLayout header = new FrameLayout(this);
        normalBar = row();
        normalBar.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 6), 0);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView tag = Ui.text(this, "ENCRYPTED  ·  OFFLINE", 11, t.muted, true);
        tag.setLetterSpacing(0.12f);
        titles.addView(tag);
        TextView title = Ui.text(this, "SecureVault", 26, t.text, true);
        title.setMaxLines(1);
        Ui.gradientText(title, t.accent, t.accent2);
        titles.addView(title);
        normalBar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        normalBar.addView(Ui.iconButton(this, R.drawable.ic_lock_small, t.text, v -> { Session.lock(); goToLock(); }));
        normalBar.addView(Ui.iconButton(this, R.drawable.ic_more, t.text, v -> startActivity(new Intent(this, SettingsActivity.class))));
        header.addView(normalBar, new FrameLayout.LayoutParams(-1, -1));

        // ---------- top bar (selection mode) ----------
        selBar = row();
        selBar.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        selBar.setBackground(Ui.round(Ui.alpha(t.accent, 0.14f), Ui.dp(this, 20)));
        selBar.addView(Ui.iconButton(this, R.drawable.ic_close, t.text, v -> exitSelect()));
        // Only the number is shown (e.g. "12"), so it never gets squeezed by the 5 buttons.
        selCount = Ui.text(this, "", 20, t.text, true);
        selCount.setMaxLines(1);
        selCount.setEllipsize(android.text.TextUtils.TruncateAt.END);
        selBar.addView(selCount, new LinearLayout.LayoutParams(0, -2, 1f));
        selBar.addView(Ui.iconButton(this, R.drawable.ic_select_all, t.text, v -> selectAll()));
        selBar.addView(Ui.iconButton(this, R.drawable.ic_folder, t.accent2, v -> moveSelected()));
        selBar.addView(Ui.iconButton(this, R.drawable.ic_download, t.accent, v -> restoreSelected()));
        selBar.addView(Ui.iconButton(this, R.drawable.ic_delete, t.danger, v -> deleteSelected()));
        selBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, -1);
        sp.setMargins(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
        header.addView(selBar, sp);
        col.addView(header, new LinearLayout.LayoutParams(-1, Ui.dp(this, 68)));

        // ---------- dashboard card ----------
        col.addView(buildStatsCard(), margins(new LinearLayout.LayoutParams(-1, -2), 16, 8, 16, 0));

        // ---------- album chips ----------
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        chipRow = new LinearLayout(this);
        chipRow.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        hs.addView(chipRow);
        col.addView(hs, margins(new LinearLayout.LayoutParams(-1, -2), 0, 14, 0, 6));

        // ---------- grid + empty state ----------
        FrameLayout content = new FrameLayout(this);
        grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        int g = Ui.dp(this, 5);
        grid.setHorizontalSpacing(g);
        grid.setVerticalSpacing(g);
        grid.setPadding(Ui.dp(this, 12), g, Ui.dp(this, 12), Ui.dp(this, 100));
        grid.setClipToPadding(false);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(0));
        adapter = new GridAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> onPhotoTap(pos));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            if (!selecting) enterSelect();
            toggle(shown.get(pos).id);
            return true;
        });
        content.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        emptyView = buildEmptyView();
        content.addView(emptyView, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        col.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        // ---------- floating "+" button ----------
        fab = new FrameLayout(this);
        fab.setBackground(Ui.ripple(Ui.circle(t.accent, t.accent2), 0x44FFFFFF));
        fab.setElevation(Ui.dp(this, 10));
        ImageView plus = new ImageView(this);
        plus.setImageResource(R.drawable.ic_add);
        plus.setImageTintList(ColorStateList.valueOf(DARK));
        fab.addView(plus, new FrameLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32), Gravity.CENTER));
        fab.setOnClickListener(v -> pickPhotos());
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(Ui.dp(this, 66), Ui.dp(this, 66), Gravity.BOTTOM | Gravity.END);
        fp.setMargins(0, 0, Ui.dp(this, 22), Ui.dp(this, 26));
        root.addView(fab, fp);
        return root;
    }

    private View buildStatsCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 18);
        card.setPadding(p, p, p, Ui.dp(this, 12));
        card.setBackground(Ui.gradient(t.accent, t.accent2, Ui.dp(this, 26), GradientDrawable.Orientation.TL_BR));
        card.setElevation(Ui.dp(this, 6));

        LinearLayout nums = new LinearLayout(this);
        statCount = Ui.text(this, "0", 32, DARK, true);
        statSize = Ui.text(this, "0 B", 32, DARK, true);
        nums.addView(stat(statCount, "photos protected"), new LinearLayout.LayoutParams(0, -2, 1f));
        nums.addView(stat(statSize, "encrypted storage"), new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(nums);

        View line = new View(this);
        line.setBackgroundColor(Ui.alpha(DARK, 0.15f));
        card.addView(line, margins(new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)), 0, 12, 0, 6));

        LinearLayout backupRow = row();
        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_backup);
        ic.setImageTintList(ColorStateList.valueOf(DARK));
        backupRow.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20)));
        statBackup = Ui.text(this, "", 13, DARK, true);
        statBackup.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), 0, Ui.dp(this, 6));
        backupRow.addView(statBackup, new LinearLayout.LayoutParams(0, -2, 1f));
        backupRow.addView(Ui.text(this, "›", 22, DARK, true));
        backupRow.setOnClickListener(v -> backup.startBackup(all.size()));
        card.addView(backupRow);
        return card;
    }

    private LinearLayout stat(TextView big, String label) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        // Fixed height + auto-size: a long value like "1.25 GB" shrinks a little instead of breaking the card.
        big.setMaxLines(1);
        big.setIncludeFontPadding(false);
        big.setGravity(Gravity.CENTER_VERTICAL);
        big.setAutoSizeTextTypeUniformWithConfiguration(14, 30, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        c.addView(big, new LinearLayout.LayoutParams(-1, Ui.dp(this, 38)));
        TextView l = Ui.text(this, label, 12, Ui.alpha(DARK, 0.7f), false);
        l.setMaxLines(1);
        l.setEllipsize(android.text.TextUtils.TruncateAt.END);
        c.addView(l);
        c.setPadding(0, 0, Ui.dp(this, 8), 0);
        return c;
    }

    private LinearLayout buildEmptyView() {
        LinearLayout e = new LinearLayout(this);
        e.setOrientation(LinearLayout.VERTICAL);
        e.setGravity(Gravity.CENTER_HORIZONTAL);
        e.setPadding(Ui.dp(this, 40), 0, Ui.dp(this, 40), Ui.dp(this, 60));
        FrameLayout art = new FrameLayout(this);
        View ring = new View(this);
        ring.setBackground(Ui.circle(Ui.alpha(t.accent, 0.18f), Ui.alpha(t.accent2, 0.18f)));
        art.addView(ring, new FrameLayout.LayoutParams(-1, -1));
        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_photo);
        ic.setImageTintList(ColorStateList.valueOf(t.accent));
        art.addView(ic, new FrameLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52), Gravity.CENTER));
        e.addView(art, new LinearLayout.LayoutParams(Ui.dp(this, 110), Ui.dp(this, 110)));
        TextView h = Ui.text(this, "Your vault is empty", 20, t.text, true);
        h.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 6));
        e.addView(h);
        TextView s = Ui.text(this, "Tap  +  to hide your first photos.\nThey are encrypted and never leave this phone.", 14, t.muted, false);
        s.setGravity(Gravity.CENTER);
        e.addView(s);
        return e;
    }

    // =================================================================== data

    private void load() {
        worker.execute(() -> {
            List<PhotoItem> list = store.list();
            main.post(() -> {
                if (isFinishing()) return;
                all.clear();
                all.addAll(list);
                Set<String> ids = new LinkedHashSet<>();
                for (PhotoItem p : all) ids.add(p.id);
                selected.retainAll(ids);
                if (album != null && !VaultStore.albums(all).containsKey(album)) album = null;
                rebuildChips();
                applyFilter();
                updateStats();
                if (selecting) updateSelBar();
            });
        });
    }

    private void applyFilter() {
        shown.clear();
        for (PhotoItem p : all) if (album == null || album.equalsIgnoreCase(p.album)) shown.add(p);
        adapter.notifyDataSetChanged();
        emptyView.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void updateStats() {
        long total = 0;
        for (PhotoItem p : all) total += p.sizeBytes;
        statSize.setText(Ui.size(total));
        int n = all.size();
        if (firstLoad && n > 0) {                     // count-up animation
            ValueAnimator va = ValueAnimator.ofInt(0, n);
            va.setDuration(Math.min(900, 300 + n * 10L));
            va.addUpdateListener(a -> statCount.setText(String.valueOf(a.getAnimatedValue())));
            va.start();
        } else {
            statCount.setText(String.valueOf(n));
        }
        firstLoad = false;

        long last = settings.lastBackup();
        long newest = all.isEmpty() ? 0 : all.get(0).addedAt;
        if (all.isEmpty()) statBackup.setText("Backups will appear here once you add photos");
        else if (last == 0) statBackup.setText("⚠  No backup yet — tap to back up now");
        else if (newest > last) statBackup.setText("⚠  New photos since last backup (" + Ui.ago(last) + ")");
        else statBackup.setText("✓  Backed up " + Ui.ago(last));
    }

    private void rebuildChips() {
        chipRow.removeAllViews();
        chipRow.addView(chip("All  " + all.size(), 0, album == null, () -> { album = null; rebuildChips(); applyFilter(); }));
        for (Map.Entry<String, Integer> e : VaultStore.albums(all).entrySet()) {
            String name = e.getKey();
            chipRow.addView(chip(name + "  " + e.getValue(), AppTheme.albumColor(name), name.equalsIgnoreCase(album),
                    () -> { album = name; rebuildChips(); applyFilter(); }));
        }
    }

    private TextView chip(String label, int dotColor, boolean active, Runnable onTap) {
        SpannableString s = new SpannableString((dotColor != 0 ? "●  " : "") + label);
        if (dotColor != 0) s.setSpan(new ForegroundColorSpan(active ? DARK : dotColor), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView c = Ui.text(this, s, 14, active ? DARK : t.text, true);
        int h = Ui.dp(this, 16), v = Ui.dp(this, 9);
        c.setPadding(h, v, h, v);
        GradientDrawable bg = active
                ? Ui.gradient(t.accent, t.accent2, Ui.dp(this, 20), GradientDrawable.Orientation.LEFT_RIGHT)
                : Ui.round(Ui.alpha(t.surface, 0.9f), Ui.dp(this, 20));
        if (!active) bg.setStroke(Ui.dp(this, 1), Ui.alpha(dotColor != 0 ? dotColor : t.accent, 0.35f));
        c.setBackground(Ui.ripple(bg, 0x33FFFFFF));
        c.setOnClickListener(x -> onTap.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        c.setLayoutParams(p);
        return c;
    }

    // =================================================================== taps & selection

    private void onPhotoTap(int pos) {
        if (selecting) { toggle(shown.get(pos).id); return; }
        ArrayList<String> ids = new ArrayList<>();
        for (PhotoItem p : shown) ids.add(p.id);
        Intent i = new Intent(this, ViewerActivity.class);
        i.putStringArrayListExtra(ViewerActivity.EXTRA_IDS, ids);
        i.putExtra(ViewerActivity.EXTRA_INDEX, pos);
        startActivity(i);
    }

    private void enterSelect() {
        selecting = true;
        normalBar.setVisibility(View.GONE);
        selBar.setVisibility(View.VISIBLE);
        selBar.setAlpha(0f);
        selBar.animate().alpha(1f).setDuration(150).start();
        fab.animate().scaleX(0f).scaleY(0f).setDuration(150).start();
        updateSelBar();
    }

    private void exitSelect() {
        selecting = false;
        selected.clear();
        selBar.setVisibility(View.GONE);
        normalBar.setVisibility(View.VISIBLE);
        fab.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
        adapter.notifyDataSetChanged();
    }

    private void toggle(String id) {
        if (!selected.remove(id)) selected.add(id);
        if (selected.isEmpty()) { exitSelect(); return; }
        updateSelBar();
        adapter.notifyDataSetChanged();
    }

    private void selectAll() {
        if (selected.size() == shown.size()) selected.clear();
        else for (PhotoItem p : shown) selected.add(p.id);
        if (selected.isEmpty()) { exitSelect(); return; }
        updateSelBar();
        adapter.notifyDataSetChanged();
    }

    private void updateSelBar() {
        selCount.setText(String.valueOf(selected.size()));
        selCount.setContentDescription(selected.size() + " selected");
    }

    private void deleteSelected() {
        List<String> ids = new ArrayList<>(selected);
        Ui.show(Ui.dialog(this)
                .setTitle("Delete " + ids.size() + (ids.size() == 1 ? " photo?" : " photos?"))
                .setMessage("They will be removed from the vault permanently. This cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> worker.execute(() -> {
                    for (String id : ids) store.delete(id);
                    main.post(() -> { exitSelect(); load(); toast(ids.size() + " deleted"); });
                })), t);
    }

    private void restoreSelected() {
        List<String> ids = new ArrayList<>(selected);
        Ui.show(Ui.dialog(this)
                .setTitle("Restore to gallery?")
                .setMessage("Normal, unencrypted copies of " + ids.size() + " photo(s) will be saved in "
                        + "Pictures/Restored, where anyone using your gallery can see them. The vault copies stay.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restore", (d, w) -> worker.execute(() -> {
                    int ok = 0;
                    for (String id : ids) {
                        try { store.exportToGallery(id); ok++; } catch (Exception ignored) { }
                    }
                    int fOk = ok;
                    main.post(() -> { exitSelect(); toast(fOk + " saved to Pictures/Restored"); });
                })), t);
    }

    private void moveSelected() {
        List<String> ids = new ArrayList<>(selected);
        AlbumPicker.show(this, t, VaultStore.albums(all).keySet(), name -> worker.execute(() -> {
            int ok = 0;
            for (String id : ids) {
                try { store.setAlbum(id, name); ok++; } catch (Exception ignored) { }
            }
            int fOk = ok;
            main.post(() -> {
                exitSelect();
                load();
                toast(name.isEmpty() ? fOk + " removed from album" : fOk + " moved to " + name);
            });
        }));
    }

    // =================================================================== add photos

    private void pickPhotos() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        VaultApp.allowPicker();   // don't auto-lock while the picker is open
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (backup != null && backup.onActivityResult(requestCode, resultCode, data)) return;
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null || !Session.isUnlocked()) return;

        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) for (int k = 0; k < clip.getItemCount(); k++) uris.add(clip.getItemAt(k).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;

        String target = album == null ? "" : album;
        Ui.show(Ui.dialog(this)
                .setTitle("Hide " + uris.size() + (uris.size() == 1 ? " photo" : " photos")
                        + (target.isEmpty() ? "" : " in “" + target + "”"))
                .setMessage("Delete the originals from your gallery after they are safely encrypted?\n\n"
                        + "(Recommended. Also check Google Photos or any cloud backup — it may keep its own copy.)")
                .setPositiveButton("Encrypt & delete", (d, w) -> runImport(uris, true, target))
                .setNegativeButton("Keep originals", (d, w) -> runImport(uris, false, target)), t);
    }

    private void runImport(List<Uri> uris, boolean deleteOriginals, String target) {
        android.app.AlertDialog progress = Ui.show(Ui.dialog(this).setTitle("🔐 Encrypting…")
                .setMessage("0 of " + uris.size()).setCancelable(false), t);
        worker.execute(() -> {
            int ok = 0, failed = 0, notDeleted = 0;
            for (int k = 0; k < uris.size(); k++) {
                final int n = k + 1;
                main.post(() -> progress.setMessage(n + " of " + uris.size()));
                try {
                    store.importPhoto(uris.get(k), target);
                    ok++;
                    if (deleteOriginals && !store.deleteOriginal(uris.get(k))) notDeleted++;
                } catch (Exception e) {
                    failed++;
                    if (!Session.isUnlocked()) break;
                }
            }
            final int fOk = ok, fFailed = failed, fNotDeleted = notDeleted;
            main.post(() -> {
                progress.dismiss();
                if (!Session.isUnlocked()) { goToLock(); return; }
                load();
                StringBuilder msg = new StringBuilder(fOk + " photo(s) encrypted and hidden.");
                if (fFailed > 0) msg.append("\n").append(fFailed).append(" could not be added.");
                if (fNotDeleted > 0) msg.append("\n\n").append(fNotDeleted)
                        .append(" original(s) could not be deleted automatically. Please delete them in your Gallery app.");
                Ui.show(Ui.dialog(this).setTitle("✅ Done").setMessage(msg.toString()).setPositiveButton("OK", null), t);
            });
        });
    }

    // =================================================================== helpers

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private LinearLayout.LayoutParams margins(LinearLayout.LayoutParams p, int l, int tp, int r, int b) {
        p.setMargins(Ui.dp(this, l), Ui.dp(this, tp), Ui.dp(this, r), Ui.dp(this, b));
        return p;
    }

    // =================================================================== grid adapter

    private static class Holder {
        ImageView img, check;
        View dim;
        String id;
    }

    private class GridAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int p) { return shown.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            Holder h;
            FrameLayout cell = (FrameLayout) convert;
            if (cell == null) {
                cell = new FrameLayout(VaultActivity.this);
                float r = Ui.dp(VaultActivity.this, 14);
                cell.setOutlineProvider(new ViewOutlineProvider() {
                    @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
                });
                cell.setClipToOutline(true);
                cell.setBackgroundColor(t.surface);
                h = new Holder();
                h.img = new ImageView(VaultActivity.this);
                h.img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                cell.addView(h.img, new FrameLayout.LayoutParams(-1, -1));
                h.dim = new View(VaultActivity.this);
                h.dim.setBackgroundColor(Ui.alpha(t.accent, 0.35f));
                cell.addView(h.dim, new FrameLayout.LayoutParams(-1, -1));
                h.check = new ImageView(VaultActivity.this);
                int s = Ui.dp(VaultActivity.this, 24), m = Ui.dp(VaultActivity.this, 6);
                FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(s, s, Gravity.TOP | Gravity.END);
                cp.setMargins(m, m, m, m);
                int pad = Ui.dp(VaultActivity.this, 3);
                h.check.setPadding(pad, pad, pad, pad);
                cell.addView(h.check, cp);
                cell.setTag(h);
            } else {
                h = (Holder) cell.getTag();
            }
            int side = ((GridView) parent).getColumnWidth();
            cell.setLayoutParams(new AbsListView.LayoutParams(side, side));

            String id = shown.get(pos).id;
            boolean isSel = selected.contains(id);
            h.dim.setVisibility(isSel ? View.VISIBLE : View.GONE);
            h.check.setVisibility(selecting ? View.VISIBLE : View.GONE);
            if (isSel) {
                h.check.setBackground(Ui.circle(t.accent, t.accent2));
                h.check.setImageResource(R.drawable.ic_check);
                h.check.setImageTintList(ColorStateList.valueOf(DARK));
            } else {
                GradientDrawable ring = new GradientDrawable();
                ring.setShape(GradientDrawable.OVAL);
                ring.setColor(0x55000000);
                ring.setStroke(Ui.dp(VaultActivity.this, 2), 0xFFFFFFFF);
                h.check.setBackground(ring);
                h.check.setImageDrawable(null);
            }

            if (id.equals(h.id) && h.img.getDrawable() != null) return cell;   // already showing it
            h.id = id;
            Bitmap cached = Session.THUMBS.get(id);
            if (cached != null) {
                h.img.setImageBitmap(cached);
            } else {
                h.img.setImageDrawable(null);
                final Holder target = h;
                thumbLoader.execute(() -> {
                    try {
                        Bitmap b = store.loadThumbnail(id);
                        if (b == null) return;
                        Session.THUMBS.put(id, b);
                        main.post(() -> {
                            if (!id.equals(target.id)) return;
                            target.img.setAlpha(0f);
                            target.img.setImageBitmap(b);
                            target.img.animate().alpha(1f).setDuration(220).start();
                        });
                    } catch (Exception ignored) { }
                });
            }
            return cell;
        }
    }
}
