package com.keziah.spiritualtracker;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Pure calculations over day records (no Android or Firebase), shared by screens, reminders and tests. */
public final class ProgressMath {

    public static final String[] LABELS = {"Bible", "Prayer", "Journal", "Memorize"};
    /** Pass as {@code discipline} to mean "all four on the same day". */
    public static final int ALL = -1;

    private ProgressMath() {}

    public static boolean isDone(HistoryRepository.DayRecord r, int discipline) {
        if (r == null) return false;
        switch (discipline) {
            case ALL: return r.doneCount() == LABELS.length;
            case 0:   return r.bible;
            case 1:   return r.prayed;
            case 2:   return r.journal;
            default:  return r.memorize;
        }
    }

    /**
     * Returns {current, best} streaks in days, up to {@code todayKey}. An unfinished today
     * doesn't break the current streak: the day isn't over yet.
     */
    public static int[] streaks(TreeMap<String, HistoryRepository.DayRecord> days, int discipline, String todayKey) {
        if (days.isEmpty()) return new int[]{0, 0};
        Calendar c = calendarFor(todayKey);
        if (c == null) return new int[]{0, 0};

        if (!isDone(days.get(todayKey), discipline)) c.add(Calendar.DAY_OF_YEAR, -1);
        int current = 0;
        while (isDone(days.get(key(c)), discipline)) {
            current++;
            c.add(Calendar.DAY_OF_YEAR, -1);
        }

        int best = 0, run = 0;
        Calendar walk = calendarFor(days.firstKey());
        if (walk == null) return new int[]{current, current};
        while (key(walk).compareTo(todayKey) <= 0) {
            if (isDone(days.get(key(walk)), discipline)) {
                run++;
                best = Math.max(best, run);
            } else {
                run = 0;
            }
            walk.add(Calendar.DAY_OF_YEAR, 1);
        }
        return new int[]{current, Math.max(best, current)};
    }

    /** Disciplines not yet done on this day, in display order. */
    public static List<String> missing(HistoryRepository.DayRecord r) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < LABELS.length; i++) if (!isDone(r, i)) out.add(LABELS[i]);
        return out;
    }

    /** How many of the days in [fromKey, toKey] had {@code discipline} done. */
    public static int countBetween(Map<String, HistoryRepository.DayRecord> days, String fromKey, String toKey, int discipline) {
        int n = 0;
        for (Map.Entry<String, HistoryRepository.DayRecord> e : days.entrySet()) {
            String k = e.getKey();
            if (k.compareTo(fromKey) >= 0 && k.compareTo(toKey) <= 0 && isDone(e.getValue(), discipline)) n++;
        }
        return n;
    }

    /**
     * Days before each of a verse's five reviews, counted from the review (or learning) before it.
     * Learned on day 0, it's reviewed on days 1, 3, 5, 6 and 7; a Got it on the fifth masters it,
     * so a verse takes a week.
     */
    public static final int[] REVIEW_GAPS = {1, 2, 2, 1, 1};

    /**
     * Where a verse waiting for review number {@code idx} (0-based) should be due, given the day it
     * was last reviewed: never later than that day plus its gap. Returns the earlier date when the
     * stored one is later (dates set by an older, slower schedule), or null when it's fine as it is.
     */
    public static Date pulledForward(Date nextReview, String lastReviewedDay, int idx) {
        if (nextReview == null || lastReviewedDay == null || idx < 0 || idx >= REVIEW_GAPS.length) return null;
        Date last;
        try {
            last = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(lastReviewedDay);
        } catch (ParseException e) {
            return null;
        }
        if (last == null) return null;
        Calendar latest = Calendar.getInstance();
        latest.setTime(last);
        latest.add(Calendar.DAY_OF_YEAR, REVIEW_GAPS[idx]);
        // Compare whole days: due "on" the latest day is fine whatever the hour.
        Calendar dayAfter = (Calendar) latest.clone();
        dayAfter.add(Calendar.DAY_OF_YEAR, 1);
        return nextReview.before(dayAfter.getTime()) ? null : latest.getTime();
    }

    /** The Monday of the week {@code day} is in (weeks run Monday to Sunday, as on the home screen). */
    public static Calendar weekStart(Calendar day) {
        Calendar c = (Calendar) day.clone();
        int back = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7; // Monday 0 … Sunday 6
        c.add(Calendar.DAY_OF_YEAR, -back);
        return c;
    }

    /** A verse is due any time on or after its review day (not only after the exact minute). */
    public static boolean isDueOn(Date nextReview, Date now) {
        if (nextReview == null) return false;
        Calendar tomorrow = Calendar.getInstance();
        tomorrow.setTime(now);
        tomorrow.add(Calendar.DAY_OF_YEAR, 1);
        tomorrow.set(Calendar.HOUR_OF_DAY, 0);
        tomorrow.set(Calendar.MINUTE, 0);
        tomorrow.set(Calendar.SECOND, 0);
        tomorrow.set(Calendar.MILLISECOND, 0);
        return nextReview.before(tomorrow.getTime());
    }

    static String key(Calendar c) {
        return ActivityLog.dayKey(c.getTime());
    }

    static Calendar calendarFor(String dayKey) {
        try {
            Calendar c = Calendar.getInstance();
            c.setTime(new SimpleDateFormat(ActivityLog.DATE_FMT, Locale.US).parse(dayKey));
            return c;
        } catch (ParseException | NullPointerException e) {
            return null;
        }
    }
}
