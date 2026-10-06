package com.keziah.spiritualtracker;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class PrayerActivity extends AppCompatActivity {

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

        // 4. Hide Bottom Navigation when Keyboard Opens
        final View rootView = findViewById(android.R.id.content);
        rootView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            int heightDiff = rootView.getRootView().getHeight() - rootView.getHeight();
            if (heightDiff > 200) {
                bottomNavigation.setVisibility(View.GONE);
            } else {
                bottomNavigation.setVisibility(View.VISIBLE);
            }
        });
    }
}