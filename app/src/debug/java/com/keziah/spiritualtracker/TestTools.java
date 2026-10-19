package com.keziah.spiritualtracker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.WriteBatch;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Debug builds only: lets testing on a real phone use a throwaway passage whose name starts with
 * "TEST", driven from adb (am broadcast -a com.keziah.spiritualtracker.test.ACTION):
 *   SNAPSHOT  remembers whether today's Memorize was already done, before testing starts
 *   SHIFT     moves the TEST verses' dates back (--ei days N, default 1), as if days had passed
 *   CLEANUP   deletes the TEST verses and takes them out of today's history, restoring the snapshot
 *   PRAYER    shows the 6:30 prayer reminder now, as it would be on the next given weekday
 *             (--ei weekday 1-7, Sunday = 1); reads only, nothing is saved
 *   PUSHCHECK checks why partner notifications might not arrive: this phone's push token against
 *             both profiles, notification permission and background limits; reads only, sends nothing
 * Nothing else is touched. Results go to logcat under "TestTools".
 */
public class TestTools extends BroadcastReceiver {

    private static final String TAG = "TestTools";
    static final String PREFIX = "TEST";

    @Override
    public void onReceive(Context context, Intent intent) {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null || intent.getAction() == null) { Log.w(TAG, "not signed in"); return; }
        PendingResult pending = goAsync();
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        SharedPreferences prefs = context.getSharedPreferences("test_tools", Context.MODE_PRIVATE);
        String today = ActivityLog.today();
        String action = intent.getAction().substring(intent.getAction().lastIndexOf('.') + 1);

