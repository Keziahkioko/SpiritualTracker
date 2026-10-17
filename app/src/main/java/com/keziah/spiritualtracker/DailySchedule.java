package com.keziah.spiritualtracker;

import android.content.Context;

import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ListenableWorker;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

/**
 * Reminders at a set time of day. Each run is booked for one exact time and books the next one
 * itself, instead of a "every 24 hours" job: those drift whenever a run is late (phone off,
 * battery saver), and once drifted they kept firing at the wrong time, e.g. the evening
 * reminder arriving in the morning.
 */
final class DailySchedule {

    /** A reminder that runs this much later than planned is skipped rather than shown at an odd time. */
    private static final int LATE_HOURS = 3;

    private DailySchedule() {}

    /**
     * Books the next run at {@code hour}:00 (on {@code dayOfWeek}, or any day if -1), at least an
     * hour from now. Safe to call often: the same slot is only booked once.
     */
    static void bookNext(Context context, String name, Class<? extends ListenableWorker> worker,
                         int dayOfWeek, int hour, Data input) {
        bookNext(context, name, worker, dayOfWeek, hour, 0, input);
    }

    /** As above, at {@code hour}:{@code minute}. */
    static void bookNext(Context context, String name, Class<? extends ListenableWorker> worker,
                         int dayOfWeek, int hour, int minute, Data input) {
        Calendar next = nextAt(dayOfWeek, hour, minute, System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1));
        long delay = Math.max(0, next.getTimeInMillis() - System.currentTimeMillis());
        // One name per slot, so the run that's finishing can book tomorrow's without replacing itself.
        WorkManager.getInstance(context).enqueueUniqueWork(
                name + "_" + ActivityLog.dayKey(next.getTime()),
                ExistingWorkPolicy.KEEP,
                new OneTimeWorkRequest.Builder(worker)
                        .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                        .setInputData(input)
                        .build());
    }

    /** True from {@code hour}:00 until a few hours after; outside that, a reminder would arrive at an odd time. */
    static boolean onTime(int hour) {
        int now = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return now >= hour && now < hour + LATE_HOURS;
    }

    /** Next {@code hour}:00 after {@code afterMillis}, optionally on a given weekday (-1 = any day). */
    static Calendar nextAt(int dayOfWeek, int hour, long afterMillis) {
        return nextAt(dayOfWeek, hour, 0, afterMillis);
    }

    static Calendar nextAt(int dayOfWeek, int hour, int minute, long afterMillis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(afterMillis);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        while (c.getTimeInMillis() <= afterMillis
                || (dayOfWeek > 0 && c.get(Calendar.DAY_OF_WEEK) != dayOfWeek)) {
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return c;
    }
}
