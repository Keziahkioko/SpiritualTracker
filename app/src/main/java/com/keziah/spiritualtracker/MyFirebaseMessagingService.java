package com.keziah.spiritualtracker;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class MyFirebaseMessagingService extends FirebaseMessagingService {

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        String title = "New Update";
        String body = "You have a new activity.";
        String type = "default"; // Default fallback
        String docId = "";

        // 1. Get standard notification text (if it exists)
        if (remoteMessage.getNotification() != null) {
            title = remoteMessage.getNotification().getTitle();
            body = remoteMessage.getNotification().getBody();
        }

        // 2. Extract custom hidden DATA sent from backend/Firebase
        if (remoteMessage.getData().size() > 0) {
            if (remoteMessage.getData().containsKey("title")) title = remoteMessage.getData().get("title");
            if (remoteMessage.getData().containsKey("body")) body = remoteMessage.getData().get("body");
            if (remoteMessage.getData().containsKey("type")) type = remoteMessage.getData().get("type");
            if (remoteMessage.getData().containsKey("docId")) docId = remoteMessage.getData().get("docId");
        }

        showNotification(title, body, type, docId);
    }

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        saveTokenToDatabase(token);
    }

    private void showNotification(String title, String body, String type, String docId) {
        String channelId = "JournalChatChannel";
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId,
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
                intent = new Intent(this, BibleActivity.class);
                break;

            case "journal_chat":
            case "journal_new":
            case "journal":
                intent = new Intent(this, JournalListActivity.class);
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
                intent = new Intent(this, PrayerActivity.class);
                break;

            default:
                // If it doesn't match anything, just open the dashboard safely
                intent = new Intent(this, MainActivity.class);
                break;
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        // We use a unique request code so Intents don't overwrite each other
        int requestCode = (int) System.currentTimeMillis();
        PendingIntent pendingIntent = PendingIntent.getActivity(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(body)
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
                    .update("fcmToken", token);
        }
    }
}