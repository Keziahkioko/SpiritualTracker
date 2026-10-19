package com.keziah.spiritualtracker;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

/**
 * A photo shown with a saved framing (see {@link PhotoFrame}): always filling the view, centred
 * and zoomed as chosen, whatever the view's size. With {@link #setEditable} it can be pinched to
 * zoom and dragged to move. The home screen and the photo editor both use it, so what is set in
 * the editor is exactly what the home screen shows.
 */
public class FramedImageView extends AppCompatImageView {

    private float cx = 0.5f, cy = 0.5f, zoom = 1f;
    private boolean editable;
    /** The photo load now showing (see HomePhoto), released when a newer one replaces it. */
    com.bumptech.glide.request.target.CustomTarget<Drawable> loading;
    private final ScaleGestureDetector scaler;
    private final GestureDetector dragger;

    public FramedImageView(Context context) { this(context, null); }

    public FramedImageView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);
        scaler = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                change(0, 0, d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }
        });
        dragger = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                // distance is how far the content should scroll, i.e. opposite to the finger.
                change(-distanceX, -distanceY, 1f, getWidth() / 2f, getHeight() / 2f);
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                // Double tap: back to the whole photo, centred.
                setFrame(0.5f, 0.5f, 1f);
                return true;
            }
        });
    }

    public void setEditable(boolean editable) { this.editable = editable; }

    public float frameX() { return cx; }
    public float frameY() { return cy; }
    public float frameZoom() { return zoom; }

    public void setFrame(float cx, float cy, float zoom) {
        this.cx = cx;
        this.cy = cy;
        this.zoom = zoom;
        reframe();
    }

    private void change(float dx, float dy, float factor, float fx, float fy) {
        Drawable d = getDrawable();
        if (d == null || getWidth() == 0 || d.getIntrinsicWidth() <= 0) return;
        float[] next = PhotoFrame.moved(d.getIntrinsicWidth(), d.getIntrinsicHeight(), getWidth(), getHeight(),
                cx, cy, zoom, dx, dy, factor, fx, fy);
        cx = next[0];
        cy = next[1];
        zoom = next[2];
        reframe();
    }

    private void reframe() {
        Drawable d = getDrawable();
        if (d == null || getWidth() == 0 || getHeight() == 0
                || d.getIntrinsicWidth() <= 0 || d.getIntrinsicHeight() <= 0) return;
        float[] f = PhotoFrame.fit(d.getIntrinsicWidth(), d.getIntrinsicHeight(), getWidth(), getHeight(), cx, cy, zoom);
        Matrix m = new Matrix();
        m.postScale(f[0], f[0]);
        m.postTranslate(getWidth() / 2f - f[1] * f[0], getHeight() / 2f - f[2] * f[0]);
        setImageMatrix(m);
    }

    @Override public void setImageDrawable(@Nullable Drawable drawable) {
        super.setImageDrawable(drawable);
        reframe();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        reframe();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!editable) return super.onTouchEvent(event);
        getParent().requestDisallowInterceptTouchEvent(true);
        scaler.onTouchEvent(event);
        if (!scaler.isInProgress()) dragger.onTouchEvent(event);
        return true;
    }
}
