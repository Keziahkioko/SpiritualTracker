package com.keziah.spiritualtracker;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 12:30 every day: the unanswered prayer requests whose weekday it is (see PrayerReminders),
 * from the shared board (both of ours), the joint focus list and my personal list, gathered
 * into one notification. Requests with their bell switched off are left out.
 */
public class PrayerReminderWorker extends Worker {

    private static final String TAG = "PrayerReminder";
    private static final int SHARED_TAB = 1, MY_SPACE_TAB = 2;

    public PrayerReminderWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        // Book tomorrow's first, so a failure below can't end the chain.
        PrayerReminders.schedule(getApplicationContext());
        if (!DailySchedule.onTime(PrayerReminders.HOUR)) return Result.success();
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) return Result.success();
        try {
            remind(getApplicationContext(), uid, Calendar.getInstance());
            return Result.success();
        } catch (Exception e) {
            Log.w(TAG, "Prayer reminder failed", e);
            return Result.retry();
        }
    }

    /** Gathers {@code today}'s requests and shows the notification (if any). Returns how many. */
    static int remind(Context context, String uid, Calendar today) throws Exception {
        FirebaseFirestore db = FirebaseFirestore.getInstance();

        DocumentSnapshot me = await(db.collection("users").document(uid).get());
        String partnerId = me.getString("partnerId");
        if (partnerId != null && partnerId.isEmpty()) partnerId = null;
        String partner = PartnerName.get(context);

        Set<String> muted = PrayerReminders.muted(context);
        try {
            Object list = await(PrayerReminders.cloudRef().get()).get("muted");
            if (list instanceof List) {
                muted = new HashSet<>();
                for (Object o : (List<?>) list) if (o instanceof String) muted.add((String) o);
            }
        } catch (Exception e) {
            Log.w(TAG, "Using the phone's copy of the bells", e);
        }

        List<String> lines = new ArrayList<>();
        int tab = 0;

        // Shared board: both partners' unanswered requests.
        QuerySnapshot shared = await(db.collection("shared_prayers")
                .whereIn("authorId", partnerId != null ? Arrays.asList(uid, partnerId) : Arrays.asList(uid)).get());
        for (DocumentSnapshot d : shared.getDocuments()) {
            if (!due(d, PrayerReminders.SHARED, muted, today)) continue;
            lines.add(uid.equals(d.getString("authorId"))
                    ? "Keep praying about your request: " + shorten(d.getString("title"))
                    : "Pray for " + partner + " concerning: " + shorten(d.getString("title")));
            if (tab == 0) tab = SHARED_TAB;
        }

        // Joint focus list.
        if (partnerId != null) {
            String pair = uid.compareTo(partnerId) < 0 ? uid + "_" + partnerId : partnerId + "_" + uid;
            QuerySnapshot joint = await(db.collection("partnerships").document(pair).collection("joint_list").get());
            for (DocumentSnapshot d : joint.getDocuments()) {
                if (!due(d, PrayerReminders.JOINT, muted, today)) continue;
                lines.add("Pray together for: " + shorten(d.getString("title")));
                if (tab == 0) tab = SHARED_TAB;
            }
        }

        // My personal list.
        QuerySnapshot personal = await(db.collection("users").document(uid).collection("personal_prayers").get());
        for (DocumentSnapshot d : personal.getDocuments()) {
            if (!due(d, PrayerReminders.PERSONAL, muted, today)) continue;
            lines.add("Remember to pray for: " + shorten(d.getString("title")));
            if (tab == 0) tab = MY_SPACE_TAB;
        }

        if (lines.isEmpty()) return 0;
        String title, body;
        if (lines.size() == 1) {
            title = "Remember to pray";
            body = lines.get(0);
        } else {
            title = "Remember to pray today";
            StringBuilder sb = new StringBuilder();
            for (String l : lines) sb.append(sb.length() > 0 ? "\n" : "").append("• ").append(l);
            body = sb.toString();
        }
        MyFirebaseMessagingService.showNotification(context, title, body, "prayer_reminder", String.valueOf(tab));
        return lines.size();
    }

    private static boolean due(DocumentSnapshot d, String kind, Set<String> muted, Calendar today) {
        if (Boolean.TRUE.equals(d.getBoolean("isAnswered"))) return false;
        String title = d.getString("title");
        if (title == null || title.trim().isEmpty() || muted.contains(kind + d.getId())) return false;
        Timestamp posted = d.getTimestamp("timestamp");
        return PrayerReminders.dueOn(posted, today);
    }

    /** First line of a title, kept short: a focus item can be several paragraphs long. */
    static String shorten(String title) {
        String first = "";
        for (String line : title.split("\n")) {
            if (!line.trim().isEmpty()) { first = line.replaceAll("\\s+", " ").trim(); break; }
        }
        return first.length() <= 60 ? first : first.substring(0, 57).trim() + "…";
    }

    private static <T> T await(com.google.android.gms.tasks.Task<T> task) throws Exception {
        return Tasks.await(task, 20, TimeUnit.SECONDS);
    }
}
