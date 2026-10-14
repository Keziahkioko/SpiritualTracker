package com.keziah.spiritualtracker;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Signed-in calls to our relay (server/api) that report back whether they worked. */
public final class Relay {

    private static final String TAG = "Relay";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Done { void done(boolean ok); }

    private Relay() {}

    /** POSTs {@code body} to {@code path} (e.g. "/api/unlink"); {@code done} runs on the main thread. */
    public static void post(String path, JSONObject body, Done done) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        String relay = BuildConfig.RELAY_URL;
        if (user == null || relay == null || relay.isEmpty()) { done.done(false); return; }
        user.getIdToken(false).addOnSuccessListener(result -> {
            Request request = new Request.Builder()
                    .url(relay + path)
                    .header("Authorization", "Bearer " + result.getToken())
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();
            PartnerNotifier.CLIENT.newCall(request).enqueue(new Callback() {
                @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    Log.w(TAG, path + " failed", e);
                    MAIN.post(() -> done.done(false));
                }
                @Override public void onResponse(@NonNull Call call, @NonNull Response response) {
                    boolean ok = response.isSuccessful();
                    if (!ok) Log.w(TAG, path + " refused: HTTP " + response.code());
                    response.close();
                    MAIN.post(() -> done.done(ok));
                }
            });
        }).addOnFailureListener(e -> {
            Log.w(TAG, "Couldn't get sign-in token for " + path, e);
            done.done(false);
        });
    }

    /** Removes Cloudinary files referenced by a journal entry; null urls means all of them. */
    public static void deleteJournalMedia(String docId, java.util.List<String> urls, Done done) {
        try {
            JSONObject body = new JSONObject().put("docId", docId);
            if (urls != null) body.put("urls", new org.json.JSONArray(urls));
            post("/api/delete-media", body, done);
        } catch (Exception e) {
            done.done(false);
        }
    }
}
