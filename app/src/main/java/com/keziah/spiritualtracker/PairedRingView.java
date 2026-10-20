package com.keziah.spiritualtracker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** Two progress rings, one inside the other: outer for me, inner for my partner. */
public class PairedRingView extends View {

    public static final int ME = 0xFF7E57C2;
    public static final int PARTNER = 0xFFB8479B;
    private static final int ME_TRACK = 0xFFEDE7F6;
    private static final int PARTNER_TRACK = 0xFFF6E1EF;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private float mine = 0f;
    /** Negative means there is no partner ring to draw. */
    private float theirs = -1f;
    private int meColor = ME, meTrack = ME_TRACK;

    public PairedRingView(Context context) { this(context, null); }

    public PairedRingView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    /** One ring only, in the given colour (used on a single person's page). */
    public void setSingle(float value, int color, int track) {
        meColor = color;
        meTrack = track;
        setValues(value, -1f);
    }

    /** Fractions from 0 to 1; pass a negative partner value to show my ring only. */
    public void setValues(float me, float partner) {
        mine = clamp(me);
        theirs = partner < 0 ? -1f : clamp(partner);
        setContentDescription(Math.round(mine * 100) + "% for you"
                + (theirs < 0 ? "" : ", " + Math.round(theirs * 100) + "% for your partner"));
        invalidate();
    }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

    @Override
    protected void onDraw(Canvas canvas) {
        float d = getResources().getDisplayMetrics().density;
        float stroke = (theirs < 0 ? 8 : 7) * d;
        float gap = 3 * d;
        float size = Math.min(getWidth(), getHeight());
        float cx = getWidth() / 2f, cy = getHeight() / 2f;

        float r = size / 2f - stroke / 2f;
        ring(canvas, cx, cy, r, stroke, meTrack, meColor, mine);
        if (theirs >= 0) ring(canvas, cx, cy, r - stroke - gap, stroke, PARTNER_TRACK, PARTNER, theirs);
    }

    private void ring(Canvas c, float cx, float cy, float r, float stroke, int track, int color, float value) {
        box.set(cx - r, cy - r, cx + r, cy + r);
        paint.setStrokeWidth(stroke);
        paint.setColor(track);
        c.drawArc(box, 0, 360, false, paint);
        if (value <= 0f) return;
        paint.setColor(color);
        c.drawArc(box, -90, Math.max(4f, 360f * value), false, paint);
    }
}
