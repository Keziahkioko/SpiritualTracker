package com.keziah.spiritualtracker;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.UserProfileChangeRequest;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.keziah.spiritualtracker.databinding.ActivityMainBinding;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private ActivityMainBinding binding;
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;

    private String currentUserId;
    private String partnerId = null;
    private String todayDate;
    private boolean namePromptShown = false;

    private ListenerRegistration userListener;
    private ListenerRegistration myDayListener;
    private ListenerRegistration partnerListener;
    private ListenerRegistration partnerDayListener;
    private ListenerRegistration legacyBibleListener;

    // Each tick combines the day log with the older per-user date fields, so a partner
    // still on an older build of the app keeps showing up correctly.
    private DocumentSnapshot myUserDoc, myDayDoc, partnerUserDoc, partnerDayDoc, legacyBibleDoc;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        mAuth = FirebaseAuth.getInstance();
        db    = FirebaseFirestore.getInstance();

        if (mAuth.getCurrentUser() == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        currentUserId = mAuth.getCurrentUser().getUid();
        todayDate = ActivityLog.today();

        saveFcmToken();
        requestNotificationPermission();
        DailyVerseReminderWorker.schedule(this);

        setupProgressListeners();
        handleNotificationIntent(getIntent());

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

        View.OnClickListener openHistory = v -> startActivity(new Intent(this, HistoryActivity.class));
        binding.cardWeek.setOnClickListener(openHistory);
        binding.cardProgress.setOnClickListener(openHistory);
        binding.tvViewHistory.setOnClickListener(openHistory);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (currentUserId == null) return;
        // The app can stay open past midnight; move the "today" listeners to the new day.
        String now = ActivityLog.today();
        if (!now.equals(todayDate)) {
            todayDate = now;
            attachMyDayListeners();
            setupPartnerListeners();
        }
        loadHeatmap();
    }

    // ─── Setup ───────────────────────────────────────────────────────────────

    private void saveFcmToken() {
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (!task.isSuccessful() || task.getResult() == null) return;
            Map<String, Object> data = new HashMap<>();
            data.put("fcmToken", task.getResult());
            db.collection("users").document(currentUserId).set(data, SetOptions.merge());
        });
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 300);
        }
    }

    /** Without a name, partners see "null posted a prayer request" — ask once and store it. */
    private void promptForNameIfMissing(DocumentSnapshot snap) {
        if (namePromptShown || UserProfile.hasName(snap) || isFinishing()) return;
        namePromptShown = true;

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setHint("Your first name");
        input.setText(UserProfile.suggestedName());
        input.setSelection(input.getText().length());
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input);

        new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setTitle("What should your partner call you?")
                .setMessage("Your name appears on prayer requests, journal entries and notifications.")
                .setView(wrap)
                .setCancelable(false)
                .setPositiveButton("Save", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        namePromptShown = false;
                        Toast.makeText(this, "Please enter a name", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Map<String, Object> data = new HashMap<>();
                    data.put("name", name);
                    db.collection("users").document(currentUserId).set(data, SetOptions.merge());
                    if (mAuth.getCurrentUser() != null) {
                        mAuth.getCurrentUser().updateProfile(
                                new UserProfileChangeRequest.Builder().setDisplayName(name).build());
                    }
                })
                .show();
    }

    // ─── Listeners ───────────────────────────────────────────────────────────

    private void setupProgressListeners() {
        userListener = db.collection("users").document(currentUserId)
                .addSnapshotListener((snap, e) -> {
                    if (e != null || snap == null) return;
                    if (!snap.exists()) {
                        promptForNameIfMissing(snap);
                        return;
                    }
                    myUserDoc = snap;
                    promptForNameIfMissing(snap);

                    Long score = snap.getLong("sharedScore");
                    binding.tvStreak.setText(String.valueOf(score != null ? score : 0));

                    updateBadge(binding.badgeBible,   snap.getLong("unreadBible"));
                    updateBadge(binding.badgePrayer,  snap.getLong("unreadPrayer"));
                    updateBadge(binding.badgeJournal, snap.getLong("unreadJournal"));

                    String newPartnerId = snap.getString("partnerId");
                    if (newPartnerId != null && newPartnerId.isEmpty()) newPartnerId = null;
                    if (newPartnerId != null && !newPartnerId.equals(partnerId)) {
                        partnerId = newPartnerId;
                        setupPartnerListeners();
                        loadHeatmap();
                    } else if (newPartnerId == null && partnerId != null) {
                        partnerId = null;
                        stopPartnerListeners();
                        loadHeatmap();
                    }
                    refreshProgressCard();
                });

        attachMyDayListeners();
    }

    private void attachMyDayListeners() {
        if (myDayListener != null) myDayListener.remove();
        if (legacyBibleListener != null) legacyBibleListener.remove();
        myDayDoc = null;
        legacyBibleDoc = null;

        myDayListener = ActivityLog.dayRef(currentUserId, todayDate)
                .addSnapshotListener((snap, e) -> {
                    if (e != null) { Log.w(TAG, "Day log listener failed", e); return; }
                    myDayDoc = snap;
                    refreshProgressCard();
                });

        // Older builds stored Bible reading in one shared doc per date.
        legacyBibleListener = db.collection("daily_readings").document(todayDate)
                .addSnapshotListener((snap, e) -> {
                    legacyBibleDoc = e == null ? snap : null;
                    refreshProgressCard();
                });
    }

    private void setupPartnerListeners() {
        stopPartnerListeners();
        if (partnerId == null) return;

        partnerListener = db.collection("users").document(partnerId)
                .addSnapshotListener((snap, e) -> {
                    partnerUserDoc = e == null ? snap : null;
                    refreshProgressCard();
                });

        partnerDayListener = ActivityLog.dayRef(partnerId, todayDate)
                .addSnapshotListener((snap, e) -> {
                    partnerDayDoc = e == null ? snap : null;
                    refreshProgressCard();
                });
    }

    private void stopPartnerListeners() {
        if (partnerListener    != null) partnerListener.remove();
        if (partnerDayListener != null) partnerDayListener.remove();
        partnerListener = null;
        partnerDayListener = null;
        partnerUserDoc = null;
        partnerDayDoc = null;
        refreshProgressCard();
    }

    // ─── Today's ticks ───────────────────────────────────────────────────────

    private void refreshProgressCard() {
        boolean hasPartner = partnerId != null;
        setDot(binding.progressBibleMe,         bibleDone(currentUserId, myDayDoc));
        setDot(binding.progressBiblePartner,    hasPartner && bibleDone(partnerId, partnerDayDoc));
        setDot(binding.progressPrayerMe,        dayFlag(myDayDoc, "prayed") || userDate(myUserDoc, "prayedTodayDate"));
        setDot(binding.progressPrayerPartner,   hasPartner && (dayFlag(partnerDayDoc, "prayed") || userDate(partnerUserDoc, "prayedTodayDate")));
        setDot(binding.progressJournalMe,       dayFlag(myDayDoc, "journal") || userDate(myUserDoc, "journaledTodayDate"));
        setDot(binding.progressJournalPartner,  hasPartner && (dayFlag(partnerDayDoc, "journal") || userDate(partnerUserDoc, "journaledTodayDate")));
        setDot(binding.progressMemorizeMe,      dayFlag(myDayDoc, "memorize") || userDate(myUserDoc, "memorizedTodayDate"));
        setDot(binding.progressMemorizePartner, hasPartner && (dayFlag(partnerDayDoc, "memorize") || userDate(partnerUserDoc, "memorizedTodayDate")));
    }

    private boolean bibleDone(String uid, DocumentSnapshot dayDoc) {
        if (dayDoc != null && dayDoc.contains("bible")) return Boolean.TRUE.equals(dayDoc.getBoolean("bible"));
        return legacyBibleDoc != null && Boolean.TRUE.equals(legacyBibleDoc.getBoolean(uid));
    }

    private static boolean dayFlag(DocumentSnapshot dayDoc, String field) {
        return dayDoc != null && Boolean.TRUE.equals(dayDoc.getBoolean(field));
    }

    private boolean userDate(DocumentSnapshot userDoc, String field) {
        return userDoc != null && todayDate.equals(userDoc.getString(field));
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

    // ─── This week (Bible reading together) ──────────────────────────────────

    private void loadHeatmap() {
        if (currentUserId == null) return;
        Calendar cal = Calendar.getInstance();
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        int daysFromMon = (dow == Calendar.SUNDAY) ? 6 : dow - Calendar.MONDAY;
        cal.add(Calendar.DAY_OF_YEAR, -daysFromMon);
        String[] keys = new String[7];
        for (int i = 0; i < 7; i++) {
            keys[i] = ActivityLog.dayKey(cal.getTime());
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }

        final String requestedPartner = partnerId;
        HistoryRepository.load(currentUserId, keys[0], keys[6], new HistoryRepository.Callback() {
            @Override public void onLoaded(TreeMap<String, HistoryRepository.DayRecord> mine) {
                if (requestedPartner == null) {
                    paintHeatmap(keys, mine, new TreeMap<>());
                    return;
                }
                HistoryRepository.load(requestedPartner, keys[0], keys[6], new HistoryRepository.Callback() {
                    @Override public void onLoaded(TreeMap<String, HistoryRepository.DayRecord> theirs) {
                        paintHeatmap(keys, mine, theirs);
                    }
                    @Override public void onError(Exception e) { paintHeatmap(keys, mine, new TreeMap<>()); }
                });
            }
            @Override public void onError(Exception e) { Log.w(TAG, "Heatmap load failed", e); }
        });
    }

    private void paintHeatmap(String[] keys,
                              TreeMap<String, HistoryRepository.DayRecord> mine,
                              TreeMap<String, HistoryRepository.DayRecord> theirs) {
        if (isFinishing() || isDestroyed()) return;
        int[] cellIds = {
                R.id.hmCellMon, R.id.hmCellTue, R.id.hmCellWed,
                R.id.hmCellThu, R.id.hmCellFri, R.id.hmCellSat, R.id.hmCellSun
        };
        for (int i = 0; i < 7; i++) {
            HistoryRepository.DayRecord m = mine.get(keys[i]);
            HistoryRepository.DayRecord p = theirs.get(keys[i]);
            boolean myDone = m != null && m.bible;
            boolean partnerDone = partnerId != null && p != null && p.bible;
            TextView cell = findViewById(cellIds[i]);
            if (cell == null) continue;
            if (myDone && partnerDone) {
                cell.setBackgroundResource(R.drawable.hm_full);
                cell.setTextColor(Color.WHITE);
            } else if (myDone || partnerDone) {
                cell.setBackgroundResource(R.drawable.hm_half);
                cell.setTextColor(Color.WHITE);
            } else {
                cell.setBackgroundResource(R.drawable.hm_none);
                cell.setTextColor(Color.parseColor("#B39DDB"));
            }
        }
    }

    // ─── Badges & notifications ──────────────────────────────────────────────

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
        if (mAuth.getCurrentUser() == null) return;
        Map<String, Object> data = new HashMap<>();
        data.put(field, 0);
        db.collection("users").document(mAuth.getCurrentUser().getUid()).set(data, SetOptions.merge());
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
        intent.removeExtra("type"); // don't re-route on rotation
        switch (type) {
            case "bible_nudge": case "bible_update": case "mark_read":
                resetUnreadCount("unreadBible");
                startActivity(new Intent(this, BibleActivity.class));
                break;
            case "journal_chat": case "journal_new": case "journal":
                resetUnreadCount("unreadJournal");
                Intent j = new Intent(this, JournalListActivity.class);
                j.putExtra("openFromNotification", true);
                j.putExtra("targetDocId", docId);
                startActivity(j);
                break;
            case "prayer_nudge": case "prayer_chat": case "prayer_update": case "nudge": case "chat":
                resetUnreadCount("unreadPrayer");
                startActivity(new Intent(this, PrayerActivity.class));
                break;
            case "verse_reminder":
                startActivity(new Intent(this, MemorizeActivity.class));
                break;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (userListener        != null) userListener.remove();
        if (myDayListener       != null) myDayListener.remove();
        if (legacyBibleListener != null) legacyBibleListener.remove();
        if (partnerListener     != null) partnerListener.remove();
        if (partnerDayListener  != null) partnerDayListener.remove();
    }
}
