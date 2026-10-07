package com.keziah.spiritualtracker;

import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Permanent day-by-day record of each person's disciplines, stored at
 * users/{uid}/days/{yyyy-MM-dd}. The dashboard ticks and the progress history
 * both read from here, so nothing disappears when the week rolls over.
 */
public final class ActivityLog {

    public static final String DATE_FMT = "yyyy-MM-dd";

    private ActivityLog() {}

    public static String dayKey(Date date) {
        return new SimpleDateFormat(DATE_FMT, Locale.US).format(date);
    }

    public static String today() {
        return dayKey(new Date());
    }

    public static DocumentReference dayRef(String uid, String dayKey) {
        return FirebaseFirestore.getInstance()
                .collection("users").document(uid)
                .collection("days").document(dayKey);
    }

    public static void setBible(String uid, boolean done, String note) {
        Map<String, Object> data = base();
        data.put("bible", done);
        if (note != null) data.put("bibleNote", note.trim());
        write(uid, data);
    }

    public static void setBibleNote(String uid, String note) {
        Map<String, Object> data = base();
        data.put("bibleNote", note == null ? "" : note.trim());
        write(uid, data);
    }

    public static void setPrayed(String uid, boolean done) {
        Map<String, Object> data = base();
        data.put("prayed", done);
        write(uid, data);
    }

    public static void setPrayedForPartner(String uid, boolean done) {
        Map<String, Object> data = base();
        data.put("prayedForPartner", done);
        write(uid, data);
    }

    public static void addJournal(String uid, String title) {
        Map<String, Object> data = base();
        data.put("journal", true);
        data.put("journalTitles", FieldValue.arrayUnion(title == null ? "Untitled" : title));
        write(uid, data);
    }

    public static void addMemorize(String uid, String reference) {
        Map<String, Object> data = base();
        data.put("memorize", true);
        if (reference != null) data.put("memorizeRefs", FieldValue.arrayUnion(reference));
        write(uid, data);
    }

    private static Map<String, Object> base() {
        Map<String, Object> data = new HashMap<>();
        data.put("date", today());
        data.put("updatedAt", FieldValue.serverTimestamp());
        return data;
    }

    private static void write(String uid, Map<String, Object> data) {
        if (uid == null || uid.isEmpty()) return;
        dayRef(uid, (String) data.get("date")).set(data, SetOptions.merge());
    }
}
