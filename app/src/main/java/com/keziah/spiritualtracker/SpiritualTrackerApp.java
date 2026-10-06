package com.keziah.spiritualtracker; // Make sure this matches your package name!

import android.app.Application;
import com.cloudinary.android.MediaManager;
import java.util.HashMap;
import java.util.Map;

public class SpiritualTrackerApp extends Application {

    private static final String CLOUD_NAME = BuildConfig.CLOUDINARY_CLOUD_NAME;

    @Override
    public void onCreate() {
        super.onCreate();

        try {
            Map<String, String> config = new HashMap<>();
            config.put("cloud_name", CLOUD_NAME);
            MediaManager.init(this, config);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}