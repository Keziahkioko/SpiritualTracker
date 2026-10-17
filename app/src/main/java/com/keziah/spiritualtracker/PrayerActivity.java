package com.keziah.spiritualtracker;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class PrayerActivity extends AppCompatActivity {

    /** Which tab to open on: 0 Connection, 1 Shared, 2 My Space (from a prayer reminder). */
    static final String EXTRA_TAB = "tab";

    private ViewPager2 viewPager;
    private BottomNavigationView bottomNavigation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_prayer);

        viewPager = findViewById(R.id.viewPager);
        bottomNavigation = findViewById(R.id.bottomNavigation);

        // Force light-purple indicator pill regardless of dark mode
        try {
            bottomNavigation.setItemActiveIndicatorColor(
                    ColorStateList.valueOf(Color.parseColor("#EDE7F6")));
        } catch (Exception ignored) {
            // Older Material version — indicator color handled by theme
        }

        // 1. Set up the Adapter
        PrayerPagerAdapter adapter = new PrayerPagerAdapter(this);
        viewPager.setAdapter(adapter);

        // 2. Sync Tap to Swipe
        bottomNavigation.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.nav_connection) {
                viewPager.setCurrentItem(0, true);
                return true;
            } else if (itemId == R.id.nav_board) {
                viewPager.setCurrentItem(1, true);
                return true;
            } else if (itemId == R.id.nav_myspace) {
                viewPager.setCurrentItem(2, true);
                return true;
            }
            return false;
        });

        // 3. Sync Swipe to Tap
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                switch (position) {
                    case 0:
                        bottomNavigation.setSelectedItemId(R.id.nav_connection);
                        break;
                    case 1:
                        bottomNavigation.setSelectedItemId(R.id.nav_board);
                        break;
                    case 2:
                        bottomNavigation.setSelectedItemId(R.id.nav_myspace);
                        break;
                }
            }
        });

        openTab(getIntent());
        // Bells switched off on another phone (or before reinstalling) show correctly here.
        PrayerReminders.sync(this, null);

        // 4. Headers run up behind the status bar. Because the window no longer resizes for the
        //    keyboard by itself, lift the content above it and hide the bottom nav while typing.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(false);
        final View rootView = findViewById(android.R.id.content);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            boolean keyboardOpen = insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime());
            int imeBottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom;
            int navBottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom;
            bottomNavigation.setVisibility(keyboardOpen ? View.GONE : View.VISIBLE);
            bottomNavigation.setPadding(0, 0, 0, keyboardOpen ? 0 : navBottom);
            v.setPadding(0, 0, 0, keyboardOpen ? imeBottom : 0);
            return insets;
        });
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        openTab(intent);
    }

    private void openTab(android.content.Intent intent) {
        int tab = intent != null ? intent.getIntExtra(EXTRA_TAB, -1) : -1;
        if (tab >= 0 && tab <= 2) viewPager.setCurrentItem(tab, false);
    }
}