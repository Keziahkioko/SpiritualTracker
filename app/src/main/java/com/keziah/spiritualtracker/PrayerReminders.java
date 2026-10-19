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

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Weekly prayer reminders: every unanswered request (shared, joint focus list and personal) comes
 * back at 6:30 in the morning on the days you choose for it (until you choose, the weekday it was
 * posted). Each request has a bell to switch it off and its days beside it, which you tap to
 * change. Both are yours alone: kept under your account in users/{uid}/private/prayer_reminders,
 * with a copy on the phone so they draw instantly.
 */
final class PrayerReminders {

    static final int HOUR = 6, MINUTE = 30;
    static final String TIME = "6:30";

    static final String SHARED = "shared:", JOINT = "joint:", PERSONAL = "personal:";

    private static final String PREFS = "prayer_reminders";
    private static final String MUTED = "muted";
    private static final String DAYS = "days";

    /** Monday first, as the day circles are laid out. */
    private static final int[] WEEK = {Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
            Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY};

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

    // ─── Bell and days on a card ─────────────────────────────────────────────

    static boolean isOn(Context context, String key) {
        return !muted(context).contains(key);
    }

    /** The weekdays (Calendar.SUNDAY…SATURDAY) this request reminds you on; empty if unknown. */
    static Set<Integer> daysFor(Context context, String key, Timestamp posted) {
        return daysFor(days(context), key, posted);
    }

    static Set<Integer> daysFor(Map<String, Set<Integer>> chosen, String key, Timestamp posted) {
        Set<Integer> days = chosen.get(key);
        if (days != null && !days.isEmpty()) return days;
        Set<Integer> posting = new TreeSet<>();
        if (posted != null) {
            Calendar p = Calendar.getInstance();
            p.setTime(posted.toDate());
            posting.add(p.get(Calendar.DAY_OF_WEEK));
        }
        return posting;
    }

    /** Draws a request's bell (solid purple = on, grey crossed out = off) and its days. */
    static void bind(ImageView bell, TextView label, String key, Timestamp posted) {
        Context context = bell.getContext();
        boolean on = isOn(context, key);
        Set<Integer> days = daysFor(context, key, posted);
        bell.setImageResource(on ? R.drawable.ic_bell_on : R.drawable.ic_bell_off);
        bell.setContentDescription(on ? "Reminders on. Tap to turn off" : "Reminders off. Tap to turn on");
        if (label != null) {
            label.setText(shortList(days));
            label.setVisibility(on && !days.isEmpty() ? View.VISIBLE : View.GONE);
            label.setContentDescription("Reminds you " + longList(days) + ". Tap to change the days");
            label.setOnClickListener(v -> pickDays(context, daysFor(context, key, posted), chosen -> {
                setDays(context, key, chosen);
                bind(bell, label, key, posted);
                Toast.makeText(context, "Reminders: " + longList(chosen) + " at " + TIME,
                        Toast.LENGTH_SHORT).show();
            }));
        }
        bell.setOnClickListener(v -> {
            boolean nowOn = !isOn(context, key);
            setOn(context, key, nowOn);
            bind(bell, label, key, posted);
            bell.animate().scaleX(1.2f).scaleY(1.2f).setDuration(90)
                    .withEndAction(() -> bell.animate().scaleX(1f).scaleY(1f).setDuration(90)).start();
            Set<Integer> d = daysFor(context, key, posted);
            Toast.makeText(context, nowOn
                    ? "Reminders on: " + (d.isEmpty() ? "weekly" : longList(d)) + " at " + TIME
                    : "Reminders off", Toast.LENGTH_SHORT).show();
        });
    }

    interface DaysChosen { void chosen(Set<Integer> weekdays); }

