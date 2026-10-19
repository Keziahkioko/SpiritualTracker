package com.keziah.spiritualtracker;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

/**
 * A scroll box that is only as tall as its content, up to a set height ({@link #setMaxHeight});
 * past that its content scrolls inside it. Used inside a scrolling page, where it scrolls first
 * and hands over to the page at its ends.
 */
public class CappedScrollView extends NestedScrollView {

    private int maxHeight = Integer.MAX_VALUE;

    public CappedScrollView(Context context) { super(context); }

    public CappedScrollView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); }

    /** {@link Integer#MAX_VALUE} for no limit. */
    public void setMaxHeight(int px) {
        if (px == maxHeight) return;
        maxHeight = px;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (maxHeight != Integer.MAX_VALUE) {
            heightMeasureSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
