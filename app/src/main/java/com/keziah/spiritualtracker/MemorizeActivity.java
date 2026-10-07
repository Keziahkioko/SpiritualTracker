package com.keziah.spiritualtracker;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

public class MemorizeActivity extends AppCompatActivity {

    private static final int[]  INTERVALS    = {1, 2, 4, 7, 30};
    private static final int    MASTERED_IDX = 4;
    private static final String DATE_FMT     = "yyyy-MM-dd";
    private static final String NOTIFY_URL   =
            BuildConfig.NOTIFY_URL;

    // Views
    private RecyclerView                 rvVerses;
    private LinearLayout                 layoutEmpty;
    private TextView                     tvDueCount, tvMasteredCount, tvPartnerMastered;
    private TextView                     tvHeaderTitle;
    private TextView                     tvTogglePartnerLabel; // ← text label inside the FrameLayout pill
    private View                         btnTogglePartner;     // ← now View, not TextView
    private ExtendedFloatingActionButton fabAddVerse;
    private View                         layoutPartnerPanel;

    // Data
    private final List<MemoryVerse> activeList        = new ArrayList<>();
    private final List<MemoryVerse> masteredList      = new ArrayList<>();
    private final List<MemoryVerse> partnerSharedList = new ArrayList<>();
    private final List<Object>      displayList       = new ArrayList<>();

    // Firebase
    private FirebaseFirestore    db;
    private String               currentUserId;
    private String               partnerId;
    private String               myName;
    private String               partnerFcmToken;
    private ListenerRegistration myVerseListener;
    private ListenerRegistration partnerVerseListener;

