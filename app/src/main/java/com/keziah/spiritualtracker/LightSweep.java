package com.keziah.spiritualtracker;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A soft band of light that glides diagonally across a tile every few seconds, like light moving
 * over glass. Drawn on the tile's foreground, kept inside its rounded face. Off when the phone's
 * animations are off.
 */
final class LightSweep {

    private static final long CYCLE = 4200;     // one pass, then a rest
    private static final float PASS = 0.38f;    // share of the cycle the band is moving

    private LightSweep() {}

    /**
     * Starts the sweep on {@code face}, {@code delay} ms after the others so they pass one by one.
     * {@code bottomInsetDp} leaves out the tile's raised edge. Returns the animator to stop later,
     * or null when animations are off.
     */
    @Nullable
    static ValueAnimator start(View face, long delay, float radiusDp, float bottomInsetDp) {
        float scale = Settings.Global.getFloat(face.getContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        if (scale == 0f) {
            face.setForeground(null);
            return null;
        }
        float dp = face.getResources().getDisplayMetrics().density;
        Band band = new Band(radiusDp * dp, bottomInsetDp * dp);
        face.setForeground(band);
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f).setDuration(CYCLE);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setStartDelay(delay);
        a.addUpdateListener(v -> {
            float t = (float) v.getAnimatedValue();
            band.progress = t < PASS ? new AccelerateDecelerateInterpolator().getInterpolation(t / PASS) : -1f;
            band.invalidateSelf();
        });
        a.start();
        return a;
    }

    private static final class Band extends Drawable {
        final float radius, bottomInset;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path clip = new Path();
        final RectF rect = new RectF();
        float progress = -1f;

        Band(float radius, float bottomInset) {
            this.radius = radius;
            this.bottomInset = bottomInset;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            if (progress < 0f) return;
            float w = getBounds().width(), h = getBounds().height() - bottomInset;
            if (w <= 0 || h <= 0) return;
            float bandWidth = w * 0.45f;
            // From fully off the left to fully off the right, slanted.
            float x = -bandWidth - h * 0.35f + progress * (w + bandWidth * 2 + h * 0.35f);
            paint.setShader(new LinearGradient(x, 0, x + bandWidth, 0,
                    new int[]{0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            canvas.save();
            rect.set(0, 0, w, h);
            clip.reset();
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
            canvas.clipPath(clip);
            canvas.skew(-0.35f, 0f);
            canvas.translate(h * 0.35f * 0.5f, 0);
            canvas.drawRect(x, 0, x + bandWidth, h, paint);
            canvas.restore();
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(@Nullable ColorFilter colorFilter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
