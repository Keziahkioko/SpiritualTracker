package com.keziah.spiritualtracker;

import android.content.Context;
import android.content.SharedPreferences;

import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

/**
 * The couple's home-screen photo, shared by both partners:
 * partnerships/{pair}/settings/home_photo = { url (none = the photo built into the app),
 * cx, cy, zoom (its framing, see {@link PhotoFrame}), by, at }.
 * The last one seen is kept on the phone so the home screen shows it straight away.
 */
final class HomePhoto {

    static final String URL = "url", CX = "cx", CY = "cy", ZOOM = "zoom";
    private static final String PREFS = "home_photo";

    private HomePhoto() {}

    static String pairId(String a, String b) {
        return a.compareTo(b) < 0 ? a + "_" + b : b + "_" + a;
    }

    static DocumentReference ref(String me, String partner) {
        return FirebaseFirestore.getInstance().collection("partnerships").document(pairId(me, partner))
                .collection("settings").document("home_photo");
    }

    /** Saves a new photo or framing for both partners ({@code url} null = the built-in photo). */
    static com.google.android.gms.tasks.Task<Void> save(String me, String partner, @Nullable String url,
                                                       float cx, float cy, float zoom) {
        Map<String, Object> data = new HashMap<>();
        data.put(URL, url);
        data.put(CX, (double) cx);
        data.put(CY, (double) cy);
        data.put(ZOOM, (double) zoom);
        data.put("by", me);
        data.put("at", FieldValue.serverTimestamp());
        return ref(me, partner).set(data);
    }

    /** Shows the photo from the shared doc (or the built-in one) and remembers it on this phone. */
    static void show(FramedImageView view, @Nullable DocumentSnapshot doc) {
        String url = doc != null && doc.exists() ? doc.getString(URL) : null;
        float cx = number(doc, CX, 0.5f), cy = number(doc, CY, 0.5f), zoom = number(doc, ZOOM, 1f);
        prefs(view.getContext()).edit().putString(URL, url)
                .putFloat(CX, cx).putFloat(CY, cy).putFloat(ZOOM, zoom).apply();
        show(view, url, cx, cy, zoom);
    }

    /** The last photo seen, before the shared doc has answered. */
    static void showRemembered(FramedImageView view) {
        SharedPreferences p = prefs(view.getContext());
        show(view, p.getString(URL, null), p.getFloat(CX, 0.5f), p.getFloat(CY, 0.5f), p.getFloat(ZOOM, 1f));
    }

    /** Back to the built-in photo on this phone (e.g. after unlinking). */
    static void forget(FramedImageView view) {
        prefs(view.getContext()).edit().clear().apply();
        show(view, null, 0.5f, 0.5f, 1f);
    }

    static void show(FramedImageView view, @Nullable String urlOrPath, float cx, float cy, float zoom) {
        view.setFrame(cx, cy, zoom);
        com.bumptech.glide.RequestManager glide = Glide.with(view.getContext().getApplicationContext());
        if (urlOrPath == null || urlOrPath.isEmpty()) {
            view.setImageResource(R.drawable.header_bg);
            if (view.loading != null) glide.clear(view.loading);
            view.loading = null;
            return;
        }
        // Full size so zooming in stays sharp (photos are saved at most 2048 px). The current
        // picture stays up until the new one is ready, so nothing flashes; the old load is only
        // released once it's no longer on screen.
        CustomTarget<Drawable> previous = view.loading;
        view.loading = glide.load(urlOrPath)
                .override(Target.SIZE_ORIGINAL)
                .dontAnimate()
                .into(new CustomTarget<Drawable>() {
                    @Override public void onResourceReady(@NonNull Drawable d, @Nullable Transition<? super Drawable> t) {
                        view.setImageDrawable(d);
                        if (previous != null && previous != this) glide.clear(previous);
                    }
                    @Override public void onLoadFailed(@Nullable Drawable error) {
                        if (view.getDrawable() == null) view.setImageResource(R.drawable.header_bg);
                    }
                    @Override public void onLoadCleared(@Nullable Drawable placeholder) { }
                });
    }

    private static float number(@Nullable DocumentSnapshot doc, String field, float fallback) {
        Double v = doc != null ? doc.getDouble(field) : null;
        return v != null ? v.floatValue() : fallback;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
