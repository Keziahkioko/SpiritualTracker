package com.keziah.spiritualtracker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

/**
 * A verse's week at a glance: one dot per review day. Days done are filled, today's day is a
 * ring, the rest are faint. All filled (in the mastered colour) once the verse is mastered.
 */
public class StepDots extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d = getResources().getDisplayMetrics().density;
    private int count = 5, done = 0;
    private boolean mastered;

    public StepDots(Context context) { this(context, null); }

    public StepDots(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** {@code done} days finished out of {@code count}; {@code mastered} fills them all. */
    public void set(int count, int done, boolean mastered) {
        this.count = Math.max(1, count);
        this.done = Math.max(0, Math.min(done, this.count));
        this.mastered = mastered;
        setContentDescription(mastered ? "Mastered" : "Day " + Math.min(this.done + 1, this.count) + " of " + this.count);
        requestLayout();
        invalidate();
    }

    private float dot() { return 7 * d; }
    private float gap() { return 5 * d; }

    @Override protected void onMeasure(int w, int h) {
        int width = (int) Math.ceil(count * dot() + (count - 1) * gap() + 2 * d);
        int height = (int) Math.ceil(dot() + 2 * d);
        setMeasuredDimension(resolveSize(width, w), resolveSize(height, h));
    }

    @Override protected void onDraw(Canvas canvas) {
        int fill = ContextCompat.getColor(getContext(), mastered ? R.color.st_mastered : R.color.st_accent);
        int faint = (ContextCompat.getColor(getContext(), R.color.st_text_3) & 0x00FFFFFF) | 0x59000000; // faint but visible on either look
        float r = dot() / 2f, cy = getHeight() / 2f, x = d + r;
        for (int i = 0; i < count; i++, x += dot() + gap()) {
            if (mastered || i < done) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(fill);
                canvas.drawCircle(x, cy, r, paint);
            } else if (i == done) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.6f * d);
                paint.setColor(fill);
                canvas.drawCircle(x, cy, r - 0.8f * d, paint);
            } else {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(faint);
                canvas.drawCircle(x, cy, r, paint);
            }
        }
    }
}
