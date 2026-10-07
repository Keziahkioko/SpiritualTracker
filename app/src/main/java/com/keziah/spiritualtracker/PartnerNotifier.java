package com.keziah.spiritualtracker;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Sends push notifications and unread-badge bumps to the linked partner.
 * Pushes go through our relay (server/api/notify.js), which checks who is signed in and looks
 * up the partner's device itself, so the app never handles the partner's push token.
 */
public final class PartnerNotifier {

    private static final String TAG = "PartnerNotifier";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    static final OkHttpClient CLIENT = new OkHttpClient();

    private PartnerNotifier() {}

    public static void incrementUnread(String partnerId, String field) {
        if (partnerId == null || partnerId.isEmpty()) return;
        FirebaseFirestore.getInstance().collection("users").document(partnerId)
                .update(field, FieldValue.increment(1))
                .addOnFailureListener(e -> Log.w(TAG, "Unread bump failed", e));
    }

    /** {@code type} must be one the relay accepts; it decides which screen the tap opens. */
    public static void notifyPartner(String partnerId, String title, String body, String type, String docId) {
        if (partnerId == null || partnerId.isEmpty()) return;
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        String relay = BuildConfig.RELAY_URL;
        if (user == null) return;
        if (relay == null || relay.isEmpty()) {
            Log.w(TAG, "relay.url is not set in local.properties; skipping push");
            return;
        }
        user.getIdToken(false).addOnSuccessListener(result -> {
            try {
                JSONObject json = new JSONObject();
                json.put("title", title);
                json.put("body", body);
                json.put("type", type);
                json.put("docId", docId == null ? "" : docId);
                Request request = new Request.Builder()
                        .url(relay + "/api/notify")
                        .header("Authorization", "Bearer " + result.getToken())
                        .post(RequestBody.create(json.toString(), JSON))
                        .build();
                CLIENT.newCall(request).enqueue(new Callback() {
                    @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        Log.w(TAG, "Push request failed", e);
                    }
                    @Override public void onResponse(@NonNull Call call, @NonNull Response response) {
                        if (!response.isSuccessful()) Log.w(TAG, "Relay refused push: HTTP " + response.code());
                        response.close();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Could not build push request", e);
            }
        }).addOnFailureListener(e -> Log.w(TAG, "Couldn't get sign-in token for push", e));
    }
}
