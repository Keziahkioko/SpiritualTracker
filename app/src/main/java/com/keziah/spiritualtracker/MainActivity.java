package com.keziah.spiritualtracker;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.keziah.spiritualtracker.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private FirebaseAuth mAuth;
    private FirebaseFirestore db; // 1. Add Firestore variable

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance(); // 2. Initialize Firestore

        // --- NEW: LISTEN FOR SCORE UPDATES ---
        if (mAuth.getCurrentUser() != null) {
            String userId = mAuth.getCurrentUser().getUid();

            db.collection("users").document(userId)
                    .addSnapshotListener((documentSnapshot, e) -> {
                        if (e != null) {
                            Log.w("MainActivity", "Listen failed.", e);
                            return;
                        }

                        if (documentSnapshot != null && documentSnapshot.exists()) {
                            // Look for the "sharedScore" field
                            Long score = documentSnapshot.getLong("sharedScore");

                            // If score is null (first time), show 0
                            if (score == null) score = 0L;

                            // Update the Big Number on the Dashboard
                            // Make sure your TextView ID in XML is 'tvStreak'
                            binding.tvStreak.setText(String.valueOf(score));
                        }
                    });
        }
        // -------------------------------------

        // 1. Logout Logic
        binding.ivLogout.setOnClickListener(v -> {
            mAuth.signOut();
            Intent intent = new Intent(MainActivity.this, LoginActivity.class);
            startActivity(intent);
            finish();
        });

        // 2. Journal Card Click Logic
        binding.cardJournal.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, JournalListActivity.class);
            startActivity(intent);
        });

        // 3. Bible Card Click Logic
        binding.cardBible.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, BibleActivity.class);
            startActivity(intent);
        });

        // 4. (Optional) Placeholders for Prayer & Memorize
        binding.cardPrayer.setOnClickListener(v -> {
            // Intent intent = new Intent(MainActivity.this, PrayerActivity.class);
            // startActivity(intent);
        });

        binding.cardMemory.setOnClickListener(v -> {
            // Intent intent = new Intent(MainActivity.this, MemorizeActivity.class);
            // startActivity(intent);
        });
    }
}