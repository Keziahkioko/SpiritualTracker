package com.keziah.spiritualtracker;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.Calendar;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class DailyVerseReminderWorker extends Worker {

    private static final String TAG       = "DailyVerseWorker";
    private static final String WORK_NAME = "daily_verse_reminder";

    public DailyVerseReminderWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    private static final int HOUR = 8;

    public static void schedule(Context context) {
        // The old every-24-hours job drifted (see DailySchedule); stop it.
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
        book(context);
    }

    private static void book(Context context) {
        DailySchedule.bookNext(context, "verse", DailyVerseReminderWorker.class, -1, HOUR, Data.EMPTY);
    }

    @NonNull
    @Override
    public Result doWork() {
        // Book tomorrow's first, so a failure below can't end the chain.
        book(getApplicationContext());
        if (!DailySchedule.onTime(HOUR)) return Result.success();

        FirebaseAuth auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() == null) return Result.success();

        String            userId = auth.getCurrentUser().getUid();
        FirebaseFirestore db     = FirebaseFirestore.getInstance();

        CountDownLatch latch    = new CountDownLatch(1);
        AtomicInteger  dueCount = new AtomicInteger(0);
        AtomicBoolean  failed   = new AtomicBoolean(false);

        // Anything scheduled before tomorrow's midnight is due today.
        Calendar tomorrow = Calendar.getInstance();
        tomorrow.add(Calendar.DAY_OF_YEAR, 1);
        tomorrow.set(Calendar.HOUR_OF_DAY, 0);
        tomorrow.set(Calendar.MINUTE, 0);
        tomorrow.set(Calendar.SECOND, 0);
        tomorrow.set(Calendar.MILLISECOND, 0);

        // Everything scheduled for today, refreshes of mastered verses included.
        db.collection("users").document(userId)
                .collection("memory_verses")
                .whereLessThan("nextReviewDate", new Timestamp(tomorrow.getTime()))
                .get()
                .addOnSuccessListener(query -> {
                    dueCount.set(query.size());
                    latch.countDown();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Query failed", e);
                    failed.set(true);
                    latch.countDown();
                });

        try {
            if (!latch.await(20, TimeUnit.SECONDS)) return Result.retry();
        } catch (InterruptedException e) {
            return Result.retry();
        }
        if (failed.get()) return Result.retry();

        int due = dueCount.get();
        if (due > 0) {
            // Shown directly on this phone; no need to go through the push relay to notify yourself.
            String title = "📖 " + due + " verse" + (due > 1 ? "s" : "") + " to review today";
            MyFirebaseMessagingService.showNotification(getApplicationContext(),
                    title, "Keep your memorization streak going!", "verse_reminder", "");
            Log.d(TAG, "Shown due-today reminder for " + due + " verses");
        }
        return Result.success();
    }
}
