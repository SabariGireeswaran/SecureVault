package com.sabari.photovault.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

/** Small helpers so every screen looks the same without repeating code. */
public final class Ui {

    private Ui() {}

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static int alpha(int color, float a) {
        return (Math.round(a * 255) << 24) | (color & 0x00FFFFFF);
    }

    public static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    public static GradientDrawable gradient(int a, int b, float radiusPx, GradientDrawable.Orientation o) {
        GradientDrawable g = new GradientDrawable(o, new int[]{a, b});
        g.setCornerRadius(radiusPx);
        return g;
    }

    public static GradientDrawable circle(int a, int b) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{a, b});
        g.setShape(GradientDrawable.OVAL);
        return g;
    }

    /** Full-screen background gradient for the current theme. */
    public static Drawable background(AppTheme t) {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{t.bgTop, t.bgBottom});
    }

    /** Adds a touch "ripple" on top of a background. */
    public static Drawable ripple(Drawable content, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, content);
    }

    public static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return tv;
    }

    /** Paints the text with a left-to-right colour gradient. */
    public static void gradientText(TextView tv, int a, int b) {
        float w = tv.getPaint().measureText(tv.getText().toString());
        tv.getPaint().setShader(new LinearGradient(0, 0, Math.max(w, 1), 0, a, b, Shader.TileMode.CLAMP));
        tv.invalidate();
    }

    /** Round icon button (48dp touch area). */
    public static ImageView iconButton(Context c, int iconRes, int color, View.OnClickListener l) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(iconRes);
        iv.setImageTintList(ColorStateList.valueOf(color));
        int p = dp(c, 12);
        iv.setPadding(p, p, p, p);
        iv.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null));
        iv.setOnClickListener(l);
        iv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(dp(c, 48), dp(c, 48)));
        return iv;
    }

    /** Big gradient "pill" button. */
    public static Button gradientButton(Context c, AppTheme t, String label, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(0xFF0B0B12);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setStateListAnimator(null);
        b.setBackground(ripple(gradient(t.accent, t.accent2, dp(c, 28), GradientDrawable.Orientation.LEFT_RIGHT), 0x44FFFFFF));
        b.setOnClickListener(l);
        b.setGravity(Gravity.CENTER);
        return b;
    }

    public static void applyWindow(Activity a, AppTheme t) {
        a.getWindow().setStatusBarColor(t.bgTop);
        a.getWindow().setNavigationBarColor(t.bgBottom);
    }

    // ---------------- dialogs ----------------

    public static AlertDialog.Builder dialog(Context c) {
        return new AlertDialog.Builder(c, android.R.style.Theme_Material_Dialog_Alert);
    }

    /** Shows a dialog with rounded theme colours. */
    public static AlertDialog show(AlertDialog.Builder b, AppTheme t) {
        AlertDialog d = b.create();
        if (d.getWindow() != null) {
            GradientDrawable bg = round(t.surface, dp(d.getContext(), 22));
            bg.setStroke(dp(d.getContext(), 1), alpha(t.accent, 0.35f));
            d.getWindow().setBackgroundDrawable(bg);
        }
        d.show();
        tintButtons(d, t);
        return d;
    }

    public static void tintButtons(AlertDialog d, AppTheme t) {
        int[] which = {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL};
        for (int w : which) {
            Button btn = d.getButton(w);
            if (btn != null) btn.setTextColor(w == AlertDialog.BUTTON_POSITIVE ? t.accent : t.muted);
        }
    }

    // ---------------- formatting ----------------

    public static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    public static String ago(long ts) {
        if (ts <= 0) return "never";
        return DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString();
    }
}
