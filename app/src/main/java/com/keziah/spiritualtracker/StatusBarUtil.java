package com.keziah.spiritualtracker;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/** Lets a screen's purple header run up behind the status bar (like the dashboard photo). */
public final class StatusBarUtil {

    private StatusBarUtil() {}

    /** For headers inside a screen that already draws behind the status bar (e.g. Prayer tabs). */
    public static void padTop(View header) {
        final int baseTop = header.getPaddingTop();
        Runnable apply = () -> {
            WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(header);
            int top = insets != null ? insets.getInsets(WindowInsetsCompat.Type.statusBars()).top : 0;
            header.setPadding(header.getPaddingLeft(), baseTop + top,
                    header.getPaddingRight(), header.getPaddingBottom());
        };
        if (header.isAttachedToWindow()) apply.run();
        header.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { v.post(apply); }
            @Override public void onViewDetachedFromWindow(View v) {}
        });
    }

    /**
     * @param header  background view that should grow by the status-bar height
     * @param content view whose top padding should grow by the status-bar height
     */
    public static void drawBehind(Activity activity, View header, View content) {
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        activity.getWindow().setStatusBarColor(Color.TRANSPARENT);
        WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView())
                .setAppearanceLightStatusBars(false);

        ViewGroup.LayoutParams lp = header.getLayoutParams();
        final int headerHeight = lp.height;
        final int contentTop = content.getPaddingTop();
        final int contentBottom = content.getPaddingBottom();

        View root = activity.findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            if (headerHeight > 0) {
                header.getLayoutParams().height = headerHeight + bars.top;
                header.requestLayout();
            }
            content.setPadding(content.getPaddingLeft(), contentTop + bars.top,
                    content.getPaddingRight(), contentBottom + bars.bottom);
            return insets;
        });
    }
}
