package com.keziah.spiritualtracker;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.TreeMap;

public class ProgressMathTest {

    private static HistoryRepository.DayRecord day(String key, boolean bible, boolean prayed,
                                                   boolean journal, boolean memorize) {
        HistoryRepository.DayRecord r = new HistoryRepository.DayRecord(key);
        r.bible = bible;
        r.prayed = prayed;
        r.journal = journal;
        r.memorize = memorize;
        return r;
    }

    private static TreeMap<String, HistoryRepository.DayRecord> bibleOn(String... keys) {
        TreeMap<String, HistoryRepository.DayRecord> days = new TreeMap<>();
        for (String k : keys) days.put(k, day(k, true, false, false, false));
        return days;
    }

    // ── Streaks ─────────────────────────────────────────────────────────

    @Test
    public void emptyHistoryHasNoStreak() {
        assertArrayEquals(new int[]{0, 0}, ProgressMath.streaks(new TreeMap<>(), 0, "2026-10-07"));
    }

    @Test
    public void currentStreakCountsBackFromToday() {
        TreeMap<String, HistoryRepository.DayRecord> days = bibleOn("2026-10-05", "2026-10-06", "2026-10-07");
        assertArrayEquals(new int[]{3, 3}, ProgressMath.streaks(days, 0, "2026-10-07"));
    }

    @Test
    public void unfinishedTodayDoesNotBreakTheStreak() {
        TreeMap<String, HistoryRepository.DayRecord> days = bibleOn("2026-10-05", "2026-10-06");
        assertArrayEquals(new int[]{2, 2}, ProgressMath.streaks(days, 0, "2026-10-07"));
    }

    @Test
    public void missedYesterdayResetsCurrentButKeepsBest() {
        TreeMap<String, HistoryRepository.DayRecord> days =
                bibleOn("2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04", "2026-10-07");
        assertArrayEquals(new int[]{1, 4}, ProgressMath.streaks(days, 0, "2026-10-07"));
    }

    @Test
    public void streakCrossesMonthAndYearBoundaries() {
        TreeMap<String, HistoryRepository.DayRecord> days = bibleOn("2025-12-30", "2025-12-31", "2026-01-01");
        assertArrayEquals(new int[]{3, 3}, ProgressMath.streaks(days, 0, "2026-01-01"));
    }

    @Test
    public void allFourRequiresEveryDisciplineOnTheSameDay() {
        TreeMap<String, HistoryRepository.DayRecord> days = new TreeMap<>();
        days.put("2026-10-06", day("2026-10-06", true, true, true, true));
        days.put("2026-10-07", day("2026-10-07", true, true, false, true));
        // Today isn't complete yet, so yesterday's full day still counts as the current run.
        assertArrayEquals(new int[]{1, 1}, ProgressMath.streaks(days, ProgressMath.ALL, "2026-10-07"));
    }

    @Test
    public void disciplinesAreTrackedSeparately() {
        TreeMap<String, HistoryRepository.DayRecord> days = new TreeMap<>();
        days.put("2026-10-06", day("2026-10-06", false, true, false, false));
        days.put("2026-10-07", day("2026-10-07", true, true, false, false));
        assertArrayEquals(new int[]{1, 1}, ProgressMath.streaks(days, 0, "2026-10-07"));
        assertArrayEquals(new int[]{2, 2}, ProgressMath.streaks(days, 1, "2026-10-07"));
        assertArrayEquals(new int[]{0, 0}, ProgressMath.streaks(days, 2, "2026-10-07"));
    }

    // ── Missing today / weekly counts ───────────────────────────────────

    @Test
    public void missingListsUndoneDisciplinesInOrder() {
        assertEquals(Arrays.asList("Prayer", "Memorize"),
                ProgressMath.missing(day("k", true, false, true, false)));
        assertEquals(Collections.emptyList(), ProgressMath.missing(day("k", true, true, true, true)));
        assertEquals(Arrays.asList(ProgressMath.LABELS), ProgressMath.missing(null));
    }

    @Test
    public void countBetweenOnlyCountsDaysInRange() {
        TreeMap<String, HistoryRepository.DayRecord> days =
                bibleOn("2026-09-30", "2026-10-01", "2026-10-04", "2026-10-07", "2026-10-08");
        assertEquals(3, ProgressMath.countBetween(days, "2026-10-01", "2026-10-07", 0));
        assertEquals(0, ProgressMath.countBetween(days, "2026-10-01", "2026-10-07", 1));
    }

    // ── Memory verse due dates ──────────────────────────────────────────

    @Test
    public void verseScheduledForLaterTodayIsAlreadyDue() {
        Calendar now = new GregorianCalendar(2026, Calendar.OCTOBER, 7, 8, 0);
        Calendar review = new GregorianCalendar(2026, Calendar.OCTOBER, 7, 21, 0);
        assertTrue(ProgressMath.isDueOn(review.getTime(), now.getTime()));
    }

    @Test
    public void verseScheduledForTomorrowIsNotDue() {
        Calendar now = new GregorianCalendar(2026, Calendar.OCTOBER, 7, 23, 59);
        Calendar review = new GregorianCalendar(2026, Calendar.OCTOBER, 8, 0, 1);
        assertFalse(ProgressMath.isDueOn(review.getTime(), now.getTime()));
    }

    @Test
    public void overdueVerseIsDue() {
        Calendar now = new GregorianCalendar(2026, Calendar.OCTOBER, 7, 12, 0);
        Calendar review = new GregorianCalendar(2026, Calendar.OCTOBER, 1, 9, 0);
        assertTrue(ProgressMath.isDueOn(review.getTime(), now.getTime()));
        assertFalse(ProgressMath.isDueOn(null, now.getTime()));
    }

    // ── Partner invite codes ────────────────────────────────────────────

    @Test
    public void inviteCodesAvoidLookAlikeCharacters() {
        for (int i = 0; i < 500; i++) {
            String code = PartnerLink.newCode();
            assertEquals(6, code.length());
            assertTrue(code, code.matches("[A-HJKMNP-Z2-9]{6}"));
        }
    }

    @Test
    public void enteredCodesAreNormalized() {
        assertEquals("ABC234", PartnerLink.normalize(" abc-234 "));
        assertEquals("ABC234", PartnerLink.normalize("ABC 234"));
        assertEquals("", PartnerLink.normalize(null));
    }
}