    // State
    private boolean      viewingPartner = false;
    private VerseAdapter adapter;

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_memorize);

        FirebaseAuth auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        if (auth.getCurrentUser() == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        currentUserId = auth.getCurrentUser().getUid();

        rvVerses              = findViewById(R.id.rvVerses);
        layoutEmpty           = findViewById(R.id.layoutMemorizeEmpty);
        tvDueCount            = findViewById(R.id.tvDueCount);
        tvMasteredCount       = findViewById(R.id.tvMasteredCount);
        tvPartnerMastered     = findViewById(R.id.tvPartnerMastered);
        tvHeaderTitle         = findViewById(R.id.tvHeaderTitle);
        btnTogglePartner      = findViewById(R.id.btnTogglePartner);      // View
        tvTogglePartnerLabel  = findViewById(R.id.tvTogglePartnerLabel);  // TextView inside the pill
        fabAddVerse           = findViewById(R.id.fabAddVerse);
        layoutPartnerPanel    = findViewById(R.id.layoutPartnerPanel);

        // WindowInsets — push header below status bar precisely
        View headerLayout = findViewById(R.id.headerLayout);
        ViewCompat.setOnApplyWindowInsetsListener(headerLayout, (v, insets) -> {
            int statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            int extraPadding    = getResources()
                    .getDimensionPixelSize(R.dimen.header_top_padding);
            v.setPadding(
                    v.getPaddingLeft(),
                    statusBarHeight + extraPadding,
                    v.getPaddingRight(),
                    v.getPaddingBottom());
            return insets;
        });

        adapter = new VerseAdapter(displayList, this::onVerseTapped, this::onVerseLongTapped);
        rvVerses.setLayoutManager(new LinearLayoutManager(this));
        rvVerses.setAdapter(adapter);

        fabAddVerse.setOnClickListener(v -> showAddChoiceDialog());

        if (btnTogglePartner != null)
            btnTogglePartner.setOnClickListener(v -> togglePartnerView());

        // Schedule daily 8am reminder
        DailyVerseReminderWorker.schedule(this);

        fetchUserThenListen();
    }

    // ─── Bootstrap ───────────────────────────────────────────────────────────

    private void fetchUserThenListen() {
        db.collection("users").document(currentUserId).get()
                .addOnSuccessListener(doc -> {
                    myName    = UserProfile.displayName(doc);
                    partnerId = doc.getString("partnerId");
                    if (partnerId != null && partnerId.isEmpty()) partnerId = null;

                    if (partnerId != null) {
                        // Show partner pill with pulse animation
                        if (btnTogglePartner != null) {
                            btnTogglePartner.setVisibility(View.VISIBLE);
                            Animation pulse = AnimationUtils.loadAnimation(this, R.anim.pulse);
                            btnTogglePartner.startAnimation(pulse);
                        }

                        db.collection("users").document(partnerId).get()
                                .addOnSuccessListener(pDoc -> {
                                    partnerFcmToken = pDoc.getString("fcmToken");
                                    // Show partner's name on the pill label
                                    String pName = pDoc.getString("name");
                                    if (tvTogglePartnerLabel != null && pName != null) {
                                        tvTogglePartnerLabel.setText(pName + "'s verses");
                                    }
                                    loadPartnerStats();
                                    listenToPartnerSharedVerses();
                                });
                    }
                    listenToMyVerses();
                });
    }

    // ─── My verses listener ──────────────────────────────────────────────────

    private void listenToMyVerses() {
        myVerseListener = db.collection("users").document(currentUserId)
                .collection("memory_verses")
                .orderBy("nextReviewDate", Query.Direction.ASCENDING)
                .addSnapshotListener((value, error) -> {
                    if (error != null || value == null) return;

                    activeList.clear();
                    masteredList.clear();
                    int due = 0;
                    Date now = new Date();

                    for (DocumentSnapshot doc : value.getDocuments()) {
                        MemoryVerse v = doc.toObject(MemoryVerse.class);
                        if (v == null) continue;
                        v.setId(doc.getId());
                        if ("mastered".equals(v.getStage())) {
                            masteredList.add(v);
                        } else {
                            activeList.add(v);
                            if (isDue(v)) due++;
                        }
                    }

                    if (!viewingPartner) {
                        rebuildMyDisplayList();
                        updateStats(due, masteredList.size());
                        boolean empty = activeList.isEmpty() && masteredList.isEmpty();
                        layoutEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                        rvVerses.setVisibility(empty ? View.GONE : View.VISIBLE);
                    }
                });
    }

    // ─── Partner shared verses listener ──────────────────────────────────────

    private void listenToPartnerSharedVerses() {
        if (partnerId == null) return;
        partnerVerseListener = db.collection("users").document(partnerId)
                .collection("memory_verses")
                .whereEqualTo("shared", true)
                .addSnapshotListener((value, error) -> {
                    if (error != null || value == null) return;
                    partnerSharedList.clear();
                    for (DocumentSnapshot doc : value.getDocuments()) {
                        MemoryVerse v = doc.toObject(MemoryVerse.class);
                        if (v != null) {
                            v.setId(doc.getId());
                            partnerSharedList.add(v);
                        }
                    }
                    // Sorted here: equality + orderBy on another field needs a composite index,
                    // and without it this list silently stayed empty.
                    java.util.Collections.sort(partnerSharedList, (a, b) -> {
                        long ta = a.getNextReviewDate() != null ? a.getNextReviewDate().toDate().getTime() : Long.MAX_VALUE;
                        long tb = b.getNextReviewDate() != null ? b.getNextReviewDate().toDate().getTime() : Long.MAX_VALUE;
                        return Long.compare(ta, tb);
                    });
                    if (viewingPartner) rebuildPartnerDisplayList();
                });
    }

    // ─── Toggle my / partner view ────────────────────────────────────────────

    private void togglePartnerView() {
        viewingPartner = !viewingPartner;
        if (viewingPartner) {
            tvHeaderTitle.setText("Partner's Verses");
            if (tvTogglePartnerLabel != null) tvTogglePartnerLabel.setText("My verses");
            // Stop pulse when tapped — it served its purpose
            btnTogglePartner.clearAnimation();
            fabAddVerse.setVisibility(View.GONE);
            rebuildPartnerDisplayList();
        } else {
            tvHeaderTitle.setText(getString(R.string.your_verses));
            // Restore partner name on the label
            if (tvTogglePartnerLabel != null) tvTogglePartnerLabel.setText("Partner's verses");
            fabAddVerse.setVisibility(View.VISIBLE);
            rebuildMyDisplayList();
        }
    }

    private void rebuildMyDisplayList() {
        displayList.clear();
        displayList.addAll(activeList);
        if (!masteredList.isEmpty()) {
            displayList.add("MASTERED");
            displayList.addAll(masteredList);
        }
        adapter.notifyDataSetChanged();
        boolean empty = activeList.isEmpty() && masteredList.isEmpty();
        layoutEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        rvVerses.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void rebuildPartnerDisplayList() {
        displayList.clear();
        if (partnerSharedList.isEmpty()) {
            displayList.add("NO_SHARED");
        } else {
            for (MemoryVerse v : partnerSharedList)
                if (!"mastered".equals(v.getStage())) displayList.add(v);
            List<MemoryVerse> pm = new ArrayList<>();
            for (MemoryVerse v : partnerSharedList)
                if ("mastered".equals(v.getStage())) pm.add(v);
            if (!pm.isEmpty()) {
                displayList.add("MASTERED");
                displayList.addAll(pm);
            }
        }
        adapter.notifyDataSetChanged();
        layoutEmpty.setVisibility(View.GONE);
        rvVerses.setVisibility(View.VISIBLE);
    }

    private void loadPartnerStats() {
        db.collection("users").document(partnerId)
                .collection("memory_verses")
                .whereEqualTo("stage", "mastered")
                .get()
                .addOnSuccessListener(q -> {
                    if (layoutPartnerPanel != null) layoutPartnerPanel.setVisibility(View.VISIBLE);
                    if (tvPartnerMastered != null) tvPartnerMastered.setText(q.size() + " mastered");
                })
                .addOnFailureListener(e -> {
                    if (layoutPartnerPanel != null) layoutPartnerPanel.setVisibility(View.GONE);
                });
    }

    private void updateStats(int due, int mastered) {
        tvDueCount.setText(String.valueOf(due));
        tvMasteredCount.setText(String.valueOf(mastered));
    }

    // ─── Add choice ──────────────────────────────────────────────────────────

    private void showAddChoiceDialog() {
        new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setTitle("What would you like to add?")
                .setItems(new String[]{"A single verse", "A long passage (chapter)"}, (d, which) -> {
                    if (which == 0) showAddVerseDialog();
                    else            showAddPassageDialog();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ─── Single verse dialog ─────────────────────────────────────────────────

    private void showAddVerseDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_add_verse, null);
        TextInputEditText etRef   = v.findViewById(R.id.etVerseReference);
        TextInputEditText etText  = v.findViewById(R.id.etVerseText);
        CheckBox          cbShare = v.findViewById(R.id.cbSharedVerse);
        if (cbShare != null) cbShare.setVisibility(partnerId != null ? View.VISIBLE : View.GONE);

        new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setView(v)
                .setPositiveButton("Add Verse", (d, w) -> {
                    String ref  = etRef.getText()  != null ? etRef.getText().toString().trim() : "";
                    String text = etText.getText() != null ? etText.getText().toString().trim() : "";
                    boolean shared = cbShare != null && cbShare.isChecked();
                    if (!ref.isEmpty() && !text.isEmpty()) saveVerse(ref, text, shared, null, -1);
                    else Toast.makeText(this, "Please fill both fields", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ─── Passage / chunk dialog ───────────────────────────────────────────────

    private void showAddPassageDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_add_passage, null);
        TextInputEditText etRef   = v.findViewById(R.id.etPassageReference);
        TextInputEditText etText  = v.findViewById(R.id.etPassageText);
        CheckBox          cbShare = v.findViewById(R.id.cbPassageShared);
        TextView          btn3    = v.findViewById(R.id.btnChunk3);
        TextView          btn4    = v.findViewById(R.id.btnChunk4);
        TextView          btn5    = v.findViewById(R.id.btnChunk5);

        if (cbShare != null) cbShare.setVisibility(partnerId != null ? View.VISIBLE : View.GONE);

        final int[] chunkSize = {3};

        View.OnClickListener chunkClick = cv -> {
            chunkSize[0] = Integer.parseInt(((TextView) cv).getText().toString());
            btn3.setBackgroundResource(chunkSize[0] == 3 ? R.drawable.chunk_btn_selected : R.drawable.chunk_btn_unselected);
            btn4.setBackgroundResource(chunkSize[0] == 4 ? R.drawable.chunk_btn_selected : R.drawable.chunk_btn_unselected);
            btn5.setBackgroundResource(chunkSize[0] == 5 ? R.drawable.chunk_btn_selected : R.drawable.chunk_btn_unselected);
            btn3.setTextColor(chunkSize[0] == 3 ? 0xFFFFFFFF : 0xFF7E57C2);
            btn4.setTextColor(chunkSize[0] == 4 ? 0xFFFFFFFF : 0xFF7E57C2);
            btn5.setTextColor(chunkSize[0] == 5 ? 0xFFFFFFFF : 0xFF7E57C2);
        };
        btn3.setOnClickListener(chunkClick);
        btn4.setOnClickListener(chunkClick);
        btn5.setOnClickListener(chunkClick);

        new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setView(v)
                .setPositiveButton("Split & Add", (d, w) -> {
                    String  baseRef  = etRef.getText()  != null ? etRef.getText().toString().trim()  : "";
                    String  fullText = etText.getText() != null ? etText.getText().toString().trim() : "";
                    boolean shared   = cbShare != null && cbShare.isChecked();
                    if (baseRef.isEmpty() || fullText.isEmpty()) {
                        Toast.makeText(this, "Please fill both fields", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    splitAndSavePassage(baseRef, fullText, chunkSize[0], shared);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void splitAndSavePassage(String baseRef, String fullText, int chunkSize, boolean shared) {
        // Step 1: Try to split by verse numbers first (e.g. "1 ", "[1]", "1.")
        // This handles pasted Bible text that doesn't have newlines between verses
        String normalized = fullText.trim();

        // Insert a newline before each verse number pattern so we can split cleanly
        // Matches: a digit (or digits) followed by a space at the start or after punctuation
        normalized = normalized.replaceAll("(?<=[.!?\"\\u2019]?)\\s+(\\d+)\\s+", "\n$1 ");

        // Also handle formats like [1] or (1)
        normalized = normalized.replaceAll("\\[(\\d+)\\]", "\n$1 ");
        normalized = normalized.replaceAll("\\((\\d+)\\)", "\n$1 ");

        // Step 2: Split into individual verses by newline
        String[] lines = normalized.split("\n");

        List<String> verses = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) verses.add(trimmed);
        }

        if (verses.isEmpty()) {
            Toast.makeText(this, "Could not read the passage", Toast.LENGTH_SHORT).show();
            return;
        }

        // Step 3: Group verses into chunks of the selected size
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int count = 0;

        for (String verse : verses) {
            if (count > 0) current.append("\n");
            current.append(verse);
            count++;
            if (count >= chunkSize) {
                chunks.add(current.toString());
                current = new StringBuilder();
                count = 0;
            }
        }
        if (current.length() > 0) chunks.add(current.toString());

        if (chunks.isEmpty()) {
            Toast.makeText(this, "Could not split the passage", Toast.LENGTH_SHORT).show();
            return;
        }

        for (int i = 0; i < chunks.size(); i++) {
            String chunkRef = baseRef + " (Part " + (i + 1) + " of " + chunks.size() + ")";
            saveVerse(chunkRef, chunks.get(i), shared, baseRef, i);
        }

        Toast.makeText(this,
                chunks.size() + " chunks added for \"" + baseRef + "\"",
                Toast.LENGTH_LONG).show();
    }

    // ─── Save ────────────────────────────────────────────────────────────────

    private void saveVerse(String reference, String text, boolean shared,
                           String passageId, int chunkIndex) {
        String today  = todayString();

        // Part 1 (chunkIndex 0) is due tomorrow
        // All subsequent parts are locked — due date set 10 years away
        Date dueDate = (chunkIndex <= 0)
                ? addDays(new Date(), INTERVALS[0])
                : addDays(new Date(), 365 * 10);

        Map<String, Object> data = new HashMap<>();
        data.put("reference",        reference);
        data.put("text",             text);
        data.put("intervalIndex",    0);
        data.put("stage",            "learning");
        data.put("nextReviewDate",   new Timestamp(dueDate));
        data.put("lastReviewedDate", today);
        data.put("addedAt",          FieldValue.serverTimestamp());
        data.put("shared",           shared);
        data.put("ownerName",        myName);
        if (passageId != null) {
            data.put("passageId",  passageId);
            data.put("chunkIndex", chunkIndex);
        }

        db.collection("users").document(currentUserId)
                .collection("memory_verses")
                .add(data)
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Failed to save verse", Toast.LENGTH_SHORT).show());
    }

    // ─── Mark reviewed ───────────────────────────────────────────────────────

    private void markReviewed(MemoryVerse verse, boolean gotIt) {
        if (verse.getId() == null) return;

        String  today      = todayString();
        boolean sameDay    = today.equals(verse.getLastReviewedDate());
        int     currentIdx = verse.getIntervalIndex() != null ? verse.getIntervalIndex() : 0;
        int     nextIdx;
        String  newStage;

        // Any honest attempt counts as today's memorization practice.
        logPractice(verse.getReference());

        if (gotIt) {
            if (sameDay) {
                Toast.makeText(this,
                        "Great practice! Come back tomorrow to advance 📖",
                        Toast.LENGTH_LONG).show();
                return;
            }
            nextIdx  = Math.min(currentIdx + 1, INTERVALS.length - 1);
            newStage = (nextIdx >= MASTERED_IDX) ? "mastered"
                    : (nextIdx >= 2)             ? "reviewing"
                    : "learning";
        } else {
            nextIdx  = Math.max(0, currentIdx - 1);
            newStage = "learning";
        }

        Map<String, Object> updates = new HashMap<>();
        updates.put("intervalIndex",    nextIdx);
        updates.put("stage",            newStage);
        updates.put("nextReviewDate",   new Timestamp(addDays(new Date(), INTERVALS[nextIdx])));
        updates.put("lastReviewedDate", today);

        db.collection("users").document(currentUserId)
                .collection("memory_verses")
                .document(verse.getId())
                .update(updates)
                .addOnSuccessListener(unused -> {
                    if ("mastered".equals(newStage)) {
                        Toast.makeText(this, "🎉 Verse mastered!", Toast.LENGTH_LONG).show();
                        notifyPartnerOfMastery(verse.getReference());
                    }

                    // ── Sequential unlock ──
                    // When a chunk advances to "reviewing" or "mastered",
                    // unlock the next chunk in the passage
                    boolean advanced = gotIt && !newStage.equals("learning");
                    boolean isChunk  = verse.getPassageId() != null;

                    if (advanced && isChunk && verse.getChunkIndex() != null) {
                        unlockNextChunk(verse.getPassageId(), verse.getChunkIndex());
                    }
                });

        
    }

    private void unlockNextChunk(String passageId, int currentChunkIndex) {
        if (passageId == null) return;
        int nextChunkIndex = currentChunkIndex + 1;

        // Find the next chunk in this passage
        db.collection("users").document(currentUserId)
                .collection("memory_verses")
                .whereEqualTo("passageId",  passageId)
                .whereEqualTo("chunkIndex", nextChunkIndex)
                .limit(1)
                .get()
                .addOnSuccessListener(query -> {
                    if (query.isEmpty()) return; // no next chunk — this was the last one

                    DocumentSnapshot nextDoc   = query.getDocuments().get(0);
                    String           nextStage = nextDoc.getString("stage");

                    // Only unlock if it's still locked (learning with a far future date)
                    // We detect "locked" by checking if nextReviewDate is more than
                    // 365 days away — that's our sentinel for a locked chunk
                    com.google.firebase.Timestamp nextReview =
                            nextDoc.getTimestamp("nextReviewDate");

                    boolean isLocked = nextReview != null &&
                            nextReview.toDate().getTime() - System.currentTimeMillis()
                                    > 365L * 24 * 60 * 60 * 1000;

                    if (!isLocked) return; // already unlocked, nothing to do

                    // Unlock: set nextReviewDate to tomorrow
                    Map<String, Object> unlock = new HashMap<>();
                    unlock.put("nextReviewDate", new Timestamp(addDays(new Date(), 1)));

                    db.collection("users").document(currentUserId)
                            .collection("memory_verses")
                            .document(nextDoc.getId())
                            .update(unlock)
                            .addOnSuccessListener(v -> {
                                String ref = nextDoc.getString("reference");
                                Toast.makeText(this,
                                        "🔓 Next part unlocked: " + ref,
                                        Toast.LENGTH_LONG).show();
                            });
                });
    }

    private void logPractice(String reference) {
        ActivityLog.addMemorize(currentUserId, reference);
        db.collection("users").document(currentUserId)
                .set(java.util.Collections.singletonMap("memorizedTodayDate", todayString()),
                        com.google.firebase.firestore.SetOptions.merge());
    }

    // ─── Notifications ───────────────────────────────────────────────────────

    private void notifyPartnerOfMastery(String reference) {
        if (partnerFcmToken == null) return;
        sendPush(partnerFcmToken,
                myName + " just mastered a verse!",
                "\"" + reference + "\" — keep going!");
    }

    private void sendPush(String token, String title, String body) {
        PartnerNotifier.send(token, title, body, "memorize", "", NOTIFY_URL);
    }

    // ─── Delete ──────────────────────────────────────────────────────────────

    private void deleteVerse(MemoryVerse verse) {
        if (verse.getId() == null) return;
        db.collection("users").document(currentUserId)
                .collection("memory_verses")
                .document(verse.getId())
                .delete()
                .addOnSuccessListener(unused ->
                        Toast.makeText(this, "Verse removed", Toast.LENGTH_SHORT).show());
    }

    // ─── Tap handlers ────────────────────────────────────────────────────────

    private void onVerseTapped(MemoryVerse verse) {
        if (viewingPartner) { showVerseDetailDialog(verse); return; }
        if (isDue(verse)) showQuizDialog(verse);
        else     showVerseDetailDialog(verse);
    }

    private void onVerseLongTapped(MemoryVerse verse) {
        if (viewingPartner) return;
        new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setTitle("Delete Verse?")
                .setMessage("\"" + verse.getReference() + "\" will be permanently removed.")
                .setPositiveButton("Delete", (d, w) -> deleteVerse(verse))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ─── Quiz dialog ─────────────────────────────────────────────────────────

    private void showQuizDialog(MemoryVerse verse) {
        View dv = LayoutInflater.from(this).inflate(R.layout.dialog_verse_quiz, null);

        TextView       tvRef          = dv.findViewById(R.id.tvQuizReference);
        TextView       tvProgress     = dv.findViewById(R.id.tvQuizProgress);
        TextView       tvHint         = dv.findViewById(R.id.tvQuizHint);
        TextView       tvText         = dv.findViewById(R.id.tvQuizText);
        MaterialButton btnFirstLetter = dv.findViewById(R.id.btnFirstLetter);
        MaterialButton btnReveal      = dv.findViewById(R.id.btnReveal);
        View           layoutActions  = dv.findViewById(R.id.layoutQuizActions);
        MaterialButton btnGotIt       = dv.findViewById(R.id.btnGotIt);
        MaterialButton btnTryAgain    = dv.findViewById(R.id.btnTryAgain);

        int idx = verse.getIntervalIndex() != null ? verse.getIntervalIndex() : 0;
        tvRef.setText(verse.getReference());
        if (tvProgress != null) tvProgress.setText("Day " + (idx + 1) + " of " + INTERVALS.length);

        tvText.setVisibility(View.GONE);
        layoutActions.setVisibility(View.GONE);
        if (tvHint != null) tvHint.setVisibility(View.GONE);

        if (idx >= 2 && tvHint != null) {
            tvHint.setText(buildProgressiveHint(verse.getText(), idx));
            tvHint.setVisibility(View.VISIBLE);
        }

        androidx.appcompat.app.AlertDialog dialog =
                new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_App_MaterialAlertDialog)
                        .setView(dv).setCancelable(true).create();

        if (btnFirstLetter != null) {
            btnFirstLetter.setOnClickListener(fv -> {
                if (tvHint != null) {
                    tvHint.setText(buildFirstLetterHint(verse.getText()));
                    tvHint.setVisibility(View.VISIBLE);
                }
            });
        }

        btnReveal.setOnClickListener(rv -> {
            tvText.setText(verse.getText());
            tvText.setVisibility(View.VISIBLE);
            btnReveal.setVisibility(View.GONE);
            if (btnFirstLetter != null) btnFirstLetter.setVisibility(View.GONE);
            if (tvHint != null) tvHint.setVisibility(View.GONE);
            layoutActions.setVisibility(View.VISIBLE);
        });

        btnGotIt.setOnClickListener(gv -> { markReviewed(verse, true); dialog.dismiss(); });
        btnTryAgain.setOnClickListener(tv -> {
            markReviewed(verse, false);
            dialog.dismiss();
            Toast.makeText(this, "Keep going, you'll get it!", Toast.LENGTH_SHORT).show();
        });

        dialog.show();
    }

    private void showVerseDetailDialog(MemoryVerse verse) {
        int    idx  = verse.getIntervalIndex() != null ? verse.getIntervalIndex() : 0;
        String next = isLocked(verse) ? "after the previous part advances"
                : verse.getNextReviewDate() != null
                ? android.text.format.DateFormat.getDateFormat(this)
                .format(verse.getNextReviewDate().toDate()) : "—";

        new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setTitle(verse.getReference())
                .setMessage(verse.getText()
                        + "\n\nDay " + (idx + 1) + " of " + INTERVALS.length
                        + "\nStage: " + getStageLabel(verse)
                        + "\nNext review: " + next)
                .setPositiveButton("Close", null)
                .setNeutralButton(viewingPartner ? null : "Delete",
                        viewingPartner ? null : (d, w) -> onVerseLongTapped(verse))
                .show();
    }

    // ─── Hint builders ───────────────────────────────────────────────────────

    private String buildProgressiveHint(String text, int idx) {
        if (text == null || text.isEmpty()) return "";
        String[] words = text.split(" ");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) sb.append(" ");
            boolean blank = (idx >= 4) || (idx == 3 && i % 2 == 1) || (idx == 2 && i % 3 == 2);
            sb.append(blank ? underscores(words[i].length()) : words[i]);
        }
        return sb.toString();
    }

    private String buildFirstLetterHint(String text) {
        if (text == null || text.isEmpty()) return "";
        String[] words = text.split(" ");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) sb.append(" ");
            if (!words[i].isEmpty()) sb.append(words[i].charAt(0));
        }
        return sb.toString();
    }

    private String underscores(int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.max(len, 1); i++) sb.append('_');
        return sb.toString();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String todayString() {
        return ActivityLog.today();
    }

    /** Due any time on its review day, not only after the exact minute it was scheduled. */
    static boolean isDue(MemoryVerse v) {
        if (v.getNextReviewDate() == null || "mastered".equals(v.getStage())) return false;
        Calendar tomorrow = Calendar.getInstance();
        tomorrow.add(Calendar.DAY_OF_YEAR, 1);
        tomorrow.set(Calendar.HOUR_OF_DAY, 0);
        tomorrow.set(Calendar.MINUTE, 0);
        tomorrow.set(Calendar.SECOND, 0);
        tomorrow.set(Calendar.MILLISECOND, 0);
        return v.getNextReviewDate().toDate().before(tomorrow.getTime());
    }

    /** Later parts of a passage are parked ~10 years out until the previous part advances. */
    static boolean isLocked(MemoryVerse v) {
        return v.getNextReviewDate() != null &&
                v.getNextReviewDate().toDate().getTime() - System.currentTimeMillis() > 365L * 24 * 60 * 60 * 1000;
    }

    private String getStageLabel(MemoryVerse v) {
        if (v.getStage() == null) return "Learning";
        switch (v.getStage()) {
            case "mastered":  return "Mastered";
            case "reviewing": return "Reviewing";
            default:          return "Learning";
        }
    }

    private Date addDays(Date date, int days) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        cal.add(Calendar.DAY_OF_YEAR, days);
        return cal.getTime();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (myVerseListener      != null) myVerseListener.remove();
        if (partnerVerseListener != null) partnerVerseListener.remove();
    }

    // ─── Adapter ─────────────────────────────────────────────────────────────

    static class VerseAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int   TYPE_VERSE  = 0;
        private static final int   TYPE_HEADER = 1;
        private static final int[] INTERVALS   = {1, 2, 4, 7, 30};

        private final List<Object>                              list;
        private final java.util.function.Consumer<MemoryVerse> onTap, onLong;

        VerseAdapter(List<Object> list,
                     java.util.function.Consumer<MemoryVerse> onTap,
                     java.util.function.Consumer<MemoryVerse> onLong) {
            this.list   = list;
            this.onTap  = onTap;
            this.onLong = onLong;
        }

        @Override public int getItemViewType(int pos) {
            return list.get(pos) instanceof String ? TYPE_HEADER : TYPE_VERSE;
        }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (vt == TYPE_HEADER)
                return new HeaderVH(inf.inflate(R.layout.item_section_header, parent, false));
            return new VerseVH(inf.inflate(R.layout.item_memory_verse, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
            if (holder instanceof HeaderVH) {
                String header = (String) list.get(pos);
                ((HeaderVH) holder).tvHeader.setText(
                        "NO_SHARED".equals(header)
                                ? "Partner hasn't shared any verses yet"
                                : header);
                return;
            }

            VerseVH     h = (VerseVH) holder;
            MemoryVerse v = (MemoryVerse) list.get(pos);

            h.tvReference.setText(v.getReference());
            h.tvText.setText(v.getText());

            int idx = v.getIntervalIndex() != null ? v.getIntervalIndex() : 0;
            if (h.tvProgress != null)
                h.tvProgress.setText("Day " + (idx + 1) + " / " + INTERVALS.length);

            if (h.tvShared != null)
                h.tvShared.setVisibility(v.isShared() ? View.VISIBLE : View.GONE);

            boolean isDue  = MemorizeActivity.isDue(v);
            boolean isMast = "mastered".equals(v.getStage());

            if (isMast) {
                h.tvStage.setText("Mastered");
                h.tvStage.setTextColor(0xFF2E7D32);
                h.viewStripe.setBackgroundColor(0xFF43A047);
                if (h.tvDuePill != null) h.tvDuePill.setVisibility(View.GONE);
            } else if (isDue) {
                h.tvStage.setText("Due for review");
                h.tvStage.setTextColor(0xFF6A1B9A);
                h.viewStripe.setBackgroundColor(0xFF7E57C2);
                if (h.tvDuePill != null) {
                    h.tvDuePill.setVisibility(View.VISIBLE);
                    h.tvDuePill.setText("Review now");
                }
            } else if (MemorizeActivity.isLocked(v)) {
                h.tvStage.setText("Locked 🔒");
                h.tvStage.setTextColor(0xFF9E9E9E);
                h.viewStripe.setBackgroundColor(0xFFE0E0E0);
                if (h.tvDuePill != null) h.tvDuePill.setVisibility(View.GONE);
            } else {
                h.tvStage.setText(v.getStage() != null ? capitalize(v.getStage()) : "Learning");
                h.tvStage.setTextColor(0xFF5E35B1);
                h.viewStripe.setBackgroundColor(0xFFB39DDB);
                if (h.tvDuePill != null) h.tvDuePill.setVisibility(View.GONE);
            }

            h.itemView.setOnClickListener(view -> onTap.accept(v));
            h.itemView.setOnLongClickListener(view -> { onLong.accept(v); return true; });
        }

        private String capitalize(String s) {
            return (s == null || s.isEmpty()) ? s
                    : s.substring(0, 1).toUpperCase() + s.substring(1);
        }

        @Override public int getItemCount() { return list.size(); }

        static class VerseVH extends RecyclerView.ViewHolder {
            TextView tvReference, tvText, tvStage, tvDuePill, tvProgress, tvShared;
            View     viewStripe;
            VerseVH(@NonNull View v) {
                super(v);
                tvReference = v.findViewById(R.id.tvVerseReference);
                tvText      = v.findViewById(R.id.tvVerseText);
                tvStage     = v.findViewById(R.id.tvVerseStage);
                tvDuePill   = v.findViewById(R.id.tvDuePill);
                tvProgress  = v.findViewById(R.id.tvVerseProgress);
                tvShared    = v.findViewById(R.id.tvVerseShared);
                viewStripe  = v.findViewById(R.id.viewVerseStripe);
            }
        }

        static class HeaderVH extends RecyclerView.ViewHolder {
            TextView tvHeader;
            HeaderVH(@NonNull View v) {
                super(v);
                tvHeader = v.findViewById(R.id.tvSectionHeader);
            }
        }
    }
}