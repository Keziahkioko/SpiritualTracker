package com.keziah.spiritualtracker;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldPath;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds a person's full day-by-day history. The primary source is users/{uid}/days,
 * merged with data the app stored before that log existed (shared daily_readings docs,
 * journal entries and memory-verse review dates) so older history still shows.
 */
public final class HistoryRepository {

    /** Everything one person did on one day. */
    public static class DayRecord {
        public final String date;
        public boolean bible, prayed, prayedForPartner, journal, memorize;
        public String bibleNote;
        public final List<String> journalTitles = new ArrayList<>();
        public final List<String> memorizeRefs  = new ArrayList<>();

        DayRecord(String date) { this.date = date; }

        public int doneCount() {
            return (bible ? 1 : 0) + (prayed ? 1 : 0) + (journal ? 1 : 0) + (memorize ? 1 : 0);
        }
    }

    public interface Callback {
        void onLoaded(TreeMap<String, DayRecord> days);
        void onError(Exception e);
    }

    private HistoryRepository() {}

    /** Loads all history for {@code uid} between two yyyy-MM-dd keys (inclusive). */
    public static void load(String uid, String fromKey, String toKey, Callback cb) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();

        Task<QuerySnapshot> daysTask = db.collection("users").document(uid).collection("days")
                .orderBy(FieldPath.documentId()).startAt(fromKey).endAt(toKey).get();
        Task<QuerySnapshot> legacyBibleTask = db.collection("daily_readings")
                .orderBy(FieldPath.documentId()).startAt(fromKey).endAt(toKey).get();
        Task<QuerySnapshot> journalTask = db.collection("journal_entries")
                .whereEqualTo("userId", uid).get();
        Task<QuerySnapshot> versesTask = db.collection("users").document(uid)
                .collection("memory_verses").get();
        Task<DocumentSnapshot> userTask = db.collection("users").document(uid).get();

        // Legacy sources may be unreadable once rules are tightened; never let them block the log.
        Tasks.whenAllComplete(daysTask, legacyBibleTask, journalTask, versesTask, userTask)
                .addOnCompleteListener(all -> {
                    if (!daysTask.isSuccessful()) {
                        cb.onError(daysTask.getException());
                        return;
                    }
                    TreeMap<String, DayRecord> days = new TreeMap<>();

                    if (legacyBibleTask.isSuccessful()) {
                        for (DocumentSnapshot d : legacyBibleTask.getResult()) {
                            if (Boolean.TRUE.equals(d.getBoolean(uid))) {
                                DayRecord r = get(days, d.getId());
                                r.bible = true;
                                r.bibleNote = d.getString(uid + "_verse");
                            }
                        }
                    }

                    if (journalTask.isSuccessful()) {
                        for (DocumentSnapshot d : journalTask.getResult()) {
                            if ("draft".equals(d.getString("status"))) continue;
                            Timestamp ts = d.getTimestamp("date");
                            if (ts == null) continue;
                            String key = ActivityLog.dayKey(ts.toDate());
                            if (!inRange(key, fromKey, toKey)) continue;
                            DayRecord r = get(days, key);
                            r.journal = true;
                            addUnique(r.journalTitles, d.getString("title"));
                        }
                    }

                    if (versesTask.isSuccessful()) {
                        for (DocumentSnapshot d : versesTask.getResult()) {
                            // A verse saved today also stores today's date, so only count real reviews.
                            Long idx = d.getLong("intervalIndex");
                            String key = d.getString("lastReviewedDate");
                            if (idx == null || idx <= 0 || key == null || !inRange(key, fromKey, toKey)) continue;
                            DayRecord r = get(days, key);
                            r.memorize = true;
                            addUnique(r.memorizeRefs, d.getString("reference"));
                        }
                    }

                    if (userTask.isSuccessful() && userTask.getResult() != null) {
                        DocumentSnapshot u = userTask.getResult();
                        String prayed = u.getString("prayedTodayDate");
                        if (prayed != null && !prayed.isEmpty() && inRange(prayed, fromKey, toKey)) get(days, prayed).prayed = true;
                        String forPartner = u.getString("prayedForPartnerDate");
                        if (forPartner != null && !forPartner.isEmpty() && inRange(forPartner, fromKey, toKey)) get(days, forPartner).prayedForPartner = true;
                        String memo = u.getString("memorizedTodayDate");
                        if (memo != null && !memo.isEmpty() && inRange(memo, fromKey, toKey)) get(days, memo).memorize = true;
                    }

                    // The day log is authoritative, so apply it last.
                    for (DocumentSnapshot d : daysTask.getResult()) {
                        DayRecord r = get(days, d.getId());
                        if (d.contains("bible"))            r.bible = Boolean.TRUE.equals(d.getBoolean("bible"));
                        if (d.contains("prayed"))           r.prayed = Boolean.TRUE.equals(d.getBoolean("prayed"));
                        if (d.contains("prayedForPartner")) r.prayedForPartner = Boolean.TRUE.equals(d.getBoolean("prayedForPartner"));
                        if (Boolean.TRUE.equals(d.getBoolean("journal")))  r.journal = true;
                        if (Boolean.TRUE.equals(d.getBoolean("memorize"))) r.memorize = true;
                        String note = d.getString("bibleNote");
                        if (note != null && !note.isEmpty()) r.bibleNote = note;
                        addAllUnique(r.journalTitles, d.get("journalTitles"));
                        addAllUnique(r.memorizeRefs, d.get("memorizeRefs"));
                    }

                    cb.onLoaded(days);
                });
    }

    private static DayRecord get(Map<String, DayRecord> days, String key) {
        DayRecord r = days.get(key);
        if (r == null) {
            r = new DayRecord(key);
            days.put(key, r);
        }
        return r;
    }

    private static boolean inRange(String key, String from, String to) {
        return key.compareTo(from) >= 0 && key.compareTo(to) <= 0;
    }

    private static void addUnique(List<String> list, String value) {
        if (value != null && !value.isEmpty() && !list.contains(value)) list.add(value);
    }

    private static void addAllUnique(List<String> list, Object values) {
        if (!(values instanceof List)) return;
        for (Object o : (List<?>) values) if (o instanceof String) addUnique(list, (String) o);
    }
}
