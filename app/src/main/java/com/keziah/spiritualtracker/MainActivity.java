package com.keziah.spiritualtracker;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.keziah.spiritualtracker.databinding.ActivityMainBinding;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;

    private String currentUserId;
    private String partnerId = null;
    private String todayDate;

    private Timestamp todayStart;
    private Timestamp todayEnd;

    private ListenerRegistration userListener;
    private ListenerRegistration partnerListener;
    private ListenerRegistration bibleListener;
    private ListenerRegistration myJournalListener;
    private ListenerRegistration partnerJournalListener;

    private boolean bibleMe      = false, biblePartner      = false;
    private boolean prayerMe     = false, prayerPartner     = false;
    private boolean journalMe    = false, journalPartner    = false;
    private boolean memorizeMe   = false, memorizePartner   = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        mAuth = FirebaseAuth.getInstance();
        db    = FirebaseFirestore.getInstance();

        updateTodayScope();

        if (mAuth.getCurrentUser() != null) {
            currentUserId = mAuth.getCurrentUser().getUid();
            setupProgressListeners();
            handleNotificationIntent(getIntent());
        } else {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        }

        binding.ivLogout.setOnClickListener(v -> {
            mAuth.signOut();
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        });

        binding.cardJournal.setOnClickListener(v -> {
            resetUnreadCount("unreadJournal");
            startActivity(new Intent(this, JournalListActivity.class));
        });
        binding.cardBible.setOnClickListener(v -> {
            resetUnreadCount("unreadBible");
            startActivity(new Intent(this, BibleActivity.class));
        });
        binding.cardPrayer.setOnClickListener(v -> {
            resetUnreadCount("unreadPrayer");
            startActivity(new Intent(this, PrayerActivity.class));
        });
        binding.cardMemory.setOnClickListener(v ->
                startActivity(new Intent(this, MemorizeActivity.class)));
    }

    private void updateTodayScope() {
        todayDate = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        todayStart = new Timestamp(cal.getTime());
        cal.set(Calendar.HOUR_OF_DAY, 23);
        cal.set(Calendar.MINUTE, 59);
        cal.set(Calendar.SECOND, 59);
        todayEnd = new Timestamp(cal.getTime());
    }

    private void setupProgressListeners() {

        // 1. My user doc — streak, badges, prayer, memorize
        userListener = db.collection("users").document(currentUserId)
                .addSnapshotListener((snap, e) -> {
                    if (e != null || snap == null || !snap.exists()) return;

                    Long score = snap.getLong("sharedScore");
                    binding.tvStreak.setText(String.valueOf(score != null ? score : 0));

                    updateBadge(binding.badgeBible,   snap.getLong("unreadBible"));
                    updateBadge(binding.badgePrayer,  snap.getLong("unreadPrayer"));
                    updateBadge(binding.badgeJournal, snap.getLong("unreadJournal"));

                    prayerMe   = todayDate.equals(snap.getString("prayedTodayDate"));
                    memorizeMe = todayDate.equals(snap.getString("memorizedTodayDate"));

                    String newPartnerId = snap.getString("partnerId");
                    if (newPartnerId != null && !newPartnerId.equals(partnerId)) {
                        partnerId = newPartnerId;
                        setupPartnerListeners();
                    } else if (newPartnerId == null) {
                        partnerId = null;
                        stopPartnerListeners();
                    }
                    refreshProgressCard();
                    loadHeatmap();
                });

        // 2. My journal — FIX: Marks as done IMMEDIATELY when ANY non-draft entry exists today
        //    Query by userId + date range to check if I have published anything today
        myJournalListener = db.collection("journal_entries")
                .whereEqualTo("userId", currentUserId)
                .whereGreaterThanOrEqualTo("date", todayStart)
                .whereLessThanOrEqualTo("date", todayEnd)
                .addSnapshotListener((snap, e) -> {
                    if (e != null) {
                        Log.e("MainActivity", "Journal listener error: " + e.getMessage());
                        return;
                    }
                    // Mark as done if ANY entry is published (status != "draft" or status is null/empty)
                    journalMe = false;
                    if (snap != null && !snap.isEmpty()) {
                        for (com.google.firebase.firestore.DocumentSnapshot doc : snap.getDocuments()) {
                            String status = doc.getString("status");
                            // Count as done if status is not "draft" (i.e., published, null, or other)
                            if (!"draft".equals(status)) {
                                journalMe = true;
                                break;
                            }
                        }
                    }
                    refreshProgressCard();
                });

        // 3. Bible reading (shared daily doc)
        bibleListener = db.collection("daily_readings").document(todayDate)
                .addSnapshotListener((snap, e) -> {
                    if (snap != null && snap.exists()) {
                        bibleMe = Boolean.TRUE.equals(snap.getBoolean(currentUserId));
                        if (partnerId != null)
                            biblePartner = Boolean.TRUE.equals(snap.getBoolean(partnerId));
                    } else {
                        bibleMe = false;
                        biblePartner = false;
                    }
                    refreshProgressCard();
                });
    }

    private void setupPartnerListeners() {
        if (partnerListener        != null) partnerListener.remove();
        if (partnerJournalListener != null) partnerJournalListener.remove();
        if (partnerId == null || partnerId.isEmpty()) return;

        // Partner user doc — prayer, memorize
        partnerListener = db.collection("users").document(partnerId)
                .addSnapshotListener((snap, e) -> {
                    if (snap != null && snap.exists()) {
                        prayerPartner   = todayDate.equals(snap.getString("prayedTodayDate"));
                        memorizePartner = todayDate.equals(snap.getString("memorizedTodayDate"));
                        refreshProgressCard();
                    }
                });

        // Partner journal — same fix: Marks as done IMMEDIATELY when partner posts
        //    (doesn't wait for me to open it — just needs to exist and be published)
        partnerJournalListener = db.collection("journal_entries")
                .whereEqualTo("userId", partnerId)
                .whereGreaterThanOrEqualTo("date", todayStart)
                .whereLessThanOrEqualTo("date", todayEnd)
                .addSnapshotListener((snap, e) -> {
                    if (e != null) {
                        Log.e("MainActivity", "Partner journal error: " + e.getMessage());
                        return;
                    }
                    // Mark as done if partner has ANY published entry today
                    journalPartner = false;
                    if (snap != null && !snap.isEmpty()) {
                        for (com.google.firebase.firestore.DocumentSnapshot doc : snap.getDocuments()) {
                            String status = doc.getString("status");
                            // Count as done if status is not "draft" (published, null, or other)
                            if (!"draft".equals(status)) {
                                journalPartner = true;
                                break;
                            }
                        }
                    }
                    refreshProgressCard();
                });
    }

    private void stopPartnerListeners() {
        if (partnerListener        != null) partnerListener.remove();
        if (partnerJournalListener != null) partnerJournalListener.remove();
        prayerPartner   = false;
        memorizePartner = false;
        journalPartner  = false;
        biblePartner    = false;
    }

    private void refreshProgressCard() {
        setDot(binding.progressBibleMe,         bibleMe);
        setDot(binding.progressBiblePartner,    partnerId != null && biblePartner);
        setDot(binding.progressPrayerMe,        prayerMe);
        setDot(binding.progressPrayerPartner,   partnerId != null && prayerPartner);
        setDot(binding.progressJournalMe,       journalMe);
        setDot(binding.progressJournalPartner,  partnerId != null && journalPartner);
        setDot(binding.progressMemorizeMe,      memorizeMe);
        setDot(binding.progressMemorizePartner, partnerId != null && memorizePartner);
    }

    // ── Dot: circle background + double-tick icon when done ──────────────────
    private void setDot(ImageView view, boolean done) {
        if (done) {
            view.setBackgroundResource(R.drawable.ic_progress_done); // solid purple circle
            view.setImageResource(R.drawable.ic_check_double);       // white double tick
            view.setColorFilter(Color.WHITE);
        } else {
            view.setBackgroundResource(R.drawable.ic_progress_todo); // faint circle
            view.setImageResource(0);                                 // no icon
            view.clearColorFilter();
        }
    }

    private void loadHeatmap() {
        if (currentUserId == null) return;
        Calendar cal = Calendar.getInstance();
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        int daysFromMon = (dow == Calendar.SUNDAY) ? 6 : dow - Calendar.MONDAY;
        cal.add(Calendar.DAY_OF_YEAR, -daysFromMon);

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        int[] cellIds = {
                R.id.hmCellMon, R.id.hmCellTue, R.id.hmCellWed,
                R.id.hmCellThu, R.id.hmCellFri, R.id.hmCellSat, R.id.hmCellSun
        };

        for (int i = 0; i < 7; i++) {
            final int idx  = i;
            final String date = sdf.format(cal.getTime());
            cal.add(Calendar.DAY_OF_YEAR, 1);

            db.collection("daily_readings").document(date).get()
                    .addOnSuccessListener(snap -> {
                        int level = 0;
                        if (snap.exists()) {
                            boolean myDone      = Boolean.TRUE.equals(snap.getBoolean(currentUserId));
                            boolean partnerDone = partnerId != null &&
                                    Boolean.TRUE.equals(snap.getBoolean(partnerId));
                            if (myDone && partnerDone)      level = 2;
                            else if (myDone || partnerDone) level = 1;
                        }
                        TextView cell = findViewById(cellIds[idx]);
                        if (cell != null) {
                            if (level == 2) {
                                cell.setBackgroundResource(R.drawable.hm_full);
                                cell.setTextColor(Color.WHITE);
                            } else if (level == 1) {
                                cell.setBackgroundResource(R.drawable.hm_half);
                                cell.setTextColor(Color.WHITE);
                            } else {
                                cell.setBackgroundResource(R.drawable.hm_none);
                                cell.setTextColor(Color.parseColor("#B39DDB"));
                            }
                        }
                    });
        }
    }

    private void updateBadge(View badgeView, Long count) {
        if (badgeView instanceof TextView) {
            TextView tv = (TextView) badgeView;
            if (count != null && count > 0) {
                tv.setText(String.valueOf(count));
                tv.setVisibility(View.VISIBLE);
            } else {
                tv.setVisibility(View.GONE);
            }
        }
    }

    private void resetUnreadCount(String field) {
        if (mAuth.getCurrentUser() != null)
            db.collection("users").document(mAuth.getCurrentUser().getUid()).update(field, 0);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNotificationIntent(intent);
    }

    private void handleNotificationIntent(Intent intent) {
        if (intent == null || intent.getExtras() == null) return;
        String type  = intent.getStringExtra("type");
        String docId = intent.getStringExtra("docId");
        if (type == null) return;
        switch (type) {
            case "bible_nudge": case "bible_update": case "mark_read":
                resetUnreadCount("unreadBible");
                startActivity(new Intent(this, BibleActivity.class));
                break;
            case "journal_chat": case "journal_new": case "journal":
                resetUnreadCount("unreadJournal");
                Intent j = new Intent(this, JournalListActivity.class);
                j.putExtra("docId", docId);
                startActivity(j);
                break;
            case "prayer_nudge": case "prayer_chat": case "prayer_update":
                resetUnreadCount("unreadPrayer");
                startActivity(new Intent(this, PrayerActivity.class));
                break;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (userListener             != null) userListener.remove();
        if (partnerListener          != null) partnerListener.remove();
        if (bibleListener            != null) bibleListener.remove();
        if (myJournalListener        != null) myJournalListener.remove();
        if (partnerJournalListener   != null) partnerJournalListener.remove();
    }
}