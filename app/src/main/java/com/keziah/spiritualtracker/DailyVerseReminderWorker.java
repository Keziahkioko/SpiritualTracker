package com.keziah.spiritualtracker;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

public class DailyVerseReminderWorker extends Worker {

    private static final String TAG       = "DailyVerseWorker";
    private static final String WORK_NAME = "daily_verse_reminder";
    private static final String NOTIFY_URL =
            BuildConfig.NOTIFY_URL;

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

        CountDownLatch          latch    = new CountDownLatch(1);
        AtomicInteger           dueCount = new AtomicInteger(0);
        AtomicReference<String> token    = new AtomicReference<>(null);

        db.collection("users").document(userId).get()
                .addOnSuccessListener(userDoc -> {
                    token.set(userDoc.getString("fcmToken"));

                    // ── Fixed query: only filter by nextReviewDate ──
                    // We filter out mastered manually to avoid needing a composite index
                    db.collection("users").document(userId)
                            .collection("memory_verses")
                            .whereLessThan("nextReviewDate",
                                    new com.google.firebase.Timestamp(new Date()))
                            .get()
                            .addOnSuccessListener(query -> {
                                int count = 0;
                                for (QueryDocumentSnapshot doc : query) {
                                    String stage = doc.getString("stage");
                                    // Exclude mastered verses in code, not in query
                                    if (!"mastered".equals(stage)) count++;
                                }
                                dueCount.set(count);
                                latch.countDown();
                            })
                            .addOnFailureListener(e -> {
                                Log.e(TAG, "Query failed", e);
                                latch.countDown();
                            });
                })
                .addOnFailureListener(e -> latch.countDown());

        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return Result.retry();
        }

        int    due = dueCount.get();
        String tok = token.get();

        if (due > 0 && tok != null) {
            String title = "📖 " + due + " verse" + (due > 1 ? "s" : "") + " to review today";
            String body  = "Keep your memorization streak going!";
            sendPush(tok, title, body);
            Log.d(TAG, "Sent due-today notification for " + due + " verses");
        } else {
            Log.d(TAG, "No due verses or no FCM token — skipping notification");
        }

        return Result.success();
    }

    private void sendPush(String token, String title, String body) {
        OkHttpClient client = new OkHttpClient();
        try {
            JSONObject json = new JSONObject();
            json.put("token", token);
            json.put("title", title);
            json.put("body",  body);

            RequestBody rb = RequestBody.create(
                    json.toString(),
                    MediaType.get("application/json; charset=utf-8"));

            Request req = new Request.Builder()
                    .url(NOTIFY_URL)
                    .post(rb)
                    .build();

            // Synchronous — we're already on a background thread
            okhttp3.Response response = client.newCall(req).execute();
            Log.d(TAG, "Push response: " + response.code());

        } catch (Exception e) {
            Log.e(TAG, "Push failed", e);
        }
    }
}
