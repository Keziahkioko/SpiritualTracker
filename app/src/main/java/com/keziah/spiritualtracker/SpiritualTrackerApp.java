package com.keziah.spiritualtracker;

import android.app.Application;
import android.util.Log;

import com.cloudinary.android.MediaManager;

import java.util.HashMap;
import java.util.Map;

public class SpiritualTrackerApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        try {
            Map<String, String> config = new HashMap<>();
            config.put("cloud_name", BuildConfig.CLOUDINARY_CLOUD_NAME);
            // Uploads are signed by our relay instead of using an open unsigned preset.
            MediaManager.init(this, new RelaySignatureProvider(), config);
        } catch (Exception e) {
            Log.e("SpiritualTrackerApp", "Cloudinary init failed", e);
        }
    }
}
