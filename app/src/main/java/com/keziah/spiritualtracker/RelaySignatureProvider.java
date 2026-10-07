package com.keziah.spiritualtracker;

import android.util.Log;

import com.cloudinary.android.signed.Signature;
import com.cloudinary.android.signed.SignatureProvider;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Gets each Cloudinary upload signed by our relay (server/api/cloudinary-signature.js), so the
 * Cloudinary account doesn't need an open "unsigned" preset that anyone could upload through.
 * Cloudinary calls this on its background upload thread, so blocking here is fine.
 */
public class RelaySignatureProvider implements SignatureProvider {

    /** Every app upload goes in this folder; the relay refuses to sign anything else. */
    public static final String FOLDER = "spiritual-tracker";

    private static final String TAG = "RelaySignature";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    @Override
    public Signature provideSignature(Map options) {
        try {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) throw new IllegalStateException("Not signed in");
            String idToken = Tasks.await(user.getIdToken(false), 20, TimeUnit.SECONDS).getToken();

            JSONObject body = new JSONObject();
            body.put("options", new JSONObject(options));
            Request request = new Request.Builder()
                    .url(BuildConfig.RELAY_URL + "/api/cloudinary-signature")
                    .header("Authorization", "Bearer " + idToken)
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();
            try (Response response = PartnerNotifier.CLIENT.newCall(request).execute()) {
                String text = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) throw new IllegalStateException("HTTP " + response.code() + " " + text);
                JSONObject json = new JSONObject(text);
                return new Signature(json.getString("signature"), json.getString("apiKey"), json.getLong("timestamp"));
            }
        } catch (Exception e) {
            Log.e(TAG, "Upload signing failed", e);
            // Cloudinary reports this upload as failed, and the screen shows its upload-error message.
            throw new RuntimeException("Upload signing failed", e);
        }
    }

    @Override
    public String getName() {
        return "RelaySignatureProvider";
    }
}
