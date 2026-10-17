package com.keziah.spiritualtracker;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Weekly prayer reminders: every unanswered request (shared, joint focus list and personal) comes
 * back once a week at 6:30 in the morning, on a day you choose for it (until you choose, the
 * weekday it was posted). Each request has a bell to switch it off and the day beside it, which
 * you tap to change. Both are yours alone: kept under your account in
 * users/{uid}/private/prayer_reminders, with a copy on the phone so they draw instantly.
 */
final class PrayerReminders {

    static final int HOUR = 6, MINUTE = 30;
    static final String TIME = "6:30";

    static final String SHARED = "shared:", JOINT = "joint:", PERSONAL = "personal:";

    private static final String PREFS = "prayer_reminders";
    private static final String MUTED = "muted";
    private static final String DAYS = "days";

    private PrayerReminders() {}

    static void schedule(Context context) {
        androidx.work.WorkManager wm = androidx.work.WorkManager.getInstance(context);
        // The first version ran at 12:30 under these names; stop those runs.
        Calendar c = Calendar.getInstance();
        for (int i = 0; i < 3; i++) {
            wm.cancelUniqueWork("prayer_" + ActivityLog.dayKey(c.getTime()));
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        DailySchedule.bookNext(context, "prayer_am", PrayerReminderWorker.class, -1, HOUR, MINUTE,
                androidx.work.Data.EMPTY);
    }

    // ─── Bell and day on a card ──────────────────────────────────────────────

    static boolean isOn(Context context, String key) {
        return !muted(context).contains(key);
    }

    /** The weekday (Calendar.SUNDAY…SATURDAY) this request reminds you on, or -1 if unknown. */
    static int dayFor(Context context, String key, Timestamp posted) {
        Integer chosen = days(context).get(key);
        if (chosen != null) return chosen;
        if (posted == null) return -1;
        Calendar p = Calendar.getInstance();
        p.setTime(posted.toDate());
        return p.get(Calendar.DAY_OF_WEEK);
    }

    /** Draws a request's bell (solid purple = on, grey crossed out = off) and its day. */
    static void bind(ImageView bell, TextView day, String key, Timestamp posted) {
        Context context = bell.getContext();
        boolean on = isOn(context, key);
        int weekday = dayFor(context, key, posted);
        bell.setImageResource(on ? R.drawable.ic_bell_on : R.drawable.ic_bell_off);
        bell.setContentDescription(on ? "Weekly reminder on. Tap to turn off" : "Weekly reminder off. Tap to turn on");
        if (day != null) {
            day.setText(weekday > 0 ? shortName(weekday) : "");
            day.setVisibility(on && weekday > 0 ? View.VISIBLE : View.GONE);
            day.setContentDescription("Reminds you on " + (weekday > 0 ? longName(weekday) : "") + ". Tap to change the day");
            day.setOnClickListener(v -> pickDay(context, dayFor(context, key, posted), chosen -> {
                setDay(context, key, chosen);
                bind(bell, day, key, posted);
                Toast.makeText(context, "Weekly reminder: every " + longName(chosen) + " at " + TIME,
                        Toast.LENGTH_SHORT).show();
            }));
        }
        bell.setOnClickListener(v -> {
            boolean nowOn = !isOn(context, key);
            setOn(context, key, nowOn);
            bind(bell, day, key, posted);
            bell.animate().scaleX(1.2f).scaleY(1.2f).setDuration(90)
                    .withEndAction(() -> bell.animate().scaleX(1f).scaleY(1f).setDuration(90)).start();
            int d = dayFor(context, key, posted);
            Toast.makeText(context, nowOn
                    ? "Weekly reminder on: every " + (d > 0 ? longName(d) : "week") + " at " + TIME
                    : "Weekly reminder off", Toast.LENGTH_SHORT).show();
        });
    }

    interface DayChosen { void chosen(int weekday); }

    /** A small window to choose the reminder day. */
    static void pickDay(Context context, int current, DayChosen then) {
        DayPicker picker = new DayPicker(context, current > 0 ? current : today());
        int pad = dp(context, 24);
        picker.setPadding(pad, dp(context, 8), pad, 0);
        new MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setTitle("Remind me every")
                .setView(picker)
                .setPositiveButton("Save", (d, w) -> then.chosen(picker.day()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ─── Saved choices ───────────────────────────────────────────────────────

    static Set<String> muted(Context context) {
        return new HashSet<>(prefs(context).getStringSet(MUTED, Collections.emptySet()));
    }

    static Map<String, Integer> days(Context context) {
        Map<String, Integer> out = new HashMap<>();
        for (String entry : prefs(context).getStringSet(DAYS, Collections.emptySet())) {
            int eq = entry.lastIndexOf('=');
            try { out.put(entry.substring(0, eq), Integer.parseInt(entry.substring(eq + 1))); } catch (Exception ignored) { }
        }
        return out;
    }

    static void setOn(Context context, String key, boolean on) {
        Set<String> muted = muted(context);
        if (on) muted.remove(key); else muted.add(key);
        prefs(context).edit().putStringSet(MUTED, muted).apply();
        DocumentReference ref = cloudRef();
        if (ref != null) {
            ref.set(Collections.singletonMap(MUTED, on ? FieldValue.arrayRemove(key) : FieldValue.arrayUnion(key)),
                    SetOptions.merge());
        }
    }

    static void setDay(Context context, String key, int weekday) {
        Map<String, Integer> days = days(context);
        days.put(key, weekday);
        prefs(context).edit().putStringSet(DAYS, encode(days)).apply();
        DocumentReference ref = cloudRef();
        if (ref != null) {
            ref.set(Collections.singletonMap(DAYS, Collections.singletonMap(key, weekday)), SetOptions.merge());
        }
    }

    /** Brings the phone's copy in line with the account (e.g. after signing in again). */
    static void sync(Context context, Runnable then) {
        DocumentReference ref = cloudRef();
        if (ref == null) return;
        Context app = context.getApplicationContext();
        ref.get().addOnSuccessListener(doc -> {
            prefs(app).edit()
                    .putStringSet(MUTED, mutedIn(doc))
                    .putStringSet(DAYS, encode(daysIn(doc)))
                    .apply();
            if (then != null) then.run();
        });
    }

    static Set<String> mutedIn(DocumentSnapshot doc) {
        Set<String> muted = new HashSet<>();
        Object list = doc.get(MUTED);
        if (list instanceof List) for (Object o : (List<?>) list) if (o instanceof String) muted.add((String) o);
        return muted;
    }

    static Map<String, Integer> daysIn(DocumentSnapshot doc) {
        Map<String, Integer> days = new HashMap<>();
        Object map = doc.get(DAYS);
        if (map instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) map).entrySet()) {
                if (e.getKey() instanceof String && e.getValue() instanceof Number) {
                    days.put((String) e.getKey(), ((Number) e.getValue()).intValue());
                }
            }
        }
        return days;
    }

    private static Set<String> encode(Map<String, Integer> days) {
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, Integer> e : days.entrySet()) out.add(e.getKey() + "=" + e.getValue());
        return out;
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

    // ─── Days ────────────────────────────────────────────────────────────────

    /**
     * True when a request is due its reminder on {@code day}: it's the chosen weekday, and it
     * wasn't posted that same day (the first reminder comes the following week).
     */
    static boolean dueOn(int weekday, Timestamp posted, Calendar day) {
        if (weekday <= 0 || day.get(Calendar.DAY_OF_WEEK) != weekday) return false;
        if (posted == null) return true;
        Calendar p = Calendar.getInstance();
        p.setTime(posted.toDate());
        boolean sameDate = p.get(Calendar.YEAR) == day.get(Calendar.YEAR)
                && p.get(Calendar.DAY_OF_YEAR) == day.get(Calendar.DAY_OF_YEAR);
        return !sameDate && p.before(day);
    }

    static int today() {
        return Calendar.getInstance().get(Calendar.DAY_OF_WEEK);
    }

    static String shortName(int weekday) {
        return name(weekday, "EEE");
    }

    static String longName(int weekday) {
        return name(weekday, "EEEE");
    }

    private static String name(int weekday, String pattern) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.DAY_OF_WEEK, weekday);
        return new java.text.SimpleDateFormat(pattern, Locale.getDefault()).format(c.getTime());
    }

    static int dp(Context context, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }

    /**
     * Seven round day buttons (M T W T F S S) under a line saying which day is chosen, e.g.
     * "Weekly reminder: Tuesday at 6:30". Used when adding a request and when changing its day.
     */
    static final class DayPicker extends LinearLayout {

        private static final int[] ORDER = {Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY};

        private final TextView label;
        private final TextView[] chips = new TextView[7];
        private int day;

        DayPicker(Context context, int initial) {
            super(context);
            setOrientation(VERTICAL);
            day = initial;

            label = new TextView(context);
            label.setTextColor(Color.parseColor("#5F5F64"));
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            addView(label);

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(HORIZONTAL);
            LayoutParams rowParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            rowParams.topMargin = dp(context, 8);
            addView(row, rowParams);

            int size = dp(context, 36);
            for (int i = 0; i < 7; i++) {
                int weekday = ORDER[i];
                TextView chip = new TextView(context);
                chip.setText(shortName(weekday).substring(0, 1));
                chip.setGravity(Gravity.CENTER);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                chip.setTypeface(chip.getTypeface(), android.graphics.Typeface.BOLD);
                chip.setContentDescription(longName(weekday));
                chip.setOnClickListener(v -> { day = weekday; refresh(); });
                LayoutParams p = new LayoutParams(0, size, 1f);
                p.setMarginEnd(i < 6 ? dp(context, 4) : 0);
                row.addView(chip, p);
                chips[i] = chip;
            }
            refresh();
        }

        int day() { return day; }

        private void refresh() {
            label.setText("Weekly reminder: " + longName(day) + " at " + TIME);
            for (int i = 0; i < 7; i++) {
                boolean selected = ORDER[i] == day;
                GradientDrawable bg = selected
                        ? new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                                new int[]{Color.parseColor("#B58AD3"), Color.parseColor("#6A1B9A")})
                        : new GradientDrawable();
                bg.setShape(GradientDrawable.OVAL);
                if (!selected) bg.setColor(Color.parseColor("#EDE7F6"));
                chips[i].setBackground(bg);
                chips[i].setTextColor(selected ? Color.WHITE : Color.parseColor("#7E57C2"));
                chips[i].setSelected(selected);
            }
        }
    }

    /**
     * Adds the day picker under an "add" window's own fields and returns it, so the chosen day can
     * be saved with the new request.
     */
    static DayPicker addPickerTo(View dialogView) {
        Context context = dialogView.getContext();
        DayPicker picker = new DayPicker(context, today());
        // The add windows are a purple header followed by the white section with the fields.
        android.view.ViewGroup root = (android.view.ViewGroup) dialogView;
        View last = root.getChildAt(root.getChildCount() - 1);
        android.view.ViewGroup target = last instanceof LinearLayout ? (android.view.ViewGroup) last : root;
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(context, 12);
        target.addView(picker, p);
        return picker;
    }
}
