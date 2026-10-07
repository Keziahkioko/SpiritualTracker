package com.keziah.spiritualtracker;

import android.util.Log;

import androidx.annotation.NonNull;

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

/** Sends push notifications and unread-badge bumps to the linked partner. */
public final class PartnerNotifier {

    private static final String TAG = "PartnerNotifier";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient CLIENT = new OkHttpClient();

    private PartnerNotifier() {}

    public static void incrementUnread(String partnerId, String field) {
        if (partnerId == null || partnerId.isEmpty()) return;
        FirebaseFirestore.getInstance().collection("users").document(partnerId)
                .update(field, FieldValue.increment(1))
                .addOnFailureListener(e -> Log.w(TAG, "Unread bump failed", e));
    }

    /** Looks up the partner's FCM token, then posts to the push relay at {@code relayUrl}. */
    public static void notifyPartner(String partnerId, String title, String body,
                                     String type, String docId, String relayUrl) {
        if (partnerId == null || partnerId.isEmpty()) return;
        FirebaseFirestore.getInstance().collection("users").document(partnerId).get()
                .addOnSuccessListener(doc -> {
                    String token = doc.getString("fcmToken");
                    if (token != null && !token.isEmpty()) send(token, title, body, type, docId, relayUrl);
                })
                .addOnFailureListener(e -> Log.w(TAG, "Partner token lookup failed", e));
    }

    public static void send(String token, String title, String body,
                            String type, String docId, String relayUrl) {
        if (relayUrl == null || relayUrl.isEmpty()) {
            Log.w(TAG, "No push relay URL configured in local.properties");
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("token", token);
            json.put("title", title);
            json.put("body", body);
            json.put("type", type == null ? "" : type);
            json.put("docId", docId == null ? "" : docId);
            Request request = new Request.Builder()
                    .url(relayUrl)
                    .post(RequestBody.create(json.toString(), JSON))
                    .build();
            CLIENT.newCall(request).enqueue(new Callback() {
                @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    Log.w(TAG, "Push request failed", e);
                }
                @Override public void onResponse(@NonNull Call call, @NonNull Response response) {
                    response.close();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Could not build push request", e);
        }
    }
}
