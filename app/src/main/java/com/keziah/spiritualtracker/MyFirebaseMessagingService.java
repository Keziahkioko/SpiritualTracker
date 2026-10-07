package com.keziah.spiritualtracker;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Collections;

public class MyFirebaseMessagingService extends FirebaseMessagingService {

    private static final String CHANNEL_ID = "JournalChatChannel";

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        String title = "New Update";
        String body = "You have a new activity.";
        String type = "default"; // Default fallback
        String docId = "";

        // 1. Get standard notification text (if it exists)
        if (remoteMessage.getNotification() != null) {
            if (remoteMessage.getNotification().getTitle() != null) title = remoteMessage.getNotification().getTitle();
            if (remoteMessage.getNotification().getBody() != null) body = remoteMessage.getNotification().getBody();
        }

        // 2. Extract custom hidden DATA sent from backend/Firebase
        if (remoteMessage.getData().size() > 0) {
            if (remoteMessage.getData().containsKey("title")) title = remoteMessage.getData().get("title");
            if (remoteMessage.getData().containsKey("body")) body = remoteMessage.getData().get("body");
            if (remoteMessage.getData().containsKey("type")) type = remoteMessage.getData().get("type");
            if (remoteMessage.getData().containsKey("docId")) docId = remoteMessage.getData().get("docId");
        }

        showNotification(this, title, body, type, docId);
    }

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        saveTokenToDatabase(token);
    }

    /** Posts a notification that opens the screen matching {@code type}. Also used for local reminders. */
    public static void showNotification(Context context, String title, String body, String type, String docId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "App Notifications",
                    NotificationManager.IMPORTANCE_HIGH
            );
            notificationManager.createNotificationChannel(channel);
        }

        // 3. SMART ROUTING LOGIC (The Switchboard)
        Intent intent;
        if (type == null) type = "default";

        switch (type) {
            case "bible_nudge":
            case "bible_update":
            case "mark_read":
                intent = new Intent(context, BibleActivity.class);
                break;

            case "journal_chat":
            case "journal_new":
            case "journal":
                intent = new Intent(context, JournalListActivity.class);
                intent.putExtra("openFromNotification", true);
                intent.putExtra("targetDocId", docId);
                intent.putExtra("notificationType", type);
                break;

            case "prayer_nudge":
            case "prayer_chat":
            case "prayer_update":
                // Fallbacks for the old strings you were using, just in case!
            case "nudge":
            case "chat":
                intent = new Intent(context, PrayerActivity.class);
                break;

            case "memorize":
            case "verse_reminder":
                intent = new Intent(context, MemorizeActivity.class);
                break;

            default:
                // If it doesn't match anything, just open the dashboard safely
                intent = new Intent(context, MainActivity.class);
                break;
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        // We use a unique request code so Intents don't overwrite each other
        int requestCode = (int) System.currentTimeMillis();
        PendingIntent pendingIntent = PendingIntent.getActivity(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true) // Dismisses when tapped
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent);

        // Use a unique notification ID based on time so multiple notifications stack nicely
        int notificationId = (int) System.currentTimeMillis();
        notificationManager.notify(notificationId, builder.build());
    }

    private void saveTokenToDatabase(String token) {
        String currentUid = FirebaseAuth.getInstance().getUid();
        if (currentUid != null) {
            FirebaseFirestore.getInstance()
                    .collection("users")
                    .document(currentUid)
                    .set(Collections.singletonMap("fcmToken", token), SetOptions.merge());
        }
    }
}
