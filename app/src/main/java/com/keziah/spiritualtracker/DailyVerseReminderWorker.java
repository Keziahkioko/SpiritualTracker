package com.keziah.spiritualtracker;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;

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

    public static void schedule(Context context) {
        long nowMillis    = System.currentTimeMillis();
        long eightAm      = getNext8AmMillis();
        long initialDelay = eightAm - nowMillis;
        if (initialDelay < 0) initialDelay += TimeUnit.DAYS.toMillis(1);

        PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(
                DailyVerseReminderWorker.class, 1, TimeUnit.DAYS)
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                work);

        Log.d(TAG, "Daily verse reminder scheduled, fires in " +
                TimeUnit.MILLISECONDS.toMinutes(initialDelay) + " minutes");
    }

    private static long getNext8AmMillis() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 8);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    @NonNull
    @Override
    public Result doWork() {
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

        // Only filter by nextReviewDate; mastered verses are skipped here to avoid a composite index.
        db.collection("users").document(userId)
                .collection("memory_verses")
                .whereLessThan("nextReviewDate", new Timestamp(tomorrow.getTime()))
                .get()
                .addOnSuccessListener(query -> {
                    int count = 0;
                    for (QueryDocumentSnapshot doc : query) {
                        if (!"mastered".equals(doc.getString("stage"))) count++;
                    }
                    dueCount.set(count);
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
