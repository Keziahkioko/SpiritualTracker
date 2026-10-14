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
    /** A name you chose for your partner; only stored on this phone and never shared. */
    private static final String NICKNAME = "partner_nickname";
    private static final String GENERIC = "Partner";
    private static final Pattern WORD = Pattern.compile("\\b(?:(?:[Yy]our|[Mm]y) partner|PARTNER|[Pp]artner)\\b");

    private static String lastNickname;

    private PartnerName() {}

    /** What to call the partner: your nickname if you set one, else their own name, else "Partner". */
    public static String get(Context context) {
        if (context == null) return GENERIC;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String nick = prefs.getString(NICKNAME, null);
        if (nick != null && !nick.trim().isEmpty()) return nick;
        String name = prefs.getString(KEY, null);
        return name == null || name.trim().isEmpty() ? GENERIC : name;
    }

    /** Their own name, ignoring any nickname. */
    public static String ownName(Context context) {
        if (context == null) return GENERIC;
        String name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null);
        return name == null || name.trim().isEmpty() ? GENERIC : name;
    }

    public static boolean hasNickname(Context context) {
        if (context == null) return false;
        String nick = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(NICKNAME, null);
        return nick != null && !nick.trim().isEmpty();
    }

    /** Pass null or blank to go back to their own name. */
    public static void setNickname(Context context, String nickname) {
        if (context == null) return;
        SharedPreferences.Editor e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (nickname == null || nickname.trim().isEmpty()) e.remove(NICKNAME);
        else e.putString(NICKNAME, nickname.trim());
        lastNickname = nickname == null ? null : nickname.trim();
        e.apply();
    }

    /** Private copy of the nickname under my account, so signing out and back in keeps it. */
    private static com.google.firebase.firestore.DocumentReference cloudRef() {
        com.google.firebase.auth.FirebaseUser u = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) return null;
        return com.google.firebase.firestore.FirebaseFirestore.getInstance()
                .collection("users").document(u.getUid()).collection("private").document("contacts");
    }

    /** Saves the current nickname to my account, tied to this partner. */
    public static void backup(String partnerId) {
        com.google.firebase.firestore.DocumentReference ref = cloudRef();
        if (ref == null || partnerId == null) return;
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("partnerId", partnerId);
        data.put("partnerNickname", lastNickname);
        ref.set(data).addOnFailureListener(e -> android.util.Log.w("PartnerName", "Nickname backup failed", e));
    }

    public interface Restored { void done(boolean restored); }

    /** Brings back a nickname saved under my account for this same partner. */
    public static void restore(Context context, String partnerId, Restored callback) {
        com.google.firebase.firestore.DocumentReference ref = cloudRef();
        if (ref == null || partnerId == null) { callback.done(false); return; }
        Context app = context.getApplicationContext();
        ref.get().addOnCompleteListener(task -> {
            String nick = null;
            if (task.isSuccessful() && task.getResult() != null
                    && partnerId.equals(task.getResult().getString("partnerId"))) {
                nick = task.getResult().getString("partnerNickname");
            }
            if (nick != null && !nick.trim().isEmpty()) {
                setNickname(app, nick);
                callback.done(true);
            } else {
                callback.done(false);
            }
        });
    }

    /** Name for a partner profile just loaded from the database, respecting your nickname. */
    public static String display(Context context, com.google.firebase.firestore.DocumentSnapshot partnerDoc) {
        return hasNickname(context) ? get(context) : UserProfile.partnerName(partnerDoc);
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
