package com.keziah.spiritualtracker;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.appcompat.widget.AppCompatImageView;

/** Image that fits the screen and can be pinched, dragged and double-tapped to zoom. */
public class ZoomImageView extends AppCompatImageView {

    private static final float MAX_ZOOM = 5f;
    private static final float DOUBLE_TAP_ZOOM = 2.5f;

    private final Matrix base = new Matrix();
    private final Matrix zoom = new Matrix();
    private final Matrix shown = new Matrix();
    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;

    public ZoomImageView(Context context) { this(context, null); }

    public ZoomImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);
        scaler = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                float target = Math.max(1f, Math.min(MAX_ZOOM, scale() * d.getScaleFactor()));
                float f = target / scale();
                zoom.postScale(f, f, d.getFocusX(), d.getFocusY());
                apply();
                return true;
            }
        });
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scale() <= 1.01f) return false;
                zoom.postTranslate(-dx, -dy);
                apply();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (scale() > 1.01f) zoom.reset();
                else zoom.postScale(DOUBLE_TAP_ZOOM, DOUBLE_TAP_ZOOM, e.getX(), e.getY());
                apply();
                return true;
            }
        });
    }

    private float scale() {
        float[] v = new float[9];
        zoom.getValues(v);
        return v[Matrix.MSCALE_X];
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        zoom.reset();
        fit();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fit();
    }

    private void fit() {
        Drawable d = getDrawable();
        if (d == null || getWidth() == 0 || d.getIntrinsicWidth() <= 0) return;
        base.setRectToRect(new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight()),
                new RectF(0, 0, getWidth(), getHeight()), Matrix.ScaleToFit.CENTER);
        apply();
    }

    /** Keeps the picture from being dragged off screen, then shows it. */
    private void apply() {
        Drawable d = getDrawable();
        if (d == null) return;
        shown.set(base);
        shown.postConcat(zoom);
        RectF r = new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        shown.mapRect(r);
        float dx = 0, dy = 0;
        if (r.width() <= getWidth()) dx = (getWidth() - r.width()) / 2f - r.left;
        else if (r.left > 0) dx = -r.left;
        else if (r.right < getWidth()) dx = getWidth() - r.right;
        if (r.height() <= getHeight()) dy = (getHeight() - r.height()) / 2f - r.top;
        else if (r.top > 0) dy = -r.top;
        else if (r.bottom < getHeight()) dy = getHeight() - r.bottom;
        zoom.postTranslate(dx, dy);
        shown.postTranslate(dx, dy);
        setImageMatrix(shown);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaler.onTouchEvent(event);
        gestures.onTouchEvent(event);
        // While zoomed in, dragging moves the photo instead of swiping to the next one.
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(scale() > 1.01f || event.getPointerCount() > 1);
        }
        return true;
    }
}
