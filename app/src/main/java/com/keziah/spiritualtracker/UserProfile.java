package com.keziah.spiritualtracker;

import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;

/** Resolves a person's display name so we never show or send "null". */
public final class UserProfile {

    public static final String FALLBACK_NAME = "Your partner";

    private UserProfile() {}

    /** Name from the user doc, then the Firebase account, then the email prefix. */
    public static String displayName(@Nullable DocumentSnapshot userDoc) {
        String name = userDoc != null ? userDoc.getString("name") : null;
        if (!isBlank(name)) return name.trim();

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        boolean isMe = user != null && (userDoc == null || user.getUid().equals(userDoc.getId()));
        if (isMe) {
            if (!isBlank(user.getDisplayName())) return user.getDisplayName().trim();
            String email = user.getEmail();
            if (!isBlank(email) && email.contains("@")) return capitalize(email.substring(0, email.indexOf('@')));
        }
        return FALLBACK_NAME;
    }

    /** Name for someone else's doc (partner); never falls back to my own account. */
    public static String partnerName(@Nullable DocumentSnapshot partnerDoc) {
        String name = partnerDoc != null ? partnerDoc.getString("name") : null;
        return isBlank(name) ? "Partner" : name.trim();
    }

    public static boolean hasName(@Nullable DocumentSnapshot userDoc) {
        return userDoc != null && userDoc.exists() && !isBlank(userDoc.getString("name"));
    }

    public static String suggestedName() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return "";
        if (!isBlank(user.getDisplayName())) return user.getDisplayName().trim();
        String email = user.getEmail();
        return (!isBlank(email) && email.contains("@")) ? capitalize(email.substring(0, email.indexOf('@'))) : "";
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.trim().isEmpty() || "null".equalsIgnoreCase(s.trim());
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
