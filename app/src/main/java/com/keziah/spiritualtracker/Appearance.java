package com.keziah.spiritualtracker;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * Light (Pearl), Dark (Obsidian) or Match phone, for the whole app. Saved on the phone; Dark is
 * the default. Screens take their colours from the palette (values / values-night colors.xml).
 */
final class Appearance {

    static final int LIGHT = 0, DARK = 1, SYSTEM = 2;
    static final String[] NAMES = {"Light  ·  Pearl", "Dark  ·  Obsidian", "Match phone"};
    private static final String PREFS = "appearance";

    private Appearance() {}

    static int get(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.contains("mode")) return p.getInt("mode", DARK);
        // Before this setting covered the whole app it was the dashboard's own choice.
        SharedPreferences old = context.getSharedPreferences("dashboard_look", Context.MODE_PRIVATE);
        return old.contains("dark") && !old.getBoolean("dark", true) ? LIGHT : DARK;
    }

    /** Saves the choice and applies it: every open screen redraws in the new look. */
    static void set(Context context, int mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("mode", mode).apply();
        apply(context);
    }

    static void apply(Context context) {
        int mode = get(context);
        AppCompatDelegate.setDefaultNightMode(mode == LIGHT ? AppCompatDelegate.MODE_NIGHT_NO
                : mode == DARK ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    /** Whether this screen is showing the dark look right now. */
    static boolean isDark(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }
}
