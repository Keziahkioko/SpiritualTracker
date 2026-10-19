package com.keziah.spiritualtracker;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;

import java.util.Collections;

public class BibleActivity extends AppCompatActivity {

    private View btnNudge;
    private CheckBox cbRead;
    private EditText etVerse;
    private Button btnSaveVerse;

    private TextView tvPartnerStatus, tvPartnerStatusIcon, tvPartnerVerse;
    private ImageView imgPartnerStatusIcon;
    private View layoutPartnerVerse;
    private FrameLayout framePartnerIcon;

    private FirebaseAuth auth;
    private FirebaseFirestore db;
    private String currentUserId;
    private String partnerId = null;
    private String myName = UserProfile.FALLBACK_NAME;
    private String partnerName = "your partner";
    private String todayDate;

    private ListenerRegistration myDayListener, partnerDayListener, legacyListener;
    private DocumentSnapshot myDayDoc, partnerDayDoc, legacyDoc;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bible);
        PartnerName.apply(findViewById(android.R.id.content));

        auth = FirebaseAuth.getInstance();
        db   = FirebaseFirestore.getInstance();

        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser == null) {
            // Session expired — send back to login
            finish();
            return;
        }
        currentUserId = currentUser.getUid();
        todayDate = ActivityLog.today();

        cbRead          = findViewById(R.id.cbRead);
        etVerse         = findViewById(R.id.etVerse);
        btnSaveVerse    = findViewById(R.id.btnSaveVerse);
        btnNudge        = findViewById(R.id.btnNudge);
        tvPartnerStatus     = findViewById(R.id.tvPartnerStatus);
        tvPartnerStatusIcon = findViewById(R.id.tvPartnerStatusIcon);
        imgPartnerStatusIcon = findViewById(R.id.imgPartnerStatusIcon);
        tvPartnerVerse      = findViewById(R.id.tvPartnerVerse);
        layoutPartnerVerse  = findViewById(R.id.layoutPartnerVerse);
        framePartnerIcon    = findViewById(R.id.framePartnerIcon);

        StatusBarUtil.drawBehind(this, findViewById(R.id.bibleHeader), findViewById(R.id.bibleContent));
        findViewById(R.id.btnBibleBack).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.tvBibleDate)).setText(
                new java.text.SimpleDateFormat("EEEE, d MMMM", java.util.Locale.getDefault()).format(new java.util.Date()));

        listenToMyDay();
        fetchPartnerId();
        setupListeners();
    }

    private void fetchPartnerId() {
        db.collection("users").document(currentUserId).get()
                .addOnSuccessListener(documentSnapshot -> {
                    myName = UserProfile.displayName(documentSnapshot);
                    String pid = documentSnapshot.getString("partnerId");
                    if (pid != null && !pid.isEmpty()) {
                        partnerId = pid;
                        listenToPartnerDay();
                        db.collection("users").document(pid).get().addOnSuccessListener(p -> {
                            partnerName = PartnerName.display(this, p);
                            renderPartner();
                        });
                    } else {
                        tvPartnerStatus.setText("No Partner Linked");
                    }
                });
    }

    private void listenToMyDay() {
        myDayListener = ActivityLog.dayRef(currentUserId, todayDate)
                .addSnapshotListener((snap, e) -> {
                    if (e != null) return;
                    myDayDoc = snap;
                    renderMine();
                });
        // Readings saved by older builds of the app live in one shared doc per date.
        legacyListener = db.collection("daily_readings").document(todayDate)
                .addSnapshotListener((snap, e) -> {
                    legacyDoc = e == null ? snap : null;
                    renderMine();
                    renderPartner();
                });
    }

    private void listenToPartnerDay() {
        partnerDayListener = ActivityLog.dayRef(partnerId, todayDate)
                .addSnapshotListener((snap, e) -> {
                    if (e != null) return;
                    partnerDayDoc = snap;
                    renderPartner();
                });
    }

    private boolean readToday(String uid, DocumentSnapshot dayDoc) {
        if (dayDoc != null && dayDoc.contains("bible")) return Boolean.TRUE.equals(dayDoc.getBoolean("bible"));
        return legacyDoc != null && Boolean.TRUE.equals(legacyDoc.getBoolean(uid));
    }

    private String noteToday(String uid, DocumentSnapshot dayDoc) {
        String note = dayDoc != null ? dayDoc.getString("bibleNote") : null;
        if ((note == null || note.isEmpty()) && legacyDoc != null) note = legacyDoc.getString(uid + "_verse");
        return note;
    }

    private void renderMine() {
        setReadButton(readToday(currentUserId, myDayDoc));
        String myVerse = noteToday(currentUserId, myDayDoc);
        if (myVerse != null && !myVerse.isEmpty() && !etVerse.hasFocus()
                && etVerse.getText().toString().trim().isEmpty()) {
            etVerse.setText(myVerse);
        }
    }

    /** The big read button fills purple and changes its wording once today's reading is done. */
    private void setReadButton(boolean read) {
        cbRead.setChecked(read);
        cbRead.setText(read ? "Read today  \u2713" : "I have read today");
        // A soft purple glow under the button once it's done.
        float density = getResources().getDisplayMetrics().density;
        cbRead.setElevation(read ? 8 * density : 0);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            cbRead.setOutlineSpotShadowColor(Color.parseColor("#8E24AA"));
            cbRead.setOutlineAmbientShadowColor(Color.parseColor("#8E24AA"));
        }
    }

    private void renderPartner() {
        if (partnerId == null) return;
        updatePartnerUI(readToday(partnerId, partnerDayDoc), noteToday(partnerId, partnerDayDoc));
    }

    private void updatePartnerUI(boolean hasRead, String partnerVerseText) {
        if (hasRead) {
            tvPartnerStatus.setText(partnerName + " finished today's reading!");
            tvPartnerStatus.setTextColor(Color.parseColor("#212121"));
            tvPartnerStatusIcon.setVisibility(View.GONE);
            imgPartnerStatusIcon.setVisibility(View.VISIBLE);
            btnNudge.setVisibility(View.GONE);
            imgPartnerStatusIcon.setImageResource(R.drawable.ic_partner_done);
            framePartnerIcon.setBackgroundResource(0);
            if (partnerVerseText != null && !partnerVerseText.trim().isEmpty()) {
                layoutPartnerVerse.setVisibility(View.VISIBLE);
                tvPartnerVerse.setText("“" + partnerVerseText.trim() + "”");
            } else {
                layoutPartnerVerse.setVisibility(View.GONE);
            }
        } else {
            tvPartnerStatus.setText("Waiting for " + partnerName + "\u2026");
            tvPartnerStatus.setTextColor(Color.parseColor("#616161"));
            imgPartnerStatusIcon.setVisibility(View.GONE);
            tvPartnerStatusIcon.setVisibility(View.VISIBLE);
            tvPartnerStatusIcon.setText("⏳");
            btnNudge.setVisibility(View.VISIBLE);
            layoutPartnerVerse.setVisibility(View.GONE);
            framePartnerIcon.setBackgroundResource(R.drawable.circle_bg_grey);
        }
    }

    private void setupListeners() {
        cbRead.setOnClickListener(view -> {
            boolean isChecked = cbRead.isChecked();
            setReadButton(isChecked);
            ActivityLog.setBible(currentUserId, isChecked, etVerse.getText().toString());
            if (isChecked) {
                Toast.makeText(this, "Marked as Read!", Toast.LENGTH_SHORT).show();
                if (partnerId != null) {
                    tellPartnerIRead();
                } else {
                    // Tapped before the profile loaded: look the partner up first, so the push isn't lost.
                    db.collection("users").document(currentUserId).get().addOnSuccessListener(me -> {
                        String pid = me.getString("partnerId");
                        if (pid == null || pid.isEmpty()) return;
                        partnerId = pid;
                        myName = UserProfile.displayName(me);
                        tellPartnerIRead();
                    });
                }
            }
        });

        btnSaveVerse.setOnClickListener(view -> {
            ActivityLog.setBibleNote(currentUserId, etVerse.getText().toString());
            Toast.makeText(this, "Note Saved!", Toast.LENGTH_SHORT).show();
        });

        btnNudge.setOnClickListener(view -> {
            btnNudge.animate().scaleX(0.8f).scaleY(0.8f).setDuration(100)
                    .withEndAction(() -> btnNudge.animate().scaleX(1f).scaleY(1f).setDuration(100));
            if (partnerId == null) {
                Toast.makeText(this, "No partner linked yet!", Toast.LENGTH_SHORT).show();
                return;
            }
            PartnerNotifier.incrementUnread(partnerId, "unreadBible");
            PartnerNotifier.notifyPartner(partnerId, "You've been nudged! ✨",
                    myName + " is reminding you to read the Word! 📖", "bible_nudge", "");
            Toast.makeText(this, "Nudge sent! ✨", Toast.LENGTH_SHORT).show();
        });
    }

    private void tellPartnerIRead() {
        checkIfTeamIsDone();
        PartnerNotifier.incrementUnread(partnerId, "unreadBible");
        PartnerNotifier.notifyPartner(partnerId, "Goal Completed! ✅",
                myName + " just finished their reading for today!", "bible_update", "");
    }

    /**
     * Adds one to the couple's shared score the first time both have read on a given day.
     * The "awarded" marker is stored per couple, so one couple can't block another's score.
     */
    private void checkIfTeamIsDone() {
        if (partnerId == null) return;
        String pairId = currentUserId.compareTo(partnerId) < 0
                ? currentUserId + "_" + partnerId : partnerId + "_" + currentUserId;
        DocumentReference awardRef   = db.collection("partnerships").document(pairId)
                .collection("score_awards").document(todayDate);
        DocumentReference partnerDay = ActivityLog.dayRef(partnerId, todayDate);
        DocumentReference meRef      = db.collection("users").document(currentUserId);
        DocumentReference partnerRef = db.collection("users").document(partnerId);
        // Snapshot of the legacy doc so a score already awarded by the old app isn't awarded twice.
        DocumentSnapshot legacy = legacyDoc;

        db.runTransaction(transaction -> {
            DocumentSnapshot award  = transaction.get(awardRef);
            DocumentSnapshot theirs = transaction.get(partnerDay);
            DocumentSnapshot me     = transaction.get(meRef);
            if (award.exists()) return null;

            // I just ticked the box; my own day-log write may still be in flight, so it isn't re-read here.
            boolean partnerDone = Boolean.TRUE.equals(theirs.getBoolean("bible"))
                    || (legacy != null && Boolean.TRUE.equals(legacy.getBoolean(partnerId)));
            boolean legacyAwarded = legacy != null && Boolean.TRUE.equals(legacy.getBoolean("score_awarded"));
            if (!partnerDone || legacyAwarded) return null;

            Long cur = me.getLong("sharedScore");
            long newScore = (cur == null ? 0L : cur) + 1;
            transaction.set(meRef, Collections.singletonMap("sharedScore", newScore), SetOptions.merge());
            transaction.set(partnerRef, Collections.singletonMap("sharedScore", newScore), SetOptions.merge());
            transaction.set(awardRef, Collections.singletonMap("awardedBy", currentUserId));
            return newScore;
        }).addOnSuccessListener(result -> {
            if (result != null)
                Toast.makeText(this, "Streak Increased! New Score: " + result, Toast.LENGTH_LONG).show();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (myDayListener      != null) myDayListener.remove();
        if (partnerDayListener != null) partnerDayListener.remove();
        if (legacyListener     != null) legacyListener.remove();
    }
}