    /** A small window to choose the reminder days. */
    static void pickDays(Context context, Set<Integer> current, DaysChosen then) {
        DayPicker picker = new DayPicker(context, current.isEmpty() ? Collections.singleton(today()) : current);
        int pad = dp(context, 24);
        picker.setPadding(pad, dp(context, 8), pad, 0);
        new MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setTitle("Remind me on")
                .setView(picker)
                .setPositiveButton("Save", (d, w) -> then.chosen(picker.days()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ─── Saved choices ───────────────────────────────────────────────────────

    static Set<String> muted(Context context) {
        return new HashSet<>(prefs(context).getStringSet(MUTED, Collections.emptySet()));
    }

    /** Chosen days per request. Saved on the phone as "key=2,4,6". */
    static Map<String, Set<Integer>> days(Context context) {
        Map<String, Set<Integer>> out = new HashMap<>();
        for (String entry : prefs(context).getStringSet(DAYS, Collections.emptySet())) {
            int eq = entry.lastIndexOf('=');
            if (eq < 0) continue;
            Set<Integer> days = new TreeSet<>();
            for (String d : entry.substring(eq + 1).split(",")) {
                try { days.add(Integer.parseInt(d.trim())); } catch (Exception ignored) { }
            }
            if (!days.isEmpty()) out.put(entry.substring(0, eq), days);
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

    static void setDays(Context context, String key, Set<Integer> weekdays) {
        Map<String, Set<Integer>> days = days(context);
        days.put(key, new TreeSet<>(weekdays));
        prefs(context).edit().putStringSet(DAYS, encode(days)).apply();
        DocumentReference ref = cloudRef();
        if (ref != null) {
            ref.set(Collections.singletonMap(DAYS, Collections.singletonMap(key, new ArrayList<>(new TreeSet<>(weekdays)))),
                    SetOptions.merge());
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

    /** Chosen days from the account: a list of weekdays, or a single number (saved before several days could be chosen). */
    static Map<String, Set<Integer>> daysIn(DocumentSnapshot doc) {
        Map<String, Set<Integer>> days = new HashMap<>();
        Object map = doc.get(DAYS);
        if (!(map instanceof Map)) return days;
        for (Map.Entry<?, ?> e : ((Map<?, ?>) map).entrySet()) {
            if (!(e.getKey() instanceof String)) continue;
            Set<Integer> set = new TreeSet<>();
            if (e.getValue() instanceof Number) set.add(((Number) e.getValue()).intValue());
            else if (e.getValue() instanceof List) {
                for (Object o : (List<?>) e.getValue()) if (o instanceof Number) set.add(((Number) o).intValue());
            }
            if (!set.isEmpty()) days.put((String) e.getKey(), set);
        }
        return days;
    }

    private static Set<String> encode(Map<String, Set<Integer>> days) {
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, Set<Integer>> e : days.entrySet()) {
            StringBuilder sb = new StringBuilder();
            for (int d : e.getValue()) sb.append(sb.length() > 0 ? "," : "").append(d);
            out.add(e.getKey() + "=" + sb);
        }
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
     * True when a request is due a reminder on {@code day}: it's one of its days, and it wasn't
     * posted that same day (the first reminder is its next chosen day after that).
     */
    static boolean dueOn(Set<Integer> weekdays, Timestamp posted, Calendar day) {
        if (!weekdays.contains(day.get(Calendar.DAY_OF_WEEK))) return false;
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

    /** In week order (Monday first). */
    private static List<Integer> ordered(Set<Integer> days) {
        List<Integer> out = new ArrayList<>();
        for (int d : WEEK) if (days.contains(d)) out.add(d);
        return out;
    }

    private static final Set<Integer> WEEKDAYS = new HashSet<>(java.util.Arrays.asList(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY));
    private static final Set<Integer> WEEKEND = new HashSet<>(java.util.Arrays.asList(
            Calendar.SATURDAY, Calendar.SUNDAY));

    /** For a card: "Tue", "Mon Wed Fri", "Weekdays", "Weekends", "Daily" or "5 days". */
    static String shortList(Set<Integer> days) {
        if (days.isEmpty()) return "";
        if (days.size() == 7) return "Daily";
        if (days.equals(WEEKDAYS)) return "Weekdays";
        if (days.equals(WEEKEND)) return "Weekends";
        if (days.size() > 3) return days.size() + " days";
        StringBuilder sb = new StringBuilder();
        for (int d : ordered(days)) sb.append(sb.length() > 0 ? " " : "").append(shortName(d));
        return sb.toString();
    }

    /** In a sentence: "every Tuesday", "Mon, Wed and Fri", "every weekday", "every day". */
    static String longList(Set<Integer> days) {
        if (days.isEmpty()) return "weekly";
        if (days.size() == 7) return "every day";
        if (days.equals(WEEKDAYS)) return "every weekday";
        if (days.equals(WEEKEND)) return "every weekend";
        List<Integer> list = ordered(days);
        if (list.size() == 1) return "every " + longName(list.get(0));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(i == list.size() - 1 ? " and " : ", ");
            sb.append(shortName(list.get(i)));
        }
        return sb.toString();
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
     * Seven round day buttons (M T W T F S S), any number of them chosen, under a line saying
     * which, e.g. "Reminders: Mon, Wed and Fri at 6:30". Used when adding a request and when
     * changing its days. At least one day stays chosen; the bell is for switching off.
     */
    static final class DayPicker extends LinearLayout {

        private final TextView label;
        private final TextView[] chips = new TextView[7];
        private final Set<Integer> days = new TreeSet<>();

        DayPicker(Context context, Set<Integer> initial) {
            super(context);
            setOrientation(VERTICAL);
            days.addAll(initial);

            label = new TextView(context);
            label.setTextColor(androidx.core.content.ContextCompat.getColor(label.getContext(), R.color.st_text_2));
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            addView(label);

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(HORIZONTAL);
            LayoutParams rowParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            rowParams.topMargin = dp(context, 8);
            addView(row, rowParams);

            int size = dp(context, 36);
            for (int i = 0; i < 7; i++) {
                int weekday = WEEK[i];
                TextView chip = new TextView(context);
                chip.setText(shortName(weekday).substring(0, 1));
                chip.setGravity(Gravity.CENTER);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                chip.setTypeface(chip.getTypeface(), android.graphics.Typeface.BOLD);
                chip.setContentDescription(longName(weekday));
                chip.setOnClickListener(v -> {
                    if (days.contains(weekday)) {
                        if (days.size() == 1) {
                            Toast.makeText(context, "Keep at least one day. To stop reminders, tap the bell.",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        days.remove(weekday);
                    } else {
                        days.add(weekday);
                    }
                    refresh();
                });
                LayoutParams p = new LayoutParams(0, size, 1f);
                p.setMarginEnd(i < 6 ? dp(context, 4) : 0);
                row.addView(chip, p);
                chips[i] = chip;
            }
            refresh();
        }

        Set<Integer> days() { return new TreeSet<>(days); }

        private void refresh() {
            String when = longList(days);
            label.setText("Reminders: " + Character.toUpperCase(when.charAt(0)) + when.substring(1) + " at " + TIME);
            for (int i = 0; i < 7; i++) {
                boolean selected = days.contains(WEEK[i]);
                GradientDrawable bg = selected
                        ? new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                                new int[]{Color.parseColor("#B58AD3"), Color.parseColor("#6A1B9A")})
                        : new GradientDrawable();
                bg.setShape(GradientDrawable.OVAL);
                if (!selected) bg.setColor(androidx.core.content.ContextCompat.getColor(chips[i].getContext(), R.color.st_soft));
                chips[i].setBackground(bg);
                chips[i].setTextColor(selected ? Color.WHITE : androidx.core.content.ContextCompat.getColor(chips[i].getContext(), R.color.st_accent));
                chips[i].setSelected(selected);
            }
        }
    }

    /**
     * Adds the day picker under an "add" window's own fields and returns it, so the chosen days
     * can be saved with the new request.
     */
    static DayPicker addPickerTo(View dialogView) {
        Context context = dialogView.getContext();
        DayPicker picker = new DayPicker(context, Collections.singleton(today()));
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
