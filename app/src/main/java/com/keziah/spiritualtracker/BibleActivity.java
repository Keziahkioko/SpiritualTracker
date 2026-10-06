package com.keziah.spiritualtracker;

import com.google.firebase.messaging.FirebaseMessaging;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class BibleActivity extends AppCompatActivity {

    private View btnNudge;
    private CheckBox cbRead;
    private EditText etVerse;
    private Button btnSaveVerse;

    private TextView tvPartnerStatus, tvPartnerStatusIcon, tvPartnerVerse;
    private ImageView imgPartnerStatusIcon;
    private View layoutPartnerVerse;
    private FrameLayout framePartnerIcon;

    private FirebaseAuth auth;
    private FirebaseFirestore db;
    private String currentUserId;
    private String partnerId = null;
    private String todayDate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bible);

        // Save FCM token — guarded with null check
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (!task.isSuccessful()) return;
            String token = task.getResult();
            FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
            if (u != null) {
                FirebaseFirestore.getInstance().collection("users")
                        .document(u.getUid()).update("fcmToken", token);
            }
        });

        auth = FirebaseAuth.getInstance();
        db   = FirebaseFirestore.getInstance();

        // ── BUG FIX: guard getCurrentUser() before calling getUid() ──────
        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser == null) {
            // Session expired — send back to login
            finish();
            return;
        }
        currentUserId = currentUser.getUid();
        // ─────────────────────────────────────────────────────────────────

        todayDate = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());

        cbRead          = findViewById(R.id.cbRead);
        etVerse         = findViewById(R.id.etVerse);
        btnSaveVerse    = findViewById(R.id.btnSaveVerse);
        btnNudge        = findViewById(R.id.btnNudge);
        tvPartnerStatus     = findViewById(R.id.tvPartnerStatus);
        tvPartnerStatusIcon = findViewById(R.id.tvPartnerStatusIcon);
        imgPartnerStatusIcon = findViewById(R.id.imgPartnerStatusIcon);
        tvPartnerVerse      = findViewById(R.id.tvPartnerVerse);
        layoutPartnerVerse  = findViewById(R.id.layoutPartnerVerse);
        framePartnerIcon    = findViewById(R.id.framePartnerIcon);

        fetchPartnerId();
        setupListeners();
    }

    private void fetchPartnerId() {
        db.collection("users").document(currentUserId).get()
                .addOnSuccessListener(documentSnapshot -> {
                    if (documentSnapshot.exists() && documentSnapshot.contains("partnerId")) {
                        partnerId = documentSnapshot.getString("partnerId");
                        listenToDailyReadings();
                    } else {
                        tvPartnerStatus.setText("No Partner Linked");
                    }
                });
    }

    private void listenToDailyReadings() {
        db.collection("daily_readings").document(todayDate)
                .addSnapshotListener((documentSnapshot, e) -> {
                    if (e != null) return;
                    if (documentSnapshot != null && documentSnapshot.exists()) {
                        if (documentSnapshot.contains(currentUserId)) {
                            Boolean myStatus = documentSnapshot.getBoolean(currentUserId);
                            cbRead.setChecked(Boolean.TRUE.equals(myStatus));
                            String myVerse = documentSnapshot.getString(currentUserId + "_verse");
                            if (myVerse != null && !myVerse.isEmpty() && !etVerse.hasFocus()) {
                                etVerse.setText(myVerse);
                            }
                        }
                        if (partnerId != null && documentSnapshot.contains(partnerId)) {
                            Boolean partnerStatus = documentSnapshot.getBoolean(partnerId);
                            updatePartnerUI(Boolean.TRUE.equals(partnerStatus),
                                    documentSnapshot.getString(partnerId + "_verse"));
                        } else {
                            updatePartnerUI(false, null);
                        }
                    } else {
                        cbRead.setChecked(false);
                        updatePartnerUI(false, null);
                    }
                });
    }

    private void updatePartnerUI(boolean hasRead, String partnerVerseText) {
        if (hasRead) {
            tvPartnerStatus.setText("Your partner completed today's reading!");
            tvPartnerStatus.setTextColor(Color.parseColor("#212121"));
            tvPartnerStatusIcon.setVisibility(View.GONE);
            imgPartnerStatusIcon.setVisibility(View.VISIBLE);
            btnNudge.setVisibility(View.GONE);
            imgPartnerStatusIcon.setImageResource(R.drawable.ic_partner_done);
            framePartnerIcon.setBackgroundResource(0);
            if (partnerVerseText != null && !partnerVerseText.trim().isEmpty()) {
                layoutPartnerVerse.setVisibility(View.VISIBLE);
                tvPartnerVerse.setText("\u201c" + partnerVerseText.trim() + "\u201d");
            } else {
                layoutPartnerVerse.setVisibility(View.GONE);
            }
        } else {
            tvPartnerStatus.setText("Waiting for partner...");
            tvPartnerStatus.setTextColor(Color.parseColor("#616161"));
            imgPartnerStatusIcon.setVisibility(View.GONE);
            tvPartnerStatusIcon.setVisibility(View.VISIBLE);
            tvPartnerStatusIcon.setText("⏳");
            btnNudge.setVisibility(View.VISIBLE);
            layoutPartnerVerse.setVisibility(View.GONE);
            framePartnerIcon.setBackgroundResource(R.drawable.circle_bg_grey);
        }
    }

    private void setupListeners() {
        cbRead.setOnClickListener(view -> {
            boolean isChecked = cbRead.isChecked();
            String verseText = etVerse.getText().toString();
            Map<String, Object> updateData = new HashMap<>();
            updateData.put(currentUserId, isChecked);
            updateData.put(currentUserId + "_verse", verseText);
            db.collection("daily_readings").document(todayDate)
                    .set(updateData, SetOptions.merge())
                    .addOnSuccessListener(aVoid -> {
                        if (isChecked) {
                            Toast.makeText(this, "Marked as Read!", Toast.LENGTH_SHORT).show();
                            checkIfTeamIsDone();
                            incrementPartnerUnread("unreadBible");
                            notifyPartner("Goal Completed! ✅",
                                    "Your partner just finished their reading for today!", "bible_update");
                        }
                    });
        });

        btnSaveVerse.setOnClickListener(view -> {
            String verseText = etVerse.getText().toString();
            Map<String, Object> verseData = new HashMap<>();
            verseData.put(currentUserId + "_verse", verseText);
            db.collection("daily_readings").document(todayDate)
                    .set(verseData, SetOptions.merge())
                    .addOnSuccessListener(aVoid ->
                            Toast.makeText(this, "Note Saved!", Toast.LENGTH_SHORT).show());
        });

        btnNudge.setOnClickListener(view -> {
            btnNudge.animate().scaleX(0.8f).scaleY(0.8f).setDuration(100)
                    .withEndAction(() -> btnNudge.animate().scaleX(1f).scaleY(1f).setDuration(100));
            if (partnerId == null) {
                Toast.makeText(this, "No partner linked yet!", Toast.LENGTH_SHORT).show();
                return;
            }
            incrementPartnerUnread("unreadBible");
            notifyPartner("You've been nudged! ✨",
                    "Your partner is reminding you to read the Word! 📖", "bible_nudge");
            Toast.makeText(this, "Nudge sent! ✨", Toast.LENGTH_SHORT).show();
        });
    }

    private void checkIfTeamIsDone() {
        if (partnerId == null) return;
        db.runTransaction(transaction -> {
            DocumentSnapshot daily = transaction.get(
                    db.collection("daily_readings").document(todayDate));
            boolean meDone      = Boolean.TRUE.equals(daily.getBoolean(currentUserId));
            boolean partnerDone = Boolean.TRUE.equals(daily.getBoolean(partnerId));
            boolean awarded     = Boolean.TRUE.equals(daily.getBoolean("score_awarded"));
            if (meDone && partnerDone && !awarded) {
                DocumentSnapshot userSnap = transaction.get(
                        db.collection("users").document(currentUserId));
                Long cur = userSnap.getLong("sharedScore");
                if (cur == null) cur = 0L;
                Long newScore = cur + 1;
                transaction.update(db.collection("users").document(currentUserId), "sharedScore", newScore);
                transaction.update(db.collection("users").document(partnerId), "sharedScore", newScore);
                transaction.update(db.collection("daily_readings").document(todayDate), "score_awarded", true);
                return newScore;
            }
            return null;
        }).addOnSuccessListener(result -> {
            if (result != null)
                Toast.makeText(this, "Streak Increased! New Score: " + result, Toast.LENGTH_LONG).show();
        });
    }

    private void incrementPartnerUnread(String field) {
        if (partnerId != null && !partnerId.isEmpty())
            db.collection("users").document(partnerId).update(field, FieldValue.increment(1));
    }

    private void notifyPartner(String title, String message, String type) {
        if (partnerId == null) return;
        db.collection("users").document(partnerId).get().addOnSuccessListener(doc -> {
            if (doc.exists() && doc.contains("fcmToken")) {
                sendNotificationToServer(doc.getString("fcmToken"), title, message, type);
            }
        });
    }

    private void sendNotificationToServer(String targetToken, String title, String messageBody, String type) {
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        try {
            org.json.JSONObject json = new org.json.JSONObject();
            json.put("token", targetToken);
            json.put("title", title);
            json.put("body",  messageBody);
            json.put("type",  type);
            json.put("docId", "");
            okhttp3.RequestBody body = okhttp3.RequestBody.create(
                    json.toString(), okhttp3.MediaType.get("application/json; charset=utf-8"));
            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url("https://YOUR-PIPEDREAM-ENDPOINT").post(body).build();
            client.newCall(request).enqueue(new okhttp3.Callback() {
                @Override public void onFailure(okhttp3.Call call, java.io.IOException e) {}
                @Override public void onResponse(okhttp3.Call call, okhttp3.Response r)
                        throws java.io.IOException { r.close(); }
            });
        } catch (Exception e) { e.printStackTrace(); }
    }
}
