package com.keziah.spiritualtracker;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class PrayerPagerAdapter extends FragmentStateAdapter {

    public PrayerPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        // Return the correct Fragment based on the page number
        switch (position) {
            case 0:
                return new ConnectionFragment();
            case 1:
                return new SharedBoardFragment();
            case 2:
                return new MySpaceFragment();
            default:
                return new ConnectionFragment();
        }
    }

    @Override
    public int getItemCount() {
        return 3; // We have 3 tabs total
    }
}