package com.keziah.spiritualtracker;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldPath;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.Calendar;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * Local, gentle reminders (no server involved):
 *  - evening: at 8pm, if anything is still undone today, one notification listing what's left;
 *  - weekly: Sunday 7pm, a summary of both partners' week that opens Our Journey.
 */
public class ReminderWorker extends Worker {

    private static final String TAG = "ReminderWorker";
    private static final String KEY_MODE = "mode";
    private static final String MODE_EVENING = "evening";
    private static final String MODE_WEEKLY = "weekly";

    public ReminderWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    private static final int EVENING_HOUR = 20;
    private static final int WEEKLY_HOUR  = 19;

    public static void schedule(Context context) {
        WorkManager wm = WorkManager.getInstance(context);
        // The old every-24-hours jobs drifted (see DailySchedule); stop them.
        wm.cancelUniqueWork("evening_reminder");
        wm.cancelUniqueWork("weekly_summary");
        bookEvening(context);
        bookWeekly(context);
    }

    private static void bookEvening(Context context) {
        DailySchedule.bookNext(context, "evening", ReminderWorker.class, -1, EVENING_HOUR,
                new Data.Builder().putString(KEY_MODE, MODE_EVENING).build());
    }

    private static void bookWeekly(Context context) {
        DailySchedule.bookNext(context, "weekly", ReminderWorker.class, Calendar.SUNDAY, WEEKLY_HOUR,
                new Data.Builder().putString(KEY_MODE, MODE_WEEKLY).build());
    }

    @NonNull
    @Override
    public Result doWork() {
        boolean weeklyRun = MODE_WEEKLY.equals(getInputData().getString(KEY_MODE));
        // Book the next one first, so a failure below can't end the chain.
        if (weeklyRun) bookWeekly(getApplicationContext()); else bookEvening(getApplicationContext());
        if (!DailySchedule.onTime(weeklyRun ? WEEKLY_HOUR : EVENING_HOUR)) return Result.success();

        if (FirebaseAuth.getInstance().getCurrentUser() == null) return Result.success();
        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        try {
            if (weeklyRun) weekly(uid);
            else evening(uid);
            return Result.success();
        } catch (Exception e) {
            Log.w(TAG, "Reminder failed", e);
            return Result.retry();
        }
    }

    private void evening(String uid) throws Exception {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        String today = ActivityLog.today();
        DocumentSnapshot day = Tasks.await(ActivityLog.dayRef(uid, today).get(), 20, TimeUnit.SECONDS);
        DocumentSnapshot user = Tasks.await(db.collection("users").document(uid).get(), 20, TimeUnit.SECONDS);

        HistoryRepository.DayRecord r = record(today, day);
        // Older fields still used as a fallback on the user doc.
        if (today.equals(user.getString("prayedTodayDate")))    r.prayed = true;
        if (today.equals(user.getString("journaledTodayDate"))) r.journal = true;
        if (today.equals(user.getString("memorizedTodayDate"))) r.memorize = true;

        List<String> left = ProgressMath.missing(r);
        if (left.isEmpty()) return;

        String list = left.size() == 1 ? left.get(0)
                : String.join(", ", left.subList(0, left.size() - 1)) + " and " + left.get(left.size() - 1);
        MyFirebaseMessagingService.showNotification(getApplicationContext(),
                "Still to do today: " + list + " 🌙",
                "A few quiet minutes before bed? " + (PartnerName.isKnown(getApplicationContext())
                        ? PartnerName.get(getApplicationContext()) : "Your partner") + " is cheering you on.",
                "reminder", "");
    }

    private void weekly(String uid) throws Exception {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        Calendar c = Calendar.getInstance();
        String to = ActivityLog.dayKey(c.getTime());
        c.add(Calendar.DAY_OF_YEAR, -6);
        String from = ActivityLog.dayKey(c.getTime());

        DocumentSnapshot me = Tasks.await(db.collection("users").document(uid).get(), 20, TimeUnit.SECONDS);
        String partnerId = me.getString("partnerId");

        String mine = summary(weekOf(uid, from, to), from, to);
        String body = "You: " + mine;
        if (partnerId != null && !partnerId.isEmpty()) {
            try {
                DocumentSnapshot p = Tasks.await(db.collection("users").document(partnerId).get(), 20, TimeUnit.SECONDS);
                body += "\n" + PartnerName.display(getApplicationContext(), p) + ": " + summary(weekOf(partnerId, from, to), from, to);
            } catch (Exception e) {
                Log.w(TAG, "Partner week unavailable", e);
            }
        }
        MyFirebaseMessagingService.showNotification(getApplicationContext(),
                "Your week together 🙏", body, "history", "");
    }

    private TreeMap<String, HistoryRepository.DayRecord> weekOf(String uid, String from, String to) throws Exception {
        QuerySnapshot snap = Tasks.await(FirebaseFirestore.getInstance()
                .collection("users").document(uid).collection("days")
                .orderBy(FieldPath.documentId()).startAt(from).endAt(to).get(), 20, TimeUnit.SECONDS);
        TreeMap<String, HistoryRepository.DayRecord> days = new TreeMap<>();
        for (DocumentSnapshot d : snap.getDocuments()) days.put(d.getId(), record(d.getId(), d));
        return days;
    }

    private static String summary(TreeMap<String, HistoryRepository.DayRecord> days, String from, String to) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ProgressMath.LABELS.length; i++) {
            if (i > 0) sb.append(" · ");
            sb.append(ProgressMath.LABELS[i]).append(' ')
              .append(ProgressMath.countBetween(days, from, to, i)).append("/7");
        }
        return sb.toString();
    }

    private static HistoryRepository.DayRecord record(String key, DocumentSnapshot d) {
        HistoryRepository.DayRecord r = new HistoryRepository.DayRecord(key);
        if (d != null && d.exists()) {
            r.bible    = Boolean.TRUE.equals(d.getBoolean("bible"));
            r.prayed   = Boolean.TRUE.equals(d.getBoolean("prayed"));
            r.journal  = Boolean.TRUE.equals(d.getBoolean("journal"));
            r.memorize = Boolean.TRUE.equals(d.getBoolean("memorize"));
        }
        return r;
    }
}
