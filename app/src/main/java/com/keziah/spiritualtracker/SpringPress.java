package com.keziah.spiritualtracker;

import android.annotation.SuppressLint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import com.google.android.material.button.MaterialButton;

/**
 * A press you can see and feel: the view sinks a little as the finger goes down (with a light
 * haptic tap) and springs back with a small bounce when it lifts. Taps and long presses still
 * reach the view as before.
 */
final class SpringPress {

    private SpringPress() {}

    /** {@code depth} is how far it sinks: 0.95 for tiles and cards, 0.92 for buttons. */
    @SuppressLint("ClickableViewAccessibility")
    static void on(View v, float depth) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!view.isEnabled()) break;
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    view.animate().cancel();
                    view.animate().scaleX(depth).scaleY(depth).setDuration(110)
                            .setInterpolator(new DecelerateInterpolator()).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().cancel();
                    view.animate().scaleX(1f).scaleY(1f).setDuration(420)
                            .setInterpolator(new OvershootInterpolator(3.2f)).start();
                    break;
                default:
                    break;
            }
            return false; // let the view handle the click itself
        });
    }

    /** Every button in {@code root}, however deep. */
    static void onAllButtons(View root) {
        if (root instanceof MaterialButton) {
            on(root, 0.92f);
        } else if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) onAllButtons(g.getChildAt(i));
        }
    }
}
