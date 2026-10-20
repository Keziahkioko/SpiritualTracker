package com.keziah.spiritualtracker;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.ReplacementSpan;
import android.view.View;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A verse shown with words hidden for the learn steps. Verse numbers and punctuation always
 * stay visible; a hidden word keeps its exact width as a lavender gap, and tapping a gap
 * (or a first letter) shows just that word.
 */
final class LearnText {

    static final int FULL = 0, EVERY_THIRD = 1, EVERY_OTHER = 2, FIRST_LETTERS = 3, ALL_HIDDEN = 4;

    private static int GAP      = Color.parseColor("#E6DDF7");
    private static int REVEALED = Color.parseColor("#6A1B9A");
    private static int INK      = Color.parseColor("#1A1A2E");
    private static int TILE     = Color.parseColor("#EDE6FA");

    /** Takes the colours from the palette, so the verse suits Light or Dark. */
    static void theme(android.content.Context c) {
        GAP = androidx.core.content.ContextCompat.getColor(c, R.color.st_stroke);
        REVEALED = androidx.core.content.ContextCompat.getColor(c, R.color.st_accent_strong);
        INK = androidx.core.content.ContextCompat.getColor(c, R.color.st_text);
        TILE = androidx.core.content.ContextCompat.getColor(c, R.color.st_soft);
    }
    private static final Pattern PARTS = Pattern.compile("^([^\\p{L}\\p{N}]*)(.*?)([^\\p{L}\\p{N}]*)$");
    private static final Pattern VERSE_NUMBER = Pattern.compile("^[\\[(]?\\d+[\\])]?[.:]?$");

    private static final class Word {
        String lead, core, trail;
        boolean newLine, verseNumber, revealed;
        int index; // position among the words that can be hidden
    }

    private final List<Word> words = new ArrayList<>();

    LearnText(String text) {
        int index = 0;
        boolean firstLine = true;
        for (String line : (text == null ? "" : text).split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String[] tokens = trimmed.split("\\s+");
            for (int i = 0; i < tokens.length; i++) {
                Word w = new Word();
                w.newLine = i == 0 && !firstLine;
                w.verseNumber = i == 0 && VERSE_NUMBER.matcher(tokens[i]).matches();
                Matcher m = PARTS.matcher(tokens[i]);
                if (!w.verseNumber && m.matches() && !m.group(2).isEmpty()) {
                    w.lead = m.group(1);
                    w.core = m.group(2);
                    w.trail = m.group(3);
                    w.index = index++;
                } else {
                    w.lead = tokens[i];
                    w.core = "";
                    w.trail = "";
                    w.verseNumber = true; // nothing to hide (a number or stray punctuation)
                }
                words.add(w);
            }
            firstLine = false;
        }
    }

    /** How many words have been tapped open (a review shows this as "peeked"). */
    int peeked() {
        int n = 0;
        for (Word w : words) if (w.revealed) n++;
        return n;
    }

    /** Words that can be hidden (verse numbers and punctuation don't count). */
    int wordCount() {
        int n = 0;
        for (Word w : words) if (!w.verseNumber && !w.core.isEmpty()) n++;
        return n;
    }

    /** Hides everything that was tapped open, ready for the next step. */
    void hideAgain() {
        for (Word w : words) w.revealed = false;
    }

    /** The text for a step; {@code onTap} runs after a gap is tapped open, to redraw. */
    CharSequence render(int mode, Runnable onTap) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        for (int i = 0; i < words.size(); i++) {
            Word w = words.get(i);
            if (i > 0) out.append(w.newLine ? "\n" : " ");
            out.append(w.lead);
            if (w.verseNumber || w.core.isEmpty()) continue;

            boolean hidden = mode == FIRST_LETTERS || mode == ALL_HIDDEN
                    || (mode == EVERY_THIRD && w.index % 3 == 2)
                    || (mode == EVERY_OTHER && w.index % 2 == 1);
            int start = out.length();
            if (!hidden) {
                out.append(w.core);
            } else if (w.revealed) {
                out.append(w.core);
                out.setSpan(new ForegroundColorSpan(REVEALED), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (mode == FIRST_LETTERS) {
                // The first letter on a small tile: easy to tap, and each word stands apart.
                out.append(w.core.substring(0, w.core.offsetByCodePoints(0, 1)));
                out.setSpan(new GapSpan(true), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(tapToShow(w, onTap, INK), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                // A rounded lavender pill exactly as wide as the hidden word.
                out.append(w.core);
                out.setSpan(new GapSpan(false), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(tapToShow(w, onTap, Color.TRANSPARENT), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            out.append(w.trail);
        }
        return out;
    }

    /**
     * A rounded lavender pill on the text line: as wide as the hidden word (no text), or a
     * finger-sized tile with a first letter in the middle ({@code letter}).
     */
    private static final class GapSpan extends ReplacementSpan {
        private final boolean letter;

        GapSpan(boolean letter) { this.letter = letter; }

        private float width(Paint paint, CharSequence text, int start, int end) {
            float w = paint.measureText(text, start, end);
            return letter ? Math.max(w + paint.getTextSize() * 0.6f, paint.getTextSize() * 1.5f) : w;
        }

        @Override
        public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) {
                Paint.FontMetricsInt line = paint.getFontMetricsInt();
                fm.ascent = line.ascent;
                fm.descent = line.descent;
                fm.top = line.top;
                fm.bottom = line.bottom;
            }
            return Math.round(width(paint, text, start, end));
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end,
                         float x, int top, int y, int bottom, @NonNull Paint paint) {
            Paint.FontMetrics m = paint.getFontMetrics();
            float width = width(paint, text, start, end);
            float radius = paint.getTextSize() * 0.3f;
            int color = paint.getColor();
            paint.setColor(letter ? TILE : GAP);
            canvas.drawRoundRect(new RectF(x, y + m.ascent * 0.8f, x + width, y + m.descent * 0.9f),
                    radius, radius, paint);
            if (letter) {
                paint.setColor(INK);
                canvas.drawText(text, start, end, x + (width - paint.measureText(text, start, end)) / 2f, y, paint);
            }
            paint.setColor(color);
        }
    }

    private static ClickableSpan tapToShow(Word w, Runnable onTap, int color) {
        return new ClickableSpan() {
            @Override public void onClick(@NonNull View view) {
                w.revealed = true;
                onTap.run();
            }
            @Override public void updateDrawState(@NonNull TextPaint ds) {
                ds.setColor(color);
                ds.setUnderlineText(false);
            }
        };
    }
}
