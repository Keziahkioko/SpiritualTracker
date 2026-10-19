package com.keziah.spiritualtracker;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Random;

/**
 * A short celebration: sparkles (little four-point stars and confetti in the app's violet,
 * magenta, lavender, gold and white) burst out of a point, drift down, spin and fade, drawn over
 * {@code host}. With animations turned off on the phone it just runs {@code then}.
 */
final class SparkleBurst {

    private static final int[] COLORS = {0xFF8E44C9, 0xFFA23FB5, 0xFFC9A0FF, 0xFFF7D774, 0xFFFFFFFF, 0xFF6A3CC8};
    private static final long DURATION = 950;

    private SparkleBurst() {}

    /** Bursts from the centre of {@code from} (a view inside {@code host}), then runs {@code then}. */
    static void play(View host, View from, @Nullable Runnable then) {
        host.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        float scale = Settings.Global.getFloat(host.getContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        if (scale == 0f) {
            if (then != null) then.run();
            return;
        }
        int[] h = new int[2], f = new int[2];
        host.getLocationInWindow(h);
        from.getLocationInWindow(f);
        float cx = f[0] - h[0] + from.getWidth() / 2f, cy = f[1] - h[1] + from.getHeight() / 2f;

        Burst burst = new Burst(cx, cy, host.getResources().getDisplayMetrics().density);
        burst.setBounds(0, 0, host.getWidth(), host.getHeight());
        host.getOverlay().add(burst);
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(DURATION);
        a.setInterpolator(new LinearInterpolator());
        a.addUpdateListener(v -> { burst.t = (float) v.getAnimatedValue(); burst.invalidateSelf(); });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                host.getOverlay().remove(burst);
                if (then != null) then.run();
            }
        });
        a.start();
    }

    private static final class Burst extends Drawable {
        private static final int COUNT = 42;
        final float cx, cy, dp;
        final float[] vx = new float[COUNT], vy = new float[COUNT], spin = new float[COUNT], size = new float[COUNT];
        final int[] color = new int[COUNT];
        final boolean[] star = new boolean[COUNT];
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path starPath = new Path();
        float t;

        Burst(float cx, float cy, float dp) {
            this.cx = cx;
            this.cy = cy;
            this.dp = dp;
            Random r = new Random();
            for (int i = 0; i < COUNT; i++) {
                // Mostly upwards and outwards, like a little fountain.
                double angle = Math.PI * (1.08 + r.nextDouble() * 0.84);
                float speed = (260 + r.nextFloat() * 340) * dp;
                vx[i] = (float) Math.cos(angle) * speed;
                vy[i] = (float) Math.sin(angle) * speed;
                spin[i] = (r.nextFloat() - 0.5f) * 720f;
                size[i] = (3.5f + r.nextFloat() * 4.5f) * dp;
                color[i] = COLORS[i % COLORS.length];
                star[i] = i % 3 == 0;
            }
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            float s = t * DURATION / 1000f;            // seconds
            float gravity = 520 * dp;
            int alpha = (int) (255 * Math.max(0f, 1f - t * t));
            for (int i = 0; i < COUNT; i++) {
                float x = cx + vx[i] * s;
                float y = cy + vy[i] * s + 0.5f * gravity * s * s;
                paint.setColor(color[i]);
                paint.setAlpha(alpha);
                canvas.save();
                canvas.translate(x, y);
                canvas.rotate(spin[i] * s);
                if (star[i]) {
                    drawStar(canvas, size[i] * 1.5f);
                } else {
                    canvas.drawRoundRect(-size[i], -size[i] * 0.45f, size[i], size[i] * 0.45f, dp, dp, paint);
                }
                canvas.restore();
            }
        }

        /** A four-point sparkle. */
        private void drawStar(Canvas canvas, float r) {
            float w = r * 0.28f;
            starPath.reset();
            starPath.moveTo(0, -r);
            starPath.quadTo(w * 0.3f, -w * 0.3f, r, 0);
            starPath.quadTo(w * 0.3f, w * 0.3f, 0, r);
            starPath.quadTo(-w * 0.3f, w * 0.3f, -r, 0);
            starPath.quadTo(-w * 0.3f, -w * 0.3f, 0, -r);
            starPath.close();
            canvas.drawPath(starPath, paint);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(@Nullable ColorFilter colorFilter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
