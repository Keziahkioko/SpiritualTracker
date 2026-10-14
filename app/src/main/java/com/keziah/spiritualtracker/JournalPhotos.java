package com.keziah.spiritualtracker;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.cloudinary.android.MediaManager;
import com.cloudinary.android.callback.ErrorInfo;
import com.cloudinary.android.callback.UploadCallback;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Photos attached to journal entries: shrink, upload, show, open full screen. */
public final class JournalPhotos {

    public static final int MAX = 4;
    /** Longest side after shrinking: sharp enough to read handwriting, small enough for mobile data. */
    private static final int MAX_SIDE = 2048;
    private static final String TAG = "JournalPhotos";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private JournalPhotos() {}

    /** Reads the "imageUrls" field (and the old single "imageUrl") as a list. */
    public static ArrayList<String> from(@Nullable Object raw, @Nullable String legacy) {
        ArrayList<String> out = new ArrayList<>();
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) if (o instanceof String && !((String) o).isEmpty()) out.add((String) o);
        }
        if (out.isEmpty() && legacy != null && !legacy.isEmpty()) out.add(legacy);
        return out;
    }

    /** A smaller copy served by Cloudinary for thumbnails, so lists don't download full photos. */
    public static String thumb(String url, int px) {
        if (url == null || !url.contains("/upload/")) return url;
        return url.replace("/upload/", "/upload/c_fill,w_" + px + ",h_" + px + ",q_auto,f_auto/");
    }

    public static void load(ImageView view, String urlOrPath, int thumbPx) {
        Object src = urlOrPath.startsWith("http") ? (thumbPx > 0 ? thumb(urlOrPath, thumbPx) : urlOrPath)
                                                  : new File(urlOrPath);
        Glide.with(view).load(src).centerCrop().placeholder(R.drawable.bg_photo_placeholder).into(view);
    }

    public static void open(Context context, ArrayList<String> photos, int index) {
        Intent i = new Intent(context, PhotoViewActivity.class);
        i.putStringArrayListExtra("photos", photos);
        i.putExtra("index", index);
        context.startActivity(i);
    }

    public interface Shrunk { void done(@Nullable String path); }

    /** Copies a picked photo into the cache, upright and at most {@link #MAX_SIDE} px. */
    public static void shrink(Context context, Uri uri, Shrunk callback) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            String path = null;
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                try (InputStream in = app.getContentResolver().openInputStream(uri)) {
                    BitmapFactory.decodeStream(in, null, bounds);
                }
                int sample = 1;
                while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2;

                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = sample;
                Bitmap bmp;
                try (InputStream in = app.getContentResolver().openInputStream(uri)) {
                    bmp = BitmapFactory.decodeStream(in, null, opts);
                }
                if (bmp == null) throw new IllegalStateException("Not an image");

                float scale = Math.min(1f, MAX_SIDE / (float) Math.max(bmp.getWidth(), bmp.getHeight()));
                Matrix m = new Matrix();
                m.postScale(scale, scale);
                m.postRotate(rotationOf(app, uri));
                Bitmap upright = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);

                File out = new File(app.getCacheDir(), "journal_photo_" + System.nanoTime() + ".jpg");
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    upright.compress(Bitmap.CompressFormat.JPEG, 85, fos);
                }
                path = out.getAbsolutePath();
            } catch (Exception e) {
                Log.w(TAG, "Couldn't prepare photo", e);
            }
            String result = path;
            MAIN.post(() -> callback.done(result));
        });
    }

    private static int rotationOf(Context context, Uri uri) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return 0;
            switch (new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90:  return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    public interface Uploaded {
        void progress(int done, int total);
        void finished(@Nullable ArrayList<String> urls);
    }

    /**
     * Uploads any local files in {@code photos} (already-uploaded URLs are kept as they are),
     * one at a time, and hands back the full list of URLs in the same order, or null on failure.
     */
    public static void uploadAll(List<String> photos, Uploaded callback) {
        ArrayList<String> urls = new ArrayList<>(photos);
        uploadFrom(urls, 0, callback);
    }

    private static void uploadFrom(ArrayList<String> urls, int i, Uploaded callback) {
        while (i < urls.size() && urls.get(i).startsWith("http")) i++;
        if (i >= urls.size()) { MAIN.post(() -> callback.finished(urls)); return; }
        final int index = i;
        MAIN.post(() -> callback.progress(index, urls.size()));
        MediaManager.get().upload(urls.get(index))
                .option("folder", RelaySignatureProvider.FOLDER)
                .option("resource_type", "image")
                .callback(new UploadCallback() {
                    @Override public void onSuccess(String requestId, Map resultData) {
                        urls.set(index, (String) resultData.get("secure_url"));
                        uploadFrom(urls, index + 1, callback);
                    }
                    @Override public void onError(String requestId, ErrorInfo error) {
                        Log.w(TAG, "Photo upload failed: " + error.getDescription());
                        MAIN.post(() -> callback.finished(null));
                    }
                    @Override public void onStart(String id) {}
                    @Override public void onProgress(String id, long b, long t) {}
                    @Override public void onReschedule(String id, ErrorInfo e) {}
                }).dispatch();
    }
}