        switch (action) {
            case "SNAPSHOT":
                db.collection("users").document(uid).get().addOnCompleteListener(u ->
                        ActivityLog.dayRef(uid, today).get().addOnCompleteListener(d -> {
                            DocumentSnapshot day = d.isSuccessful() ? d.getResult() : null;
                            DocumentSnapshot user = u.isSuccessful() ? u.getResult() : null;
                            boolean done = day != null && Boolean.TRUE.equals(day.getBoolean("memorize"));
                            String mtd = user != null ? user.getString("memorizedTodayDate") : null;
                            prefs.edit().putString("day", today).putBoolean("memorize", done)
                                    .putString("memorizedTodayDate", mtd).apply();
                            Log.i(TAG, "SNAPSHOT day=" + today + " memorize=" + done + " memorizedTodayDate=" + mtd);
                            pending.finish();
                        }));
                break;

            case "SHIFT":
                int days = intent.getIntExtra("days", 1);
                testVerses(db, uid, docs -> {
                    WriteBatch batch = db.batch();
                    for (DocumentSnapshot doc : docs) {
                        Map<String, Object> u = new HashMap<>();
                        Timestamp next = doc.getTimestamp("nextReviewDate");
                        boolean locked = next != null
                                && next.toDate().getTime() - System.currentTimeMillis() > 365L * 86400000L;
                        if (next != null && !locked) u.put("nextReviewDate", new Timestamp(back(next.toDate(), days)));
                        Timestamp together = doc.getTimestamp("togetherNextDate");
                        if (together != null) u.put("togetherNextDate", new Timestamp(back(together.toDate(), days)));
                        Timestamp added = doc.getTimestamp("addedAt");
                        if (added != null) u.put("addedAt", new Timestamp(back(added.toDate(), days)));
                        String last = doc.getString("lastReviewedDate");
                        if (last != null) {
                            try {
                                Date d = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(last);
                                if (d != null) u.put("lastReviewedDate", ActivityLog.dayKey(back(d, days)));
                            } catch (Exception ignored) { }
                        }
                        if (!u.isEmpty()) batch.update(doc.getReference(), u);
                    }
                    batch.commit().addOnCompleteListener(t -> {
                        Log.i(TAG, "SHIFT " + days + " day(s) on " + docs.size() + " TEST verse(s): " + t.isSuccessful());
                        pending.finish();
                    });
                });
                break;

            case "CLEANUP":
                testVerses(db, uid, docs -> {
                    WriteBatch batch = db.batch();
                    List<String> refs = new ArrayList<>();
                    for (DocumentSnapshot doc : docs) {
                        batch.delete(doc.getReference());
                        if (doc.getString("reference") != null) refs.add(doc.getString("reference"));
                        if (doc.getString("passageId") != null) refs.add(doc.getString("passageId"));
                    }
                    String day = prefs.getString("day", null);
                    if (today.equals(day)) {
                        Map<String, Object> restore = new HashMap<>();
                        restore.put("memorize", prefs.getBoolean("memorize", false));
                        if (!refs.isEmpty()) restore.put("memorizeRefs", FieldValue.arrayRemove(refs.toArray()));
                        batch.set(ActivityLog.dayRef(uid, today), restore, com.google.firebase.firestore.SetOptions.merge());
                        String mtd = prefs.getString("memorizedTodayDate", null);
                        batch.update(db.collection("users").document(uid), "memorizedTodayDate",
                                mtd != null ? mtd : FieldValue.delete());
                    }
                    batch.commit().addOnCompleteListener(t -> {
                        Log.i(TAG, "CLEANUP removed " + docs.size() + " TEST verse(s), history restored="
                                + today.equals(day) + ": " + t.isSuccessful()
                                + (t.getException() != null ? " " + t.getException() : ""));
                        pending.finish();
                    });
                });
                break;

            case "PRAYER":
                int weekday = intent.getIntExtra("weekday", Calendar.getInstance().get(Calendar.DAY_OF_WEEK));
                Calendar day = Calendar.getInstance();
                day.add(Calendar.DAY_OF_YEAR, 1);
                while (day.get(Calendar.DAY_OF_WEEK) != weekday) day.add(Calendar.DAY_OF_YEAR, 1);
                new Thread(() -> {
                    try {
                        int n = PrayerReminderWorker.remind(context.getApplicationContext(), uid, day);
                        Log.i(TAG, "PRAYER as on " + ActivityLog.dayKey(day.getTime()) + ": " + n + " request(s)");
                    } catch (Exception e) {
                        Log.w(TAG, "PRAYER failed", e);
                    }
                    pending.finish();
                }).start();
                break;

            case "PUSHCHECK":
                boolean allowed = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled();
                android.os.PowerManager pm = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
                boolean unrestricted = pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
                com.google.firebase.messaging.FirebaseMessaging.getInstance().getToken().addOnCompleteListener(tk -> {
                    String device = tk.isSuccessful() ? tk.getResult() : null;
                    db.collection("users").document(uid).get().addOnCompleteListener(u -> {
                        DocumentSnapshot me = u.isSuccessful() ? u.getResult() : null;
                        String mine = me != null ? me.getString("fcmToken") : null;
                        String pid = me != null ? me.getString("partnerId") : null;
                        String head = "PUSHCHECK notificationsAllowed=" + allowed + " backgroundUnrestricted=" + unrestricted
                                + " deviceToken=" + tail(device) + " myProfileToken=" + tail(mine)
                                + " myProfileMatchesDevice=" + (device != null && device.equals(mine));
                        if (pid == null || pid.isEmpty()) {
                            Log.i(TAG, head + " partner=none");
                            pending.finish();
                            return;
                        }
                        db.collection("users").document(pid).get().addOnCompleteListener(p -> {
                            DocumentSnapshot partner = p.isSuccessful() ? p.getResult() : null;
                            String theirs = partner != null ? partner.getString("fcmToken") : null;
                            Log.i(TAG, head + " partnerLinkedBack=" + (partner != null && uid.equals(partner.getString("partnerId")))
                                    + " partnerToken=" + tail(theirs)
                                    + " partnerTokenIsThisPhone=" + (theirs != null && theirs.equals(device))
                                    + (p.getException() != null ? " error=" + p.getException() : ""));
                            pending.finish();
                        });
                    });
                });
                break;

            default:
                pending.finish();
        }
    }

    /** Enough of a token to compare, without printing it whole. */
    private static String tail(String token) {
        return token == null ? "none" : "…" + token.substring(Math.max(0, token.length() - 8));
    }

    private interface Docs { void got(List<DocumentSnapshot> docs); }

    private static void testVerses(FirebaseFirestore db, String uid, Docs then) {
        db.collection("users").document(uid).collection("memory_verses").get().addOnCompleteListener(t -> {
            List<DocumentSnapshot> out = new ArrayList<>();
            if (t.isSuccessful()) for (DocumentSnapshot d : t.getResult()) {
                String ref = d.getString("reference");
                if (ref != null && ref.startsWith(PREFIX)) out.add(d);
            }
            then.got(out);
        });
    }

    private static Date back(Date date, int days) {
        Calendar c = Calendar.getInstance();
        c.setTime(date);
        c.add(Calendar.DAY_OF_YEAR, -days);
        return c.getTime();
    }
}
