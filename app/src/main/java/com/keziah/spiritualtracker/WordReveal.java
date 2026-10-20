package com.keziah.spiritualtracker;

import android.animation.ValueAnimator;
import android.text.SpannableString;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.text.style.UpdateAppearance;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shows a verse word by word: each word fades in a moment after the one before, the whole verse
 * taking about a second however long it is. With animations turned off it simply appears.
 */
final class WordReveal {

    private WordReveal() {}

    private static final Pattern WORD = Pattern.compile("\\S+");
    private static final DecelerateInterpolator EASE = new DecelerateInterpolator();

    /** A word's see-through-ness, changed as the reveal runs. */
    private static final class Fade extends CharacterStyle implements UpdateAppearance {
        float alpha;
        @Override public void updateDrawState(TextPaint tp) {
            tp.setAlpha((int) (tp.getAlpha() * alpha));
        }
    }

    static void show(TextView view, CharSequence text) {
        SpannableString s = new SpannableString(text);
        List<Fade> fades = new ArrayList<>();
        Matcher m = WORD.matcher(s);
        while (m.find()) {
            Fade f = new Fade();
            s.setSpan(f, m.start(), m.end(), SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
            fades.add(f);
        }
        view.setText(s, TextView.BufferType.SPANNABLE);
        int n = fades.size();
        if (n == 0) return;
        long stagger = Math.max(8, Math.min(45, 1100 / n)), fade = 280;
        long total = stagger * (n - 1) + fade;
        ValueAnimator a = ValueAnimator.ofFloat(0f, total);
        a.setInterpolator(new android.view.animation.LinearInterpolator());
        a.setDuration(total);
        a.addUpdateListener(an -> {
            float t = (float) an.getAnimatedValue();
            for (int i = 0; i < n; i++) {
                float p = (t - i * stagger) / fade;
                fades.get(i).alpha = p <= 0 ? 0 : p >= 1 ? 1 : EASE.getInterpolation(p);
            }
            view.invalidate();
        });
        // If animations are off the animator jumps to the end; make sure every word shows.
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator an) {
                for (Fade f : fades) f.alpha = 1f;
                view.invalidate();
            }
        });
        a.start();
    }
}
