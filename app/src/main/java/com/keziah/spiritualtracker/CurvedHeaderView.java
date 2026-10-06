package com.keziah.spiritualtracker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

public class CurvedHeaderView extends View {

    private Path path = new Path();
    private Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public CurvedHeaderView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        paint.setStyle(Paint.Style.FILL);
        // You can set the default premium purple color here: #2B1676
        paint.setColor(0xFF2B1676);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float h = getHeight();

        // Define a path to draw the cupped shape.
        path.reset();
        // 1. Start at the top-left corner
        path.moveTo(0, 0);
        // 2. Line to the top-right corner
        path.lineTo(w, 0);
        // 3. Line down partway on the right side.
        //    This sets the 'cup' height. Adjust the multiplier as needed (0.5f to 0.7f).
        float cupEndpointRightY = h * 0.65f;
        path.lineTo(w, cupEndpointRightY);

        // 4. Draw the critical concave curve (the inward 'cup').
        //    We draw a curve to the left side partway down, using a 
        //    control point positioned near the center top.
        float cupEndpointLeftY = h * 0.65f;
        float controlPointX = w / 2f;
        // Set the control point to be higher, near the top, to make the cup deep.
        float controlPointY = h * 0.15f;

        path.cubicTo(w, cupEndpointRightY, controlPointX, controlPointY, 0, cupEndpointLeftY);

        // 5. Line from the left endpoint up to the top-left to close.
        path.lineTo(0, 0);
        path.close();

        // Paint the entire closed path.
        canvas.drawPath(path, paint);
    }

    /**
     * Optional: Method to change the color dynamically from code.
     */
    public void setHeaderColor(@ColorInt int colorInt) {
        paint.setColor(colorInt);
        invalidate(); // Redraw view with new color
    }
}