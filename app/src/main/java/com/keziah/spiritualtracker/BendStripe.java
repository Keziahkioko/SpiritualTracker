package com.keziah.spiritualtracker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;

/**
 * The coloured edge down the left of a card. Instead of a straight bar it follows the card's
 * rounded corners: full width down the side, then bending round the top and bottom corners and
 * thinning away to nothing, like a border drawn on the card itself.
 *
 * Sits at the card's left edge, {@code corner + width} wide, with a negative end margin of
 * {@code corner} so the content beside it doesn't move. Colour it with setBackgroundColor or
 * android:background as before.
 */
public class BendStripe extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outer = new Path(), inner = new Path(), shape = new Path();
    private final float corner, thick;
    /** No initialiser: android:background sets it while View's constructor runs. */
    private int color;

    public BendStripe(Context context) { this(context, null); }

    public BendStripe(Context context, AttributeSet attrs) {
        super(context, attrs);
        float d = getResources().getDisplayMetrics().density;
        corner = 18 * d;
        thick = 4 * d;
        if (color == 0) color = 0xFF7E57C2;
    }

    @Override public void setBackgroundColor(int c) {
        color = c;
        invalidate();
    }

    @Override public void setBackground(Drawable bg) {
        if (bg instanceof ColorDrawable) setBackgroundColor(((ColorDrawable) bg).getColor());
        else super.setBackground(bg);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float r = Math.min(corner, h / 2f);
        float far = w + 4 * corner; // the right side of both shapes lies outside the view
        outer.reset();
        outer.addRoundRect(0, 0, far, h, r, r, Path.Direction.CW);
        inner.reset();
        inner.addRoundRect(thick, 0, far, h, r, r, Path.Direction.CW);
        shape.reset();
        shape.op(outer, inner, Path.Op.DIFFERENCE);
    }

    @Override protected void onDraw(Canvas canvas) {
        paint.setColor(color);
        canvas.drawPath(shape, paint);
    }
}
