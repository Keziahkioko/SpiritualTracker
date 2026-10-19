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

    /**
     * Bumps a badge on the partner's home screen. A screen that hasn't finished loading the
     * partner passes null; then the partner is looked up here, so the bump isn't lost.
     */
    public static void incrementUnread(String partnerId, String field) {
        if (partnerId == null || partnerId.isEmpty()) {
            String me = FirebaseAuth.getInstance().getUid();
            if (me == null) return;
            FirebaseFirestore.getInstance().collection("users").document(me).get().addOnSuccessListener(doc -> {
                String pid = doc.getString("partnerId");
                if (pid != null && !pid.isEmpty()) incrementUnread(pid, field);
            });
            return;
        }
        FirebaseFirestore.getInstance().collection("users").document(partnerId)
                .update(field, FieldValue.increment(1))
                .addOnFailureListener(e -> Log.w(TAG, "Unread bump failed", e));
    }

    /** What the relay said about one push (for the debug test tool). */
    public interface Outcome { void done(int httpCode, String body); }

    /**
     * {@code type} must be one the relay accepts; it decides which screen the tap opens.
     * {@code partnerId} may still be null if the screen hasn't loaded it yet: the relay finds the
     * partner itself (and refuses if there is none), so the push goes out either way.
     */
    public static void notifyPartner(String partnerId, String title, String body, String type, String docId) {
        send(title, body, type, docId, null);
    }

    /**
     * Posts the push to the relay, which finds the partner itself. The relay's answer
     * (e.g. "Partner has no device registered") is logged, so a missing notification can be traced.
     */
    static void send(String title, String body, String type, String docId, Outcome outcome) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        String relay = BuildConfig.RELAY_URL;
        if (user == null) {
            if (outcome != null) outcome.done(0, "not signed in");
            return;
        }
        if (relay == null || relay.isEmpty()) {
            Log.w(TAG, "relay.url is not set in local.properties; skipping push");
            if (outcome != null) outcome.done(0, "relay.url not set");
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
                        if (outcome != null) outcome.done(0, String.valueOf(e.getMessage()));
                    }
                    @Override public void onResponse(@NonNull Call call, @NonNull Response response) {
                        String reply = "";
                        try { if (response.body() != null) reply = response.body().string(); } catch (IOException ignored) { }
                        response.close();
                        if (!response.isSuccessful() || !reply.contains("\"sent\":true")) {
                            Log.w(TAG, "Push not delivered (" + type + "): HTTP " + response.code() + " " + reply);
                        } else {
                            Log.i(TAG, "Push sent (" + type + ")");
                        }
                        if (outcome != null) outcome.done(response.code(), reply);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Could not build push request", e);
                if (outcome != null) outcome.done(0, String.valueOf(e.getMessage()));
            }
        }).addOnFailureListener(e -> {
            Log.w(TAG, "Couldn't get sign-in token for push", e);
            if (outcome != null) outcome.done(0, "no sign-in token: " + e.getMessage());
        });
    }
}
