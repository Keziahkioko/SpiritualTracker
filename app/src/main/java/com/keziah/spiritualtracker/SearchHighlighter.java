package com.keziah.spiritualtracker;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import java.util.Locale;

/** Marks every occurrence of a search term in a piece of text (case-insensitive). */
public final class SearchHighlighter {

    private static final int HIGHLIGHT_BG = 0xFFFFE8A3;   // soft amber marker
    private static final int HIGHLIGHT_FG = 0xFF4A148C;   // deep purple text

    private SearchHighlighter() {}

    public static CharSequence highlight(String text, String query) {
        if (text == null) return "";
        if (query == null || query.trim().isEmpty()) return text;
        String q = query.trim().toLowerCase(Locale.getDefault());
        String lower = text.toLowerCase(Locale.getDefault());
        SpannableString out = new SpannableString(text);
        int from = 0;
        while (true) {
            int i = lower.indexOf(q, from);
            if (i < 0) break;
            int end = i + q.length();
            out.setSpan(new BackgroundColorSpan(HIGHLIGHT_BG), i, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new ForegroundColorSpan(HIGHLIGHT_FG), i, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new StyleSpan(Typeface.BOLD), i, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            from = end;
        }
        return out;
    }

    /** Index of the first match, or -1. */
    public static int firstMatch(String text, String query) {
        if (text == null || query == null || query.trim().isEmpty()) return -1;
        return text.toLowerCase(Locale.getDefault()).indexOf(query.trim().toLowerCase(Locale.getDefault()));
    }

    /**
     * If the first match sits beyond what a short preview would show, returns the text starting
     * shortly before it ("…words around the match…"); otherwise the text unchanged.
     */
    public static String snippetAround(String text, String query, int visibleChars) {
        int i = firstMatch(text, query);
        if (i < 0 || i < visibleChars) return text;
        int start = Math.max(0, i - 40);
        int space = text.lastIndexOf(' ', start);
        int nl = text.lastIndexOf('\n', i);
        if (nl >= start) start = nl + 1;            // start at the line that contains the match
        else if (space > 0 && i - space < 60) start = space + 1;
        return "…" + text.substring(start).replace('\n', ' ');
    }
}
