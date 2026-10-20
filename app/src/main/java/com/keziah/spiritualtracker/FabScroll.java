package com.keziah.spiritualtracker;

import android.view.View;

import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

/**
 * The floating add button gets out of the way: it shrinks to a round + while you scroll down
 * through the list, and shows its label again when you scroll back up or reach the top.
 */
final class FabScroll {

    private FabScroll() {}

    static void attach(ExtendedFloatingActionButton fab, View scrolling) {
        if (fab == null || scrolling == null) return;
        if (scrolling instanceof RecyclerView) {
            ((RecyclerView) scrolling).addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                    react(fab, dy, !rv.canScrollVertically(-1));
                }
            });
        } else if (scrolling instanceof NestedScrollView) {
            ((NestedScrollView) scrolling).setOnScrollChangeListener(
                    (NestedScrollView.OnScrollChangeListener) (v, x, y, ox, oy) -> react(fab, y - oy, y == 0));
        }
    }

    private static void react(ExtendedFloatingActionButton fab, int dy, boolean atTop) {
        if (atTop || dy < 0) { if (!fab.isExtended()) fab.extend(); }
        else if (dy > 0 && fab.isExtended()) fab.shrink();
    }
}
