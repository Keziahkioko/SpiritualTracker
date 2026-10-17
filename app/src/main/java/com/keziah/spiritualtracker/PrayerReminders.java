package com.keziah.spiritualtracker;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Weekly prayer reminders: every unanswered request (shared, joint focus list and personal) comes
 * back once a week at 12:30, on the weekday it was posted. Each request has a bell; switching it
 * off is personal (it only quiets your own reminders) and is kept under your account in
 * users/{uid}/private/prayer_reminders, with a copy on the phone so the bells draw instantly.
 */
final class PrayerReminders {

    static final int HOUR = 12, MINUTE = 30;

    static final String SHARED = "shared:", JOINT = "joint:", PERSONAL = "personal:";

    private static final String PREFS = "prayer_reminders";
    private static final String MUTED = "muted";

    private PrayerReminders() {}

    static void schedule(Context context) {
        DailySchedule.bookNext(context, "prayer", PrayerReminderWorker.class, -1, HOUR, MINUTE,
                androidx.work.Data.EMPTY);
    }

    // ─── Bells ───────────────────────────────────────────────────────────────

    static boolean isOn(Context context, String key) {
        return !muted(context).contains(key);
    }

    static Set<String> muted(Context context) {
        return new HashSet<>(prefs(context).getStringSet(MUTED, Collections.emptySet()));
    }

    /** Shows a request's bell (solid purple = on, grey crossed out = off) and the day it reminds. */
    static void bind(ImageView bell, TextView day, String key, Timestamp posted) {
        Context context = bell.getContext();
        boolean on = isOn(context, key);
        bell.setImageResource(on ? R.drawable.ic_bell_on : R.drawable.ic_bell_off);
        bell.setContentDescription(on ? "Weekly reminder on. Tap to turn off" : "Weekly reminder off. Tap to turn on");
        String weekday = posted != null ? shortDay(posted) : null;
        if (day != null) {
            day.setText(on && weekday != null ? weekday : "");
            day.setVisibility(on && weekday != null ? android.view.View.VISIBLE : android.view.View.GONE);
        }
        android.view.View.OnClickListener toggle = v -> {
            boolean nowOn = !isOn(context, key);
            set(context, key, nowOn);
            bind(bell, day, key, posted);
            bell.animate().scaleX(1.2f).scaleY(1.2f).setDuration(90)
                    .withEndAction(() -> bell.animate().scaleX(1f).scaleY(1f).setDuration(90)).start();
            Toast.makeText(context, nowOn
                    ? "Weekly reminder on: every " + (posted != null ? longDay(posted) : "week") + " at 12:30"
                    : "Weekly reminder off", Toast.LENGTH_SHORT).show();
        };
        bell.setOnClickListener(toggle);
        if (day != null) day.setOnClickListener(toggle);
    }

    static void set(Context context, String key, boolean on) {
        Set<String> muted = muted(context);
        if (on) muted.remove(key); else muted.add(key);
        prefs(context).edit().putStringSet(MUTED, muted).apply();
        DocumentReference ref = cloudRef();
        if (ref != null) {
            ref.set(Collections.singletonMap(MUTED, on ? FieldValue.arrayRemove(key) : FieldValue.arrayUnion(key)),
                    SetOptions.merge());
        }
    }

    /** Brings the phone's copy in line with the account (e.g. after signing in again). */
    static void sync(Context context, Runnable then) {
        DocumentReference ref = cloudRef();
        if (ref == null) return;
        Context app = context.getApplicationContext();
        ref.get().addOnSuccessListener(doc -> {
            Object list = doc.get(MUTED);
            Set<String> muted = new HashSet<>();
            if (list instanceof List) for (Object o : (List<?>) list) if (o instanceof String) muted.add((String) o);
            prefs(app).edit().putStringSet(MUTED, muted).apply();
            if (then != null) then.run();
        });
    }

    static DocumentReference cloudRef() {
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) return null;
        return FirebaseFirestore.getInstance().collection("users").document(uid)
                .collection("private").document("prayer_reminders");
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ─── The weekly day ──────────────────────────────────────────────────────

    /** True when a request posted at {@code posted} is due its weekly reminder on {@code day}. */
    static boolean dueOn(Timestamp posted, Calendar day) {
        if (posted == null) return false;
        Calendar p = Calendar.getInstance();
        p.setTime(posted.toDate());
        // Not on the day it was posted: the first reminder is a week later.
        boolean sameDate = p.get(Calendar.YEAR) == day.get(Calendar.YEAR)
                && p.get(Calendar.DAY_OF_YEAR) == day.get(Calendar.DAY_OF_YEAR);
        return !sameDate && p.before(day) && p.get(Calendar.DAY_OF_WEEK) == day.get(Calendar.DAY_OF_WEEK);
    }

    static String shortDay(Timestamp posted) {
        return new java.text.SimpleDateFormat("EEE", Locale.getDefault()).format(posted.toDate());
    }

    static String longDay(Timestamp posted) {
        return new java.text.SimpleDateFormat("EEEE", Locale.getDefault()).format(posted.toDate());
    }
}
