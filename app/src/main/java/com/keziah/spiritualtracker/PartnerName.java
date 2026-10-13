package com.keziah.spiritualtracker;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Remembers the linked partner's display name on this phone and swaps the generic word
 * "Partner" for it in labels, so screens can show "Sirokoth's verses" instead of "Partner's verses".
 */
public final class PartnerName {

    private static final String PREFS = "partner_profile";
    private static final String KEY = "partner_name";
    private static final String GENERIC = "Partner";
    private static final Pattern WORD = Pattern.compile("\\b(?:(?:[Yy]our|[Mm]y) partner|PARTNER|[Pp]artner)\\b");

    private PartnerName() {}

    /** The partner's name, or "Partner" when it isn't known yet. */
    public static String get(Context context) {
        if (context == null) return GENERIC;
        String name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null);
        return name == null || name.trim().isEmpty() ? GENERIC : name;
    }

    public static boolean isKnown(Context context) {
        return !GENERIC.equals(get(context));
    }

    public static void save(Context context, String name) {
        if (context == null) return;
        SharedPreferences.Editor e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (name == null || name.trim().isEmpty() || GENERIC.equals(name.trim())) e.remove(KEY);
        else e.putString(KEY, name.trim());
        e.apply();
    }

    /** Replaces "Partner", "your partner", "PARTNER" etc. in a piece of text with the name. */
    public static String personalize(Context context, String text) {
        if (text == null || !isKnown(context)) return text;
        // Section titles and pre-linking prompts read better with the generic word.
        if (text.contains("Accountability") || text.contains("Link your partner")
                || text.contains("partner code") || text.contains("partner's code")) return text;
        String name = get(context);
        Matcher m = WORD.matcher(text);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String hit = m.group();
            m.appendReplacement(out, Matcher.quoteReplacement(hit.equals("PARTNER") ? name.toUpperCase() : name));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Personalizes every label under {@code root} (text fields the user types in are left alone). */
    public static void apply(View root) {
        if (root == null || !isKnown(root.getContext())) return;
        if (root instanceof TextView && !(root instanceof EditText)) {
            TextView tv = (TextView) root;
            CharSequence text = tv.getText();
            if (text != null && text.length() > 0 && !(text instanceof android.text.Spanned)) {
                String updated = personalize(root.getContext(), text.toString());
                if (!updated.contentEquals(text)) tv.setText(updated);
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) apply(g.getChildAt(i));
        }
    }
}
