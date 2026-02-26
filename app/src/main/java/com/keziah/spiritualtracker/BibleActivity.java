package com.keziah.spiritualtracker; // Make sure this matches your package name

import com.google.firebase.messaging.FirebaseMessaging;
import android.util.Log;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class BibleActivity extends AppCompatActivity {

    private View btnNudge;

    // UI Components
    private int currentScore = 0;
    private CheckBox cbRead;
    private EditText etVerse;
    private Button btnSaveVerse;

    // Partner UI Components
    private TextView tvPartnerStatus, tvPartnerLastRead, tvPartnerStatusIcon, tvPartnerVerse;
    private ImageView imgPartnerStatusIcon;
    private View layoutPartnerVerse;

    // Firebase
    private FirebaseAuth auth;
    private FirebaseFirestore db;
    private String currentUserId;
    private String partnerId = null;
    private String todayDate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bible);

        // --- FETCH AND SAVE FCM TOKEN ---
        FirebaseMessaging.getInstance().getToken()
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful()) {
                        android.util.Log.e("FCM_TEST", "FAILED to get token!", task.getException());
                        return;
                    }

                    String token = task.getResult();
                    if (FirebaseAuth.getInstance().getCurrentUser() != null) {
                        String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();
                        FirebaseFirestore.getInstance().collection("users").document(userId)
                                .update("fcmToken", token)
                                .addOnSuccessListener(aVoid -> android.util.Log.d("FCM_TEST", "Token saved!"))
                                .addOnFailureListener(e -> android.util.Log.e("FCM_TEST", "Failed to save token", e));
                    }
                });
        // ---------------------------------

        // 1. Initialize Firebase & UI
        auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        currentUserId = auth.getCurrentUser().getUid();
        todayDate = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());

        cbRead = findViewById(R.id.cbRead);
        etVerse = findViewById(R.id.etVerse);
        btnSaveVerse = findViewById(R.id.btnSaveVerse);
        btnNudge = findViewById(R.id.btnNudge);

        tvPartnerStatus = findViewById(R.id.tvPartnerStatus);
        tvPartnerLastRead = findViewById(R.id.tvPartnerLastRead);
        tvPartnerStatusIcon = findViewById(R.id.tvPartnerStatusIcon);
        imgPartnerStatusIcon = findViewById(R.id.imgPartnerStatusIcon);
        tvPartnerVerse = findViewById(R.id.tvPartnerVerse);
        layoutPartnerVerse = findViewById(R.id.layoutPartnerVerse);

        fetchPartnerId();
        setupListeners();
    }

    private void fetchPartnerId() {
        db.collection("users").document(currentUserId)
                .get()
                .addOnSuccessListener(documentSnapshot -> {
                    if (documentSnapshot.exists() && documentSnapshot.contains("partnerId")) {
                        partnerId = documentSnapshot.getString("partnerId");
                        listenToDailyReadings();
                    } else {
                        tvPartnerStatus.setText("No Partner Linked");
                        tvPartnerLastRead.setText("Link a partner in Database to see status.");
                    }
                });
    }

    private void listenToDailyReadings() {
        db.collection("daily_readings").document(todayDate)
                .addSnapshotListener((documentSnapshot, e) -> {
                    if (e != null) return;

                    if (documentSnapshot != null && documentSnapshot.exists()) {
                        if (documentSnapshot.contains(currentUserId)) {
                            boolean myStatus = documentSnapshot.getBoolean(currentUserId);
                            cbRead.setChecked(myStatus);

                            String myVerse = documentSnapshot.getString(currentUserId + "_verse");
                            if (myVerse != null && !myVerse.isEmpty() && !etVerse.hasFocus()) {
                                etVerse.setText(myVerse);
                            }
                        }

                        if (partnerId != null && documentSnapshot.contains(partnerId)) {
                            boolean partnerStatus = documentSnapshot.getBoolean(partnerId);
                            String partnerVerseText = documentSnapshot.getString(partnerId + "_verse");
                            updatePartnerUI(partnerStatus, partnerVerseText);
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
            tvPartnerStatus.setText("Partner has read! ");
            tvPartnerStatus.setTextColor(Color.parseColor("##8E24AA"));
            tvPartnerLastRead.setText("They completed their reading today.");

            tvPartnerStatusIcon.setVisibility(View.GONE);
            imgPartnerStatusIcon.setVisibility(View.VISIBLE);
            btnNudge.setVisibility(View.GONE);
            imgPartnerStatusIcon.setImageResource(R.drawable.ic_partner_done);

            if (partnerVerseText != null && !partnerVerseText.trim().isEmpty()) {
                layoutPartnerVerse.setVisibility(View.VISIBLE);
                tvPartnerVerse.setText(partnerVerseText);
            } else {
                layoutPartnerVerse.setVisibility(View.GONE);
            }
        } else {
            tvPartnerStatus.setText("Waiting for partner...");
            tvPartnerStatus.setTextColor(Color.parseColor("#616161"));
            tvPartnerLastRead.setText("No update yet today.");

            imgPartnerStatusIcon.setVisibility(View.GONE);
            tvPartnerStatusIcon.setVisibility(View.VISIBLE);
            tvPartnerStatusIcon.setText("⏳");
            btnNudge.setVisibility(View.VISIBLE);
            layoutPartnerVerse.setVisibility(View.GONE);
        }
    }

    private void setupListeners() {

        // 1. THE MAIN CHECKBOX
        cbRead.setOnClickListener(view -> {
            boolean isChecked = cbRead.isChecked();
            String verseText = etVerse.getText().toString();

            Map<String, Object> updateData = new HashMap<>();
            updateData.put(currentUserId, isChecked);
            updateData.put(currentUserId + "_verse", verseText);

            db.collection("daily_readings").document(todayDate)
                    .set(updateData, SetOptions.merge())
                    .addOnSuccessListener(aVoid -> {
                        if(isChecked) {
                            Toast.makeText(this, "Marked as Read!", Toast.LENGTH_SHORT).show();
                            checkIfTeamIsDone();

                            // 🔥 NEW: Automatically notify partner when checked!
                            notifyPartner("Goal Completed! ✅", "Your partner just finished their reading for today!");
                        }
                    });
        });

        // 2. SAVE VERSE BUTTON
        btnSaveVerse.setOnClickListener(view -> {
            String verseText = etVerse.getText().toString();
            Map<String, Object> verseData = new HashMap<>();
            verseData.put(currentUserId + "_verse", verseText);

            db.collection("daily_readings").document(todayDate)
                    .set(verseData, SetOptions.merge())
                    .addOnSuccessListener(aVoid -> Toast.makeText(this, "Note Saved!", Toast.LENGTH_SHORT).show());
        });

        // 3. NUDGE BUTTON
        btnNudge.setOnClickListener(view -> {
            btnNudge.animate().scaleX(0.8f).scaleY(0.8f).setDuration(100).withEndAction(() ->
                    btnNudge.animate().scaleX(1f).scaleY(1f).setDuration(100)
            );

            if (partnerId == null) {
                Toast.makeText(this, "No partner linked yet!", Toast.LENGTH_SHORT).show();
                return;
            }

            Toast.makeText(this, "Sending Nudge to partner... ✨", Toast.LENGTH_SHORT).show();

            // 🔥 NEW: Use our clean method to send the custom Nudge!
            notifyPartner("You've been nudged! ✨", "Your partner is reminding you to read the Word! 📖");
        });
    }

    private void checkIfTeamIsDone() {
        if (partnerId == null) return;

        db.runTransaction(transaction -> {
            DocumentSnapshot dailySnapshot = transaction.get(db.collection("daily_readings").document(todayDate));

            boolean meDone = dailySnapshot.contains(currentUserId) && Boolean.TRUE.equals(dailySnapshot.getBoolean(currentUserId));
            boolean partnerDone = dailySnapshot.contains(partnerId) && Boolean.TRUE.equals(dailySnapshot.getBoolean(partnerId));
            boolean alreadyAwarded = dailySnapshot.contains("score_awarded") && Boolean.TRUE.equals(dailySnapshot.getBoolean("score_awarded"));

            if (meDone && partnerDone && !alreadyAwarded) {
                DocumentSnapshot userSnapshot = transaction.get(db.collection("users").document(currentUserId));
                Long currentScore = userSnapshot.getLong("sharedScore");
                if (currentScore == null) currentScore = 0L;
                Long newScore = currentScore + 1;

                transaction.update(db.collection("users").document(currentUserId), "sharedScore", newScore);
                transaction.update(db.collection("users").document(partnerId), "sharedScore", newScore);
                transaction.update(db.collection("daily_readings").document(todayDate), "score_awarded", true);

                return newScore;
            } else {
                return null;
            }
        }).addOnSuccessListener(result -> {
            if (result != null) {
                Toast.makeText(this, "Streak Increased! New Score: " + result, Toast.LENGTH_LONG).show();
            }
        });
    }

    // --- HELPER METHOD: Grabs partner token and fires the payload ---
    private void notifyPartner(String title, String message) {
        if (partnerId == null) return;

        db.collection("users").document(partnerId).get()
                .addOnSuccessListener(documentSnapshot -> {
                    if (documentSnapshot.exists() && documentSnapshot.contains("fcmToken")) {
                        String partnerToken = documentSnapshot.getString("fcmToken");
                        sendNotificationToServer(partnerToken, title, message);
                    }
                })
                .addOnFailureListener(e -> android.util.Log.e("Nudge_Test", "Failed to get partner token", e));
    }

    // --- PIPEDREAM NETWORK CALL (Now accepts dynamic titles and messages) ---
    private void sendNotificationToServer(String targetToken, String title, String messageBody) {
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();

        try {
            org.json.JSONObject json = new org.json.JSONObject();
            json.put("token", targetToken);
            json.put("title", title);         // <--- NEW
            json.put("body", messageBody);    // <--- NEW

            okhttp3.RequestBody body = okhttp3.RequestBody.create(
                    json.toString(),
                    okhttp3.MediaType.get("application/json; charset=utf-8")
            );

            // ⚠️ MAKE SURE THIS IS YOUR REAL PIPEDREAM URL ⚠️
            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url("https://YOUR-PIPEDREAM-ENDPOINT")
                    .post(body)
                    .build();

            client.newCall(request).enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(okhttp3.Call call, java.io.IOException e) {
                    android.util.Log.e("Nudge_Test", "Failed to send notification request", e);
                }

                @Override
                public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                    android.util.Log.d("Nudge_Test", "Server says: " + response.body().string());
                }
            });
        } catch (Exception e) {
            android.util.Log.e("Nudge_Test", "Error building JSON", e);
        }
    }
}