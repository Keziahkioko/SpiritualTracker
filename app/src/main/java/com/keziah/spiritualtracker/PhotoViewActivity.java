package com.keziah.spiritualtracker;

import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;

import java.util.ArrayList;

/** Full-screen journal photos: swipe between them, pinch or double-tap to zoom. */
public class PhotoViewActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ArrayList<String> photos = getIntent().getStringArrayListExtra("photos");
        if (photos == null || photos.isEmpty()) { finish(); return; }

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.BLACK);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightStatusBars(false);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView()).setAppearanceLightNavigationBars(false);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        ViewPager2 pager = new ViewPager2(this);
        pager.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                ZoomImageView iv = new ZoomImageView(parent.getContext());
                iv.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                return new RecyclerView.ViewHolder(iv) {};
            }
            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                String src = photos.get(position);
                Glide.with(holder.itemView).load(src.startsWith("http") ? src : new java.io.File(src))
                        .into((ImageView) holder.itemView);
            }
            @Override public int getItemCount() { return photos.size(); }
        });
        root.addView(pager, new FrameLayout.LayoutParams(-1, -1));

        int dp = (int) getResources().getDisplayMetrics().density;
        ImageView close = new ImageView(this);
        close.setImageResource(R.drawable.ic_back);
        close.setColorFilter(Color.WHITE);
        close.setPadding(11 * dp, 11 * dp, 11 * dp, 11 * dp);
        close.setBackgroundResource(R.drawable.bg_photo_close);
        close.setContentDescription("Close");
        close.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(48 * dp, 48 * dp);
        close.setElevation(4 * dp);
        closeLp.setMargins(12 * dp, 12 * dp, 0, 0);
        root.addView(close, closeLp);

        TextView counter = new TextView(this);
        counter.setTextColor(Color.WHITE);
        counter.setTextSize(15);
        counter.setBackgroundResource(R.drawable.bg_photo_counter);
        counter.setPadding(12 * dp, 4 * dp, 12 * dp, 4 * dp);
        FrameLayout.LayoutParams countLp = new FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP | android.view.Gravity.END);
        countLp.setMargins(0, 24 * dp, 20 * dp, 0);
        root.addView(counter, countLp);
        if (photos.size() < 2) counter.setVisibility(android.view.View.GONE);

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                counter.setText((position + 1) + " / " + photos.size());
            }
        });
        pager.setCurrentItem(getIntent().getIntExtra("index", 0), false);
        counter.setText((pager.getCurrentItem() + 1) + " / " + photos.size());

        // Keep the close button and counter below the status bar.
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            closeLp.topMargin = top + 8 * dp;
            countLp.topMargin = top + 20 * dp;
            close.setLayoutParams(closeLp);
            counter.setLayoutParams(countLp);
            return insets;
        });

        setContentView(root);
    }
}
