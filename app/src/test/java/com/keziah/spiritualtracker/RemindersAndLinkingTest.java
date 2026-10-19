package com.keziah.spiritualtracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.firebase.Timestamp;

import org.junit.Test;

import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Prayer reminder days, reminder scheduling, verse references and invite codes. */
public class RemindersAndLinkingTest {

    private static Set<Integer> days(Integer... d) {
        return new HashSet<>(Arrays.asList(d));
    }

    private static Calendar date(int y, int m, int d, int hour, int min) {
        return new GregorianCalendar(y, m - 1, d, hour, min);
    }

    private static Timestamp at(Calendar c) {
        return new Timestamp(c.getTime());
    }

    // ── Which days a prayer reminder comes ──────────────────────────────

    @Test
    public void dueOnlyOnChosenDays() {
        Calendar monday = date(2026, 10, 19, 6, 30); // a Monday
        Timestamp lastWeek = at(date(2026, 10, 12, 9, 0));
        assertTrue(PrayerReminders.dueOn(days(Calendar.MONDAY, Calendar.FRIDAY), lastWeek, monday));
        assertFalse(PrayerReminders.dueOn(days(Calendar.TUESDAY), lastWeek, monday));
    }

    @Test
    public void notDueOnTheDayItWasPosted() {
        Calendar monday = date(2026, 10, 19, 6, 30);
        assertFalse(PrayerReminders.dueOn(days(Calendar.MONDAY), at(date(2026, 10, 19, 5, 0)), monday));
    }

    @Test
    public void withoutAChoiceTheDayItWasPostedIsUsed() {
        Timestamp postedTuesday = at(date(2026, 10, 13, 9, 0));
        assertEquals(days(Calendar.TUESDAY), PrayerReminders.daysFor(new HashMap<>(), "shared:x", postedTuesday));
        Map<String, Set<Integer>> chosen = new HashMap<>();
        chosen.put("shared:x", days(Calendar.SUNDAY));
        assertEquals(days(Calendar.SUNDAY), PrayerReminders.daysFor(chosen, "shared:x", postedTuesday));
    }

    @Test
    public void dayListsReadNaturally() {
        assertEquals("Daily", PrayerReminders.shortList(days(1, 2, 3, 4, 5, 6, 7)));
        assertEquals("Weekdays", PrayerReminders.shortList(days(2, 3, 4, 5, 6)));
        assertEquals("Weekends", PrayerReminders.shortList(days(1, 7)));
        assertEquals("every day", PrayerReminders.longList(days(1, 2, 3, 4, 5, 6, 7)));
        assertEquals("every weekday", PrayerReminders.longList(days(2, 3, 4, 5, 6)));
    }

    @Test
    public void longFocusItemsAreShortened() {
        assertEquals("Healing for Mum", PrayerReminderWorker.shorten("\n  Healing   for Mum\nShe has surgery on Friday"));
        String long_ = "a".repeat(100);
        String s = PrayerReminderWorker.shorten(long_);
        assertTrue(s.length() <= 60);
        assertTrue(s.endsWith("…"));
    }

    // ── Reminder times ──────────────────────────────────────────────────

    @Test
    public void nextSlotIsLaterTodayOrTomorrow() {
        long morning = date(2026, 10, 19, 5, 0).getTimeInMillis();
        Calendar next = DailySchedule.nextAt(-1, 6, 30, morning);
        assertEquals(19, next.get(Calendar.DAY_OF_MONTH));
        assertEquals(6, next.get(Calendar.HOUR_OF_DAY));
        assertEquals(30, next.get(Calendar.MINUTE));

        long evening = date(2026, 10, 19, 21, 0).getTimeInMillis();
        assertEquals(20, DailySchedule.nextAt(-1, 20, evening).get(Calendar.DAY_OF_MONTH));
    }

    @Test
    public void weeklySlotLandsOnItsWeekday() {
        long monday = date(2026, 10, 19, 12, 0).getTimeInMillis();
        Calendar sunday = DailySchedule.nextAt(Calendar.SUNDAY, 19, monday);
        assertEquals(Calendar.SUNDAY, sunday.get(Calendar.DAY_OF_WEEK));
        assertEquals(25, sunday.get(Calendar.DAY_OF_MONTH));
    }

    @Test
    public void slotExactlyNowMovesOn() {
        long sixThirty = date(2026, 10, 19, 6, 30).getTimeInMillis();
        assertEquals(20, DailySchedule.nextAt(-1, 6, 30, sixThirty).get(Calendar.DAY_OF_MONTH));
    }

    // ── Verse references for parts of a passage ─────────────────────────

    private static MemoryVerse part(String reference, String text) {
        MemoryVerse v = new MemoryVerse();
        v.setReference(reference);
        v.setText(text);
        return v;
    }

    @Test
    public void partShowsTheVersesItHolds() {
        assertEquals("Exodus 15:1-3 (Part 1 of 7)",
                part("Exodus 15:1-21 (Part 1 of 7)", "1 Then Moses sang\n2 The Lord is my strength\n3 The Lord is a warrior")
                        .shownReference());
        assertEquals("Exodus 15:4 (Part 2 of 7)",
                part("Exodus 15:1-21 (Part 2 of 7)", "4 Pharaoh's chariots").shownReference());
    }

    @Test
    public void partWithoutNumbersKeepsItsName() {
        assertEquals("Psalm 23 (Part 1 of 2)", part("Psalm 23 (Part 1 of 2)", "The Lord is my shepherd").shownReference());
        assertEquals("John 3:16", part("John 3:16", "16 For God so loved").shownReference());
        // Numbers out of order: keep the typed name rather than show something wrong.
        assertEquals("Ps 1 (Part 1 of 2)", part("Ps 1 (Part 1 of 2)", "3 a\n2 b").shownReference());
    }

    // ── Invite codes ────────────────────────────────────────────────────

    @Test
    public void codesAreTypedForgivingly() {
        assertEquals("ABCD23", PartnerLink.normalize(" abc-d23 "));
        assertEquals("", PartnerLink.normalize(null));
    }

    @Test
    public void newCodesAreUnambiguousAndVary() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String code = PartnerLink.newCode();
            assertEquals(code, PartnerLink.normalize(code));
            seen.add(code);
        }
        assertTrue("codes should be random", seen.size() > 190);
    }
}
