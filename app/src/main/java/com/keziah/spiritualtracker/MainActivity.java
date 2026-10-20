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
import android.view.ViewGroup;
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
    private boolean checkedInvites = false;
    private ListenerRegistration inviteListener;

    private ListenerRegistration userListener;
    private ListenerRegistration myDayListener;
    private ListenerRegistration partnerListener;
    private ListenerRegistration partnerDayListener;
    private ListenerRegistration homePhotoListener;
    private ListenerRegistration legacyBibleListener;

    // Each tick combines the day log with the older per-user date fields, so a partner
    // still on an older build of the app keeps showing up correctly.
    private DocumentSnapshot myUserDoc, myDayDoc, partnerUserDoc, partnerDayDoc, legacyBibleDoc;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        PartnerName.apply(binding.getRoot());
        drawPhotoBehindStatusBar();

        mAuth = FirebaseAuth.getInstance();
        db    = FirebaseFirestore.getInstance();

        if (mAuth.getCurrentUser() == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        currentUserId = mAuth.getCurrentUser().getUid();
        todayDate = ActivityLog.today();

        requestNotificationPermission();
        DailyVerseReminderWorker.schedule(this);
        ReminderWorker.schedule(this);
        PrayerReminders.schedule(this);

        setupProgressListeners();
        handleNotificationIntent(getIntent());

        binding.ivLogout.setContentDescription("Account");
        binding.ivLogout.setOnClickListener(v -> showAccountMenu());

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
        applyLook();
        // Tiles and cards sink and spring back as they're pressed, with a light tap.
        for (View tile : new View[]{binding.cardBible, binding.cardPrayer, binding.cardJournal, binding.cardMemory,
                binding.cardProgress, binding.cardWeek}) {
            SpringPress.on(tile, 0.95f);
        }
        binding.cardMemory.setOnClickListener(v ->
                startActivity(new Intent(this, MemorizeActivity.class)));

        View.OnClickListener openHistory = v -> startActivity(new Intent(this, HistoryActivity.class));
        binding.cardWeek.setOnClickListener(openHistory);
        binding.cardProgress.setOnClickListener(openHistory);
        binding.tvViewHistory.setOnClickListener(openHistory);
        binding.tvLinkPartner.setOnClickListener(v -> showLinkPartnerDialog());

        // The couple's photo (shared, set from the camera button); the last one seen shows at once.
        HomePhoto.showRemembered(binding.ivHeader);
        binding.ivChangePhoto.setOnClickListener(v -> {
            if (partnerId == null) return;
            startActivity(new Intent(this, HomePhotoActivity.class)
                    .putExtra(HomePhotoActivity.EXTRA_PARTNER, partnerId)
                    .putExtra(HomePhotoActivity.EXTRA_SCORE, binding.tvStreak.getText().toString()));
        });
    }

    private void showAccountMenu() {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, binding.ivLogout);
        if (partnerId != null) {
            menu.getMenu().add(0, 3, 0, "Rename " + PartnerName.get(this));
            // While a request is open, the banner on the home screen handles it instead.
            if (unlinkRequestBy == null) menu.getMenu().add(0, 4, 1, "Unlink " + PartnerName.get(this));
        }
        menu.getMenu().add(0, 5, 2, "Appearance");
        menu.getMenu().add(0, 2, 3, "Sign out");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 3) showNamePartnerDialog(false);
            else if (item.getItemId() == 4) confirmUnlink();
            else if (item.getItemId() == 5) chooseLook();
            else signOut();
            return true;
        });
        menu.show();
    }

    private boolean namePartnerAsked = false;

    /**
     * Like saving a contact: you choose what your partner is called on your phone.
     * Asked once when a partner is first linked; "Rename" in the menu opens it again.
     */
    private void showNamePartnerDialog(boolean firstTime) {
        if (isFinishing()) return;
        String ownName = PartnerName.ownName(this);
        boolean ownKnown = !"Partner".equals(ownName);
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setTextColor(Color.parseColor("#1A1A1A"));
        input.setHintTextColor(Color.parseColor("#6E6E73"));
        input.setHint("Their name");
        input.setText(firstTime ? (ownKnown ? ownName : "") : PartnerName.get(this));
        input.setSelection(input.getText().length());
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input);

        new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setTitle(firstTime ? "What do you want to call your partner?" : "Rename your partner")
                .setView(wrap)
                .setCancelable(!firstTime)
                .setPositiveButton("Save", (d, w) -> {
                    String nick = input.getText().toString().trim();
                    PartnerName.setNickname(this, nick.isEmpty() ? (ownKnown ? ownName : "Partner") : nick);
                    PartnerName.backup(partnerId);
                    recreate(); // refresh every label that shows the name
                })
                .setNegativeButton(firstTime ? null : "Cancel", null)
                .show();
    }

    // ─── Unlinking (needs both of us) ────────────────────────────────────────

    /** After asking, the asker may unlink alone if there's still no answer after this long. */
    private static final long UNLINK_WAIT_MS = 7L * 24 * 60 * 60 * 1000;
    private static final int RED = Color.parseColor("#C62828");

    /** Step one: ask. Nothing changes until the other person agrees. */
    private void confirmUnlink() {
        if (unlinkRequestBy != null) return; // already asked (by either of us); the banner says so
        String name = PartnerName.get(this);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setTitle("Ask " + name + " to unlink?")
                .setMessage(name + " will be asked to agree. Until they do, you stay linked and can cancel the request.\n\n"
                        + "If you both agree, you'll stop seeing each other's progress, journal and prayers. "
                        + "Your own entries stay with you.")
                .setPositiveButton("Send request", null)
                .setNegativeButton("Cancel", null)
                .show();
        android.widget.Button send = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
        send.setTextColor(RED);
        send.setOnClickListener(v -> {
            send.setEnabled(false);
            unlinkAction("request", ok -> {
                dialog.dismiss();
                if (ok) Toast.makeText(this, name + " has been asked.", Toast.LENGTH_LONG).show();
            });
        });
    }

    private String unlinkRequestBy;
    private com.google.firebase.Timestamp unlinkRequestAt;

    /** Shows or hides the calm banner on the home screen, depending on who asked. */
    private void showUnlinkBanner(DocumentSnapshot snap) {
        Object raw = snap.get("unlinkRequest");
        unlinkRequestBy = null;
        unlinkRequestAt = null;
        if (raw instanceof Map && partnerId != null) {
            Map<?, ?> req = (Map<?, ?>) raw;
            Object by = req.get("by"), at = req.get("at");
            if (by instanceof String) unlinkRequestBy = (String) by;
            if (at instanceof com.google.firebase.Timestamp) unlinkRequestAt = (com.google.firebase.Timestamp) at;
        }
        if (unlinkRequestBy == null) {
            binding.cardUnlinkRequest.setVisibility(View.GONE);
            return;
        }
        String name = PartnerName.get(this);
        binding.cardUnlinkRequest.setVisibility(View.VISIBLE);
        binding.btnUnlinkSecondary.setVisibility(View.GONE);
        if (unlinkRequestBy.equals(currentUserId)) {
            binding.tvUnlinkTitle.setText("You asked to unlink");
            binding.btnUnlinkPrimary.setText("Cancel request");
            binding.btnUnlinkPrimary.setOnClickListener(v -> unlinkAction("cancel", ok -> {}));
            boolean waitedLongEnough = unlinkRequestAt != null
                    && System.currentTimeMillis() - unlinkRequestAt.toDate().getTime() >= UNLINK_WAIT_MS;
            if (waitedLongEnough) {
                binding.tvUnlinkBody.setText(name + " hasn't answered in a week. You can keep waiting or unlink on your own.");
                binding.btnUnlinkSecondary.setVisibility(View.VISIBLE);
                binding.btnUnlinkSecondary.setText("Unlink anyway");
                binding.btnUnlinkSecondary.setOnClickListener(v -> confirmTyped(
                        "Unlink without " + name + "'s answer?",
                        "You'll stop seeing each other's progress, journal and prayers. Your own entries stay with you.",
                        "force"));
            } else {
                binding.tvUnlinkBody.setText("Waiting for " + name + " to agree. You're still linked until they do.");
            }
        } else {
            binding.tvUnlinkTitle.setText(name + " asked to unlink");
            binding.tvUnlinkBody.setText("Nothing changes unless you agree. Take your time.");
            binding.btnUnlinkPrimary.setText("Read and answer");
            binding.btnUnlinkPrimary.setOnClickListener(v -> answerUnlinkRequest());
        }
    }

    /** The asked person's choice: staying linked is the easy, highlighted answer. */
    private void answerUnlinkRequest() {
        String name = PartnerName.get(this);
        new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setTitle(name + " asked to unlink")
                .setMessage("If you agree, you'll both stop seeing each other's progress, journal and prayers. "
                        + "Your own entries stay with you, and you can link again later with a new code.\n\n"
                        + "If you'd rather stay linked, tap \"Stay linked\" and " + name + " will be told.")
                .setPositiveButton("Stay linked", (d, w) -> unlinkAction("decline", ok -> {
                    if (ok) Toast.makeText(this, "You're still linked. " + name + " has been told.", Toast.LENGTH_LONG).show();
                }))
                .setNeutralButton("Decide later", null)
                .setNegativeButton("I agree to unlink", (d, w) -> confirmTyped(
                        "Unlink from " + name + "?", "This ends the partnership for both of you.", "accept"))
                .show();
    }

    /** Last step before unlinking: type UNLINK, so a stray tap can never do it. */
    private void confirmTyped(String title, String message, String action) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.setTextColor(Color.parseColor("#1A1A1A"));
        input.setHintTextColor(Color.parseColor("#6E6E73"));
        input.setHint("Type UNLINK");
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input);

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setTitle(title)
                .setMessage(message + "\n\nTo confirm, type UNLINK below.")
                .setView(wrap)
                .setPositiveButton("Unlink", null)
                .setNegativeButton("Cancel", null)
                .show();
        android.widget.Button unlink = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
        unlink.setTextColor(RED);
        unlink.setEnabled(false);
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                unlink.setEnabled("UNLINK".equalsIgnoreCase(s.toString().trim()));
            }
        });
        unlink.setOnClickListener(v -> {
            unlink.setEnabled(false);
            unlink.setText("Unlinking…");
            // On success the profile listener sees partnerId disappear and updates the screen.
            unlinkAction(action, ok -> dialog.dismiss());
        });
    }

    private void unlinkAction(String action, Relay.Done done) {
        org.json.JSONObject body = new org.json.JSONObject();
        try { body.put("action", action); } catch (org.json.JSONException ignored) {}
        Relay.post("/api/unlink", body, ok -> {
            if (isFinishing()) return;
            if (!ok) Toast.makeText(this, "Couldn't reach the server. Check your connection and try again.", Toast.LENGTH_LONG).show();
            done.done(ok);
        });
    }

    /** Partnership ended (by either of us): forget their name on this phone and say so once. */
    private void onPartnerUnlinked() {
        String name = PartnerName.get(this);
        PartnerName.save(this, null);
        PartnerName.setNickname(this, null);
        HomePhoto.forget(binding.ivHeader);
        namePartnerAsked = false;
        if (isFinishing()) return;
        new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setTitle("No longer linked")
                .setMessage("You and " + name + " are no longer partners in the app. Your own entries are still here.")
                .setPositiveButton("OK", (d, w) -> recreate())
                .setCancelable(false)
                .show();
    }

    /**
     * Before signing out, take this phone's push token off the account; otherwise the account's
     * notifications keep arriving here (and, if someone else signs in, both accounts share it).
     */
    private void signOut() {
        PartnerName.save(this, null);
        PartnerName.setNickname(this, null);
        HomePhoto.forget(binding.ivHeader);
        Map<String, Object> clear = new HashMap<>();
        clear.put("fcmToken", com.google.firebase.firestore.FieldValue.delete());
        boolean[] done = {false};
        Runnable finishSignOut = () -> {
            if (done[0]) return;
            done[0] = true;
            // A fresh token for whoever signs in next.
            FirebaseMessaging.getInstance().deleteToken();
            mAuth.signOut();
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        };
        db.collection("users").document(currentUserId).update(clear)
                .addOnCompleteListener(t -> finishSignOut.run());
        // Offline, the write waits for a connection; don't keep the person waiting for it.
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(finishSignOut, 3000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        startSweeps();
        if (currentUserId == null) return;
        // Every time: the relay drops a token that stopped working, and this puts the current one back.
        saveFcmToken();
        checkNotificationsCanArrive();
        // The app can stay open past midnight; move the "today" listeners to the new day.
        String now = ActivityLog.today();
        if (!now.equals(todayDate)) {
            todayDate = now;
            attachMyDayListeners();
        }
        // Re-attach every time: a partner listener that hit "permission denied" (e.g. in the
        // moments before a new link is confirmed by both sides) stops for good.
        if (partnerId != null) setupPartnerListeners();
        loadHeatmap();
    }

    // ─── Setup ───────────────────────────────────────────────────────────────

    /** Lets the header photo run up behind the clock instead of under a flat purple bar. */
    private void drawPhotoBehindStatusBar() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(false);

        View[] pushedDown = {binding.ivLogout, binding.ivChangePhoto, binding.tvLinkPartner, binding.tvStreakTitle};
        int[] baseMargins = new int[pushedDown.length];
        for (int i = 0; i < pushedDown.length; i++) {
            baseMargins[i] = ((android.view.ViewGroup.MarginLayoutParams) pushedDown[i].getLayoutParams()).topMargin;
        }
        int photoHeight = binding.ivHeader.getLayoutParams().height;
        View content = (View) binding.ivHeader.getParent();
        int contentBottom = content.getPaddingBottom();

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
            androidx.core.graphics.Insets bars =
                    insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            binding.ivHeader.getLayoutParams().height = photoHeight + bars.top;
            binding.photoScrim.getLayoutParams().height = photoHeight + bars.top;
            for (int i = 0; i < pushedDown.length; i++) {
                ((android.view.ViewGroup.MarginLayoutParams) pushedDown[i].getLayoutParams()).topMargin =
                        baseMargins[i] + bars.top;
            }
            content.setPadding(content.getPaddingLeft(), content.getPaddingTop(),
                    content.getPaddingRight(), contentBottom + bars.bottom);
            content.requestLayout();
            return insets;
        });
    }

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
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("notifAsked", true).apply();
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 300);
        }
    }

    private static final String PREFS = "notification_checks";
    /** Once per app start is enough; the person can always say "Not now". */
    private static boolean notificationCheckShown = false;

    /**
     * Partner updates and reminders only arrive if notifications are allowed, and (on phones that
     * close apps in the background, like vivo and Samsung) if the app may run in the background.
     * Without either, they fail silently, so say so once.
     */
    private void checkNotificationsCanArrive() {
        if (notificationCheckShown || isFinishing()) return;
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean allowed = androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled();
        if (!allowed) {
            // Android's own permission question comes first; only step in once it has been asked.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !prefs.getBoolean("notifAsked", false)) return;
            notificationCheckShown = true;
            new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_App_MaterialAlertDialog)
                    .setTitle("Notifications are off")
                    .setMessage("You won't hear when " + PartnerName.get(this) + " reads, prays or posts, "
                            + "and your reminders won't show. Turn notifications on for this app?")
                    .setPositiveButton("Turn on", (d, w) -> {
                        Intent i = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
                        try { startActivity(i); } catch (Exception e) {
                            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.fromParts("package", getPackageName(), null)));
                        }
                    })
                    .setNegativeButton("Not now", null)
                    .show();
            return;
        }
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())
                || prefs.getBoolean("batteryAsked", false)) return;
        notificationCheckShown = true;
        prefs.edit().putBoolean("batteryAsked", true).apply();
        new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setTitle("Get updates when the app is closed")
                .setMessage("Your phone may stop this app in the background to save battery, and then "
                        + PartnerName.get(this) + "'s updates and your reminders don't arrive. "
                        + "Allow it to run in the background? It uses very little battery.")
                .setPositiveButton("Allow", (d, w) -> {
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                android.net.Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.fromParts("package", getPackageName(), null)));
                    }
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    /** Without a name, partners see "null posted a prayer request" — ask once and store it. */
    private void promptForNameIfMissing(DocumentSnapshot snap) {
        if (namePromptShown || UserProfile.hasName(snap) || isFinishing()) return;
        namePromptShown = true;

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setTextColor(Color.parseColor("#1A1A1A"));
        input.setHintTextColor(Color.parseColor("#6E6E73"));
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
                    binding.tvLinkPartner.setVisibility(newPartnerId == null ? View.VISIBLE : View.GONE);
                    if (newPartnerId == null && !checkedInvites) {
                        // Someone may have accepted my code while the app was closed.
                        checkedInvites = true;
                        PartnerLink.completeAcceptedInvites(currentUserId, null);
                    }
                    if (newPartnerId != null && !newPartnerId.equals(partnerId)) {
                        partnerId = newPartnerId;
                        setupPartnerListeners();
                        loadHeatmap();
                    } else if (newPartnerId == null && partnerId != null) {
                        partnerId = null;
                        stopPartnerListeners();
                        loadHeatmap();
                        onPartnerUnlinked();
                    }
                    showUnlinkBanner(snap);
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
                    if (snap != null && snap.exists()) {
                        PartnerName.save(this, UserProfile.partnerName(snap));
                        // First time this phone sees the partner: ask what to call them.
                        if (!PartnerName.hasNickname(this) && !namePartnerAsked) {
                            namePartnerAsked = true;
                            // Signed in again? Bring back the name already chosen before asking.
                            PartnerName.restore(this, partnerId, restored -> {
                                if (restored) { recreate(); return; }
                                if (!isFinishing()) showNamePartnerDialog(true);
                            });
                        }
                        PartnerName.apply(binding.getRoot());
                    }
                    refreshProgressCard();
                });

        partnerDayListener = ActivityLog.dayRef(partnerId, todayDate)
                .addSnapshotListener((snap, e) -> {
                    partnerDayDoc = e == null ? snap : null;
                    refreshProgressCard();
                });

        binding.ivChangePhoto.setVisibility(View.VISIBLE);
        homePhotoListener = HomePhoto.ref(currentUserId, partnerId)
                .addSnapshotListener((snap, e) -> {
                    if (e == null && snap != null) HomePhoto.show(binding.ivHeader, snap);
                });
    }

    private void stopPartnerListeners() {
        if (partnerListener    != null) partnerListener.remove();
        if (partnerDayListener != null) partnerDayListener.remove();
        if (homePhotoListener  != null) homePhotoListener.remove();
        partnerListener = null;
        partnerDayListener = null;
        homePhotoListener = null;
        if (partnerId == null) binding.ivChangePhoto.setVisibility(View.GONE);
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

        // All eight (both of us, all four practices): the both-finished card.
        boolean[] mine = {
                bibleDone(currentUserId, myDayDoc),
                dayFlag(myDayDoc, "prayed") || userDate(myUserDoc, "prayedTodayDate"),
                dayFlag(myDayDoc, "journal") || userDate(myUserDoc, "journaledTodayDate"),
                dayFlag(myDayDoc, "memorize") || userDate(myUserDoc, "memorizedTodayDate")};
        boolean[] theirs = {
                hasPartner && bibleDone(partnerId, partnerDayDoc),
                hasPartner && (dayFlag(partnerDayDoc, "prayed") || userDate(partnerUserDoc, "prayedTodayDate")),
                hasPartner && (dayFlag(partnerDayDoc, "journal") || userDate(partnerUserDoc, "journaledTodayDate")),
                hasPartner && (dayFlag(partnerDayDoc, "memorize") || userDate(partnerUserDoc, "memorizedTodayDate"))};
        boolean all = hasPartner;
        for (int k = 0; k < 4; k++) all &= mine[k] && theirs[k];
        if (BuildConfig.DEBUG && getSharedPreferences("test_tools", MODE_PRIVATE).getBoolean("pretendBothDone", false)) all = true;
        showBothDone(all);

        // The tiles light up for what I've done today.
        lightTile(0, binding.cardBible,   binding.tileFaceBible,   bibleDone(currentUserId, myDayDoc));
        lightTile(1, binding.cardPrayer,  binding.tileFacePrayer,
                dayFlag(myDayDoc, "prayed") || userDate(myUserDoc, "prayedTodayDate"));
        lightTile(2, binding.cardJournal, binding.tileFaceJournal,
                dayFlag(myDayDoc, "journal") || userDate(myUserDoc, "journaledTodayDate"));
        lightTile(3, binding.cardMemory,  binding.tileFaceMemory,
                dayFlag(myDayDoc, "memorize") || userDate(myUserDoc, "memorizedTodayDate"));
    }

    /** Which tiles were lit last time, so only a change (not every refresh) pops and pulses. */
    private final Boolean[] tileLit = new Boolean[4];

    /**
     * A practice done today: its tile glows (stronger purple light, bright rim, bigger glow under
     * it); the ticks themselves are in Today's progress. When it has just been done, the tile pulses.
     */
    private void lightTile(int i, androidx.cardview.widget.CardView card, View face, boolean done) {
        boolean changed = tileLit[i] != null && tileLit[i] != done;
        boolean first = tileLit[i] == null;
        tileLit[i] = done;
        if (!first && !changed) return;
        face.setBackgroundResource(darkLook
                ? (done ? R.drawable.card_premium_done_bg : R.drawable.card_premium_bg)
                : (done ? R.drawable.card_pearl_done_bg : R.drawable.card_pearl_bg));
        float density = getResources().getDisplayMetrics().density;
        card.setCardElevation((done ? 16 : 10) * density);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            int glow = darkLook ? (done ? 0xFFB15CFF : 0xFF8E44C9) : (done ? 0xFFC9B2F0 : 0xFFCFC2EC);
            card.setOutlineSpotShadowColor(glow);
            card.setOutlineAmbientShadowColor(glow);
        }
        if (first || !done) return;
        // Just done: the tile pulses once.
        card.animate().scaleX(1.04f).scaleY(1.04f).setDuration(160)
                .withEndAction(() -> card.animate().scaleX(1f).scaleY(1f).setDuration(220).start()).start();
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

    // ─── You both finished today ─────────────────────────────────────────────

    private Boolean bothDoneShown;

    /**
     * The both-finished card when both of us have done all four practices today. The first time it
     * appears on a given day it pops in with a sparkle burst; after that it just stays.
     */
    private void showBothDone(boolean all) {
        View card = binding.cardBothDone;
        if (bothDoneShown != null && bothDoneShown == all) return;
        bothDoneShown = all;
        if (!all) { card.setVisibility(View.GONE); return; }
        binding.tvBothDoneTitle.setText("You and " + PartnerName.get(this) + " both finished today");
        card.setVisibility(View.VISIBLE);
        android.content.SharedPreferences prefs = getSharedPreferences("celebrations", MODE_PRIVATE);
        if (todayDate.equals(prefs.getString("bothDone", null))) return;
        prefs.edit().putString("bothDone", todayDate).apply();
        card.setScaleX(0.85f);
        card.setScaleY(0.85f);
        card.setAlpha(0f);
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(1000).setStartDelay(400)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f))
                .withEndAction(this::celebrateDashboard)
                .start();
    }

    /**
     * The celebration, unhurried: the four tiles fill up like balloons together; the ticks in
     * Today's progress fill up one after another, then all of them and the tiles at once; then
     * sparkles burst out of every tile and every circle across the whole screen.
     */
    private void celebrateDashboard() {
        View[] tiles = {binding.cardBible, binding.cardPrayer, binding.cardJournal, binding.cardMemory};
        View[] dots = {binding.progressBibleMe, binding.progressBiblePartner, binding.progressPrayerMe,
                binding.progressPrayerPartner, binding.progressJournalMe, binding.progressJournalPartner,
                binding.progressMemorizeMe, binding.progressMemorizePartner};
        // The circles swell beyond their rows; let every row up to the card show them whole.
        for (View d : dots) {
            for (android.view.ViewParent p = d.getParent(); p instanceof ViewGroup && p != binding.cardProgress; p = p.getParent()) {
                ((ViewGroup) p).setClipChildren(false);
                ((ViewGroup) p).setClipToPadding(false);
            }
        }
        // 1. All four tiles together.
        for (View t : tiles) balloon(t, 300, 1.12f, 750);
        // 2. The ticks one by one, down the list.
        long start = 2700;
        for (int i = 0; i < dots.length; i++) balloon(dots[i], start + i * 330L, 1.6f, 450);
        // 3. Then all of them at once.
        long together = start + dots.length * 330L + 1100;
        for (View d : dots) balloon(d, together, 1.6f, 550);
        for (View t : tiles) balloon(t, together, 1.12f, 550); // the tiles fill up with them
        // 4. As the full balloons let go, sparkles burst out of every tile and every circle.
        View screen = getWindow().getDecorView();
        screen.postDelayed(() -> {
            if (isFinishing()) return;
            screen.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
            for (View t : tiles) SparkleBurst.play(screen, t, 16, 0.6f, 3600, true, false, null);
            for (View d : dots) SparkleBurst.play(screen, d, 8, 0.5f, 3400, true, false, null);
            SparkleBurst.play(screen, binding.cardBothDone, 24, 0.7f, 3800, true, false, null);
        }, together + 550 + 340); // the moment the full balloons let go: inflate 550 ms + wobble 340 ms
    }

    /**
     * Like a balloon: swells to {@code peak} and lifts off the page over {@code inflate} ms,
     * wobbles (wide, then tall) while full, then lets go and springs back with a jiggle that settles.
     */
    private void balloon(View v, long delay, float peak, long inflate) {
        float lift = 10 * getResources().getDisplayMetrics().density;
        v.postDelayed(() -> v.animate().scaleX(peak).scaleY(peak).translationZ(lift).setStartDelay(0)
                .setDuration(inflate)
                .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                .withEndAction(() -> v.animate().scaleX(peak * 1.05f).scaleY(peak * 0.95f).setDuration(170)
                        .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                        .withEndAction(() -> v.animate().scaleX(peak * 0.96f).scaleY(peak * 1.04f).setDuration(170)
                                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(1100)
                                        .setInterpolator(t -> (float) (1 - Math.exp(-5.5 * t) * Math.cos(15 * t)))
                                        .start())
                                .start())
                        .start())
                .start(), delay);
    }

    // ─── Light sweep across the tiles ─────────────────────────────────────────

    private final java.util.List<android.animation.ValueAnimator> sweeps = new java.util.ArrayList<>();

    /** A soft band of light glides across the four tiles in turn, every few seconds. */
    private void startSweeps() {
        stopSweeps();
        View[] faces = {binding.tileFaceBible, binding.tileFacePrayer, binding.tileFaceJournal, binding.tileFaceMemory};
        for (int i = 0; i < faces.length; i++) {
            android.animation.ValueAnimator a = LightSweep.start(faces[i], 600 + i * 180L, 16, 6);
            if (a != null) sweeps.add(a);
        }
    }

    private void stopSweeps() {
        for (android.animation.ValueAnimator a : sweeps) a.cancel();
        sweeps.clear();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopSweeps();
    }

    // ─── Look: dark (Obsidian) or light (Pearl) ─────────────────────────────

    private boolean darkLook = true;
    /** Colours the layout gives the cards' text (the dark look), and what they become in the light look. */
    private static final int[][] LIGHT_TEXT = {
            {0xFFC9B2F0, 0xFF7E57C2}, {0xFFB9A6DA, 0xFF6A4FB0}, {0xFFECE7F3, 0xFF3D3459},
            {0xFFD7C4FF, 0xFF5E35B1}, {0xFFA9A1B6, 0xFF5F5F64}};
    private final Map<View, Integer> layoutColour = new HashMap<>();

    private void chooseLook() {
        new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setTitle("Appearance")
                .setSingleChoiceItems(Appearance.NAMES, Appearance.get(this), (d, which) -> {
                    d.dismiss();
                    Appearance.set(this, which); // the whole app redraws in the new look
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Dresses the dashboard in the chosen look: the tiles, the two cards and what's in them, the
     * beads and week chips, the page behind and the navigation bar. Dark is the default.
     */
    private void applyLook() {
        darkLook = Appearance.isDark(this);
        int page = darkLook ? 0xFF17131D : 0xFFF6F2FB;
        binding.getRoot().setBackgroundColor(page);
        getWindow().setNavigationBarColor(page);
        androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightNavigationBars(!darkLook);

        // Tiles: white icons with a halo and glowing labels on dark; purple on light.
        for (View face : new View[]{binding.tileFaceBible, binding.tileFacePrayer, binding.tileFaceJournal, binding.tileFaceMemory}) {
            ViewGroup g = (ViewGroup) face;
            for (int k = 0; k < g.getChildCount(); k++) {
                View child = g.getChildAt(k);
                if (child instanceof ImageView) {
                    ((ImageView) child).setImageTintList(android.content.res.ColorStateList.valueOf(darkLook ? Color.WHITE : 0xFF8C6FCB));
                    child.setBackgroundResource(darkLook ? R.drawable.bg_icon_glow : 0);
                } else if (child instanceof TextView) {
                    TextView label = (TextView) child;
                    label.setTextColor(darkLook ? Color.WHITE : 0xFF4E4168);
                    label.setShadowLayer(darkLook ? 10 : 0, 0, 0, 0x739F6BFF);
                }
            }
        }
        java.util.Arrays.fill(tileLit, null); // so the tiles redraw in this look

        // The two cards and their contents.
        for (com.google.android.material.card.MaterialCardView card : new com.google.android.material.card.MaterialCardView[]{binding.cardProgress, binding.cardWeek}) {
            card.getChildAt(0).setBackgroundResource(darkLook ? R.drawable.bg_obsidian_card : R.drawable.bg_pearl_card);
            recolour(card);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                int glow = darkLook ? 0xFF8E44C9 : 0xFFB39DDB;
                card.setOutlineSpotShadowColor(glow);
                card.setOutlineAmbientShadowColor(glow);
            }
        }
        binding.legendNone.setBackgroundResource(darkLook ? R.drawable.hm_none : R.drawable.hm_none_light);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            for (View tile : new View[]{binding.cardBible, binding.cardPrayer, binding.cardJournal, binding.cardMemory}) {
                tile.setOutlineSpotShadowColor(darkLook ? 0xFF8E44C9 : 0xFFCFC2EC);
                tile.setOutlineAmbientShadowColor(darkLook ? 0xFF8E44C9 : 0xFFCFC2EC);
            }
        }
        refreshProgressCard();
        if (currentUserId != null) loadHeatmap();
    }

    /** Text and small icons inside a card: the layout's (dark-look) colour, or its light-look match. */
    private void recolour(View v) {
        if (v instanceof ViewGroup) {
            for (int k = 0; k < ((ViewGroup) v).getChildCount(); k++) recolour(((ViewGroup) v).getChildAt(k));
            return;
        }
        Integer original = layoutColour.get(v);
        if (original == null) {
            if (v instanceof TextView) original = ((TextView) v).getCurrentTextColor();
            else if (v instanceof ImageView && ((ImageView) v).getImageTintList() != null)
                original = ((ImageView) v).getImageTintList().getDefaultColor();
            else return;
            layoutColour.put(v, original);
        }
        int colour = original;
        if (!darkLook) for (int[] pair : LIGHT_TEXT) if (pair[0] == original) colour = pair[1];
        if (v instanceof TextView) ((TextView) v).setTextColor(colour);
        else ((ImageView) v).setImageTintList(android.content.res.ColorStateList.valueOf(colour));
    }

    // ── Dot: circle background + double-tick icon when done ──────────────────
    private void setDot(ImageView view, boolean done) {
        if (done) {
            view.setBackgroundResource(R.drawable.ic_progress_done); // solid purple circle
            view.setImageResource(R.drawable.ic_check_double);       // white double tick
            view.setColorFilter(Color.WHITE);
        } else {
            view.setBackgroundResource(darkLook ? R.drawable.ic_progress_todo : R.drawable.ic_progress_todo_light);
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
                cell.setBackgroundResource(darkLook ? R.drawable.hm_none : R.drawable.hm_none_light);
                cell.setTextColor(darkLook ? 0xFFB9A6DA : 0xFF6A4FB0);
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
            case "verse_reminder": case "memorize":
                startActivity(new Intent(this, MemorizeActivity.class));
                break;
            case "history":
                startActivity(new Intent(this, HistoryActivity.class));
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
        if (homePhotoListener   != null) homePhotoListener.remove();
        if (inviteListener      != null) inviteListener.remove();
    }

    // ─── Partner linking ─────────────────────────────────────────────────────

    private void showLinkPartnerDialog() {
        new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setTitle("Link your partner")
                .setMessage("One of you shares a code, the other enters it. Codes last 24 hours.")
                .setPositiveButton("Share my code", (d, w) -> createAndShowCode())
                .setNegativeButton("Enter a code", (d, w) -> showEnterCodeDialog())
                .setNeutralButton("Cancel", null)
                .show();
    }

    private void createAndShowCode() {
        PartnerLink.createInvite(currentUserId, new PartnerLink.Result() {
            @Override public void onSuccess(String code) {
                if (isFinishing()) return;
                TextView codeView = new TextView(MainActivity.this);
                codeView.setText(code.substring(0, 3) + " " + code.substring(3));
                codeView.setTextSize(34);
                codeView.setLetterSpacing(0.15f);
                codeView.setTextIsSelectable(true);
                codeView.setGravity(android.view.Gravity.CENTER);
                codeView.setTextColor(0xFF4527A0);
                codeView.setPadding(0, 24, 0, 8);

                androidx.appcompat.app.AlertDialog dialog =
                        new MaterialAlertDialogBuilder(MainActivity.this, R.style.ThemeOverlay_App_MaterialAlertDialog)
                                .setTitle("Your code")
                                .setMessage("Ask your partner to open the app, tap \"Link your partner\" → \"Enter a code\". This screen updates once they've joined.")
                                .setView(codeView)
                                .setPositiveButton("Share", null)
                                .setNegativeButton("Close", null)
                                .create();
                dialog.setOnShowListener(x -> dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                        .setOnClickListener(b -> {
                            Intent share = new Intent(Intent.ACTION_SEND);
                            share.setType("text/plain");
                            share.putExtra(Intent.EXTRA_TEXT, "Join me on Spiritual Tracker! My partner code is " + code);
                            startActivity(Intent.createChooser(share, "Share code"));
                        }));
                dialog.setOnDismissListener(x -> {
                    if (inviteListener != null) { inviteListener.remove(); inviteListener = null; }
                });
                dialog.show();

                if (inviteListener != null) inviteListener.remove();
                inviteListener = db.collection("invites").document(code).addSnapshotListener((snap, e) -> {
                    if (snap == null || !snap.exists() || snap.getString("acceptedBy") == null) return;
                    PartnerLink.completeAcceptedInvites(currentUserId, new PartnerLink.Result() {
                        @Override public void onSuccess(String partner) {
                            Toast.makeText(MainActivity.this, "You're linked! 🎉", Toast.LENGTH_LONG).show();
                            if (dialog.isShowing()) dialog.dismiss();
                        }
                        @Override public void onError(String message) {}
                    });
                });
            }
            @Override public void onError(String message) {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showEnterCodeDialog() {
        EditText input = new EditText(this);
        input.setHint("e.g. ABC 234");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input);

        new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setTitle("Enter your partner's code")
                .setView(wrap)
                .setPositiveButton("Link", (d, w) -> PartnerLink.acceptInvite(currentUserId,
                        input.getText().toString(), new PartnerLink.Result() {
                            @Override public void onSuccess(String partner) {
                                Toast.makeText(MainActivity.this,
                                        "Linked! Your partner's app will finish linking next time it opens.",
                                        Toast.LENGTH_LONG).show();
                            }
                            @Override public void onError(String message) {
                                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                            }
                        }))
                .setNegativeButton("Cancel", null)
                .show();
    }
}
