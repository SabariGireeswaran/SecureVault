package com.sabari.photovault.ui;

import android.app.Activity;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** "Move to album" dialog: pick an existing album, make a new one, or remove from album. */
public final class AlbumPicker {

    public interface Callback { void onPicked(String album); }   // "" = remove from album

    private AlbumPicker() {}

    public static void show(Activity a, AppTheme t, Collection<String> existing, Callback cb) {
        List<String> names = new ArrayList<>(existing);
        List<String> labels = new ArrayList<>();
        labels.add("＋  New album…");
        for (String n : names) labels.add("📁  " + n);
        labels.add("✕  Remove from album");

        Ui.show(Ui.dialog(a)
                .setTitle("Move to album")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which == 0) newAlbum(a, t, cb);
                    else if (which == labels.size() - 1) cb.onPicked("");
                    else cb.onPicked(names.get(which - 1));
                })
                .setNegativeButton("Cancel", null), t);
    }

    private static void newAlbum(Activity a, AppTheme t, Callback cb) {
        LinearLayout box = new LinearLayout(a);
        int pad = Ui.dp(a, 22);
        box.setPadding(pad, pad / 2, pad, 0);
        EditText e = new EditText(a);
        e.setHint("Album name (e.g. Family, Documents)");
        e.setTextColor(t.text);
        e.setHintTextColor(t.muted);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        e.setSingleLine(true);
        box.addView(e, new LinearLayout.LayoutParams(-1, -2));
        Ui.show(Ui.dialog(a)
                .setTitle("New album")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", (d, w) -> {
                    String name = e.getText().toString().trim();
                    if (name.isEmpty()) { Toast.makeText(a, "Please type a name", Toast.LENGTH_SHORT).show(); return; }
                    if (name.length() > 30) name = name.substring(0, 30);
                    cb.onPicked(name);
                }), t);
    }
}
