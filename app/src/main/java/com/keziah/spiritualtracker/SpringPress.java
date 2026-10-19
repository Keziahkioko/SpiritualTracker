package com.keziah.spiritualtracker;

import android.annotation.SuppressLint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import com.google.android.material.button.MaterialButton;

/**
 * A press you can see and feel: the view sinks a little while it's pressed (with a light haptic
 * tap) and springs back with a small bounce when it's let go. Like Android's own pressed state it
 * waits a moment before sinking, so a finger that starts scrolling a list doesn't press anything.
 * Taps and long presses still reach the view as before.
 */
final class SpringPress {

    private SpringPress() {}

    /** {@code depth} is how far it sinks: 0.95 for tiles and cards, 0.92 for buttons. */
    @SuppressLint("ClickableViewAccessibility")
    static void on(View v, float depth) {
        int slop = ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
        float[] down = new float[2];
        boolean[] sunk = {false};
        Runnable sink = () -> {
            if (!v.isEnabled()) return;
            sunk[0] = true;
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            v.animate().cancel();
            v.animate().scaleX(depth).scaleY(depth).setDuration(110)
                    .setInterpolator(new DecelerateInterpolator()).start();
        };
        Runnable release = () -> {
            v.removeCallbacks(sink);
            if (!sunk[0]) return;
            sunk[0] = false;
            v.animate().cancel();
            v.animate().scaleX(1f).scaleY(1f).setDuration(420)
                    .setInterpolator(new OvershootInterpolator(3.2f)).start();
        };
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getX();
                    down[1] = e.getY();
                    view.postDelayed(sink, ViewConfiguration.getTapTimeout());
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.hypot(e.getX() - down[0], e.getY() - down[1]) > slop) release.run();
                    break;
                case MotionEvent.ACTION_UP:
                    // A quick tap lifts before the delay: still give it the press and bounce.
                    if (!sunk[0] && view.isEnabled() && Math.hypot(e.getX() - down[0], e.getY() - down[1]) <= slop) {
                        view.removeCallbacks(sink);
                        sink.run();
                        view.postDelayed(release, 90);
                    } else {
                        release.run();
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    release.run();
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
