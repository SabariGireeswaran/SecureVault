package com.sabari.photovault.ui;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/** ImageView with pinch-to-zoom, drag, and double-tap zoom. */
public class ZoomImageView extends ImageView {

    private final Matrix matrix = new Matrix();
    private float baseScale = 1f;
    private float zoom = 1f;
    private static final float MAX_ZOOM = 5f;

    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;
    private Runnable onSingleTap;

    public ZoomImageView(Context c) {
        super(c);
        setScaleType(ScaleType.MATRIX);

        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                float f = d.getScaleFactor();
                float next = Math.max(1f, Math.min(zoom * f, MAX_ZOOM));
                f = next / zoom;
                zoom = next;
                matrix.postScale(f, f, d.getFocusX(), d.getFocusY());
                fixBounds();
                return true;
            }
        });

        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (zoom > 1f) { matrix.postTranslate(-dx, -dy); fixBounds(); }
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (zoom > 1f) fitToScreen();
                else {
                    zoom = 2.5f;
                    matrix.postScale(2.5f, 2.5f, e.getX(), e.getY());
                    fixBounds();
                }
                return true;
            }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                // Only swipe to the next photo when not zoomed in.
                if (zoom <= 1.01f && onSwipe != null && Math.abs(vx) > Math.abs(vy) && Math.abs(vx) > 700) {
                    onSwipe.onSwipe(vx < 0 ? 1 : -1);
                }
                return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (onSingleTap != null) onSingleTap.run();
                return true;
            }
        });
    }

    public void setOnSingleTap(Runnable r) { onSingleTap = r; }

    /** direction: +1 = next photo (swiped left), -1 = previous photo (swiped right). */
    public interface SwipeListener { void onSwipe(int direction); }
    private SwipeListener onSwipe;
    public void setOnSwipe(SwipeListener l) { onSwipe = l; }

    @Override
    public void setImageDrawable(Drawable d) {
        super.setImageDrawable(d);
        fitToScreen();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        fitToScreen();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaler.onTouchEvent(e);
        gestures.onTouchEvent(e);
        return true;
    }

    private void fitToScreen() {
        Drawable d = getDrawable();
        if (d == null || getWidth() == 0) return;
        float dw = d.getIntrinsicWidth(), dh = d.getIntrinsicHeight();
        baseScale = Math.min(getWidth() / dw, getHeight() / dh);
        zoom = 1f;
        matrix.reset();
        matrix.postScale(baseScale, baseScale);
        matrix.postTranslate((getWidth() - dw * baseScale) / 2f, (getHeight() - dh * baseScale) / 2f);
        setImageMatrix(matrix);
    }

    /** Keeps the picture from being dragged off the screen. */
    private void fixBounds() {
        Drawable d = getDrawable();
        if (d == null) return;
        RectF r = new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        matrix.mapRect(r);
        float dx = 0, dy = 0;
        if (r.width() <= getWidth()) dx = (getWidth() - r.width()) / 2f - r.left;
        else if (r.left > 0) dx = -r.left;
        else if (r.right < getWidth()) dx = getWidth() - r.right;
        if (r.height() <= getHeight()) dy = (getHeight() - r.height()) / 2f - r.top;
        else if (r.top > 0) dy = -r.top;
        else if (r.bottom < getHeight()) dy = getHeight() - r.bottom;
        matrix.postTranslate(dx, dy);
        setImageMatrix(matrix);
    }
}
