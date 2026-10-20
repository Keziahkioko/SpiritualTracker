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

    /** Days before each of the five reviews: days 1, 3, 5, 6 and 7 after learning (see ProgressMath). */
    private static final int[]  INTERVALS    = ProgressMath.REVIEW_GAPS;
    /** Mastered verses come back for a refresh this often, or sooner after a "Try again". */
    private static final int    REFRESH_DAYS       = 30;
    private static final int    REFRESH_AGAIN_DAYS = 7;
    private static final String DATE_FMT     = "yyyy-MM-dd";

    // Views
    private RecyclerView                 rvVerses;
    private LinearLayout                 layoutEmpty;
    private TextView                     tvDueCount, tvMasteredCount, tvPartnerMastered;
    private TextView                     tvHeaderTitle;
    private TextView                     tvTogglePartnerLabel; // ← text label inside the FrameLayout pill
    private View                         btnTogglePartner;     // ← now View, not TextView
    private com.google.android.material.button.MaterialButton fabAddVerse;
    private View                         layoutPartnerPanel;

    // Data
    private final List<MemoryVerse> activeList        = new ArrayList<>();
    private final List<MemoryVerse> masteredList      = new ArrayList<>();
    private final List<MemoryVerse> partnerSharedList = new ArrayList<>();
    private final List<Object>      displayList       = new ArrayList<>();
    private final List<Together>    togetherList      = new ArrayList<>();

    // Firebase
    private FirebaseFirestore    db;
    private String               currentUserId;
    private String               partnerId;
    private String               myName;
    private ListenerRegistration myVerseListener;
    private ListenerRegistration partnerVerseListener;

    // State
    private boolean      viewingPartner = false;
    private VerseAdapter adapter;
    private VerseSpeaker speaker;
    /** Parts this screen has already asked to unlock, so a slow write isn't sent (or toasted) twice. */
    private final java.util.Set<String> unlocking = new java.util.HashSet<>();

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_memorize);
        SAID_DIFFERENTLY = androidx.core.content.ContextCompat.getColor(this, R.color.st_accent);
        MISSED_WORD = androidx.core.content.ContextCompat.getColor(this, R.color.st_danger);
        SCORE_COLOR = androidx.core.content.ContextCompat.getColor(this, R.color.st_accent_strong);
        SCORE_TEXT = androidx.core.content.ContextCompat.getColor(this, R.color.st_text_2);
        LearnText.theme(this);
        PartnerName.apply(findViewById(android.R.id.content));

        FirebaseAuth auth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        if (auth.getCurrentUser() == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        currentUserId = auth.getCurrentUser().getUid();
        speaker = new VerseSpeaker(this);

        rvVerses             = findViewById(R.id.rvVerses);
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
        findViewById(R.id.btnMemorizeBack).setOnClickListener(v -> finish());
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(false);
        final int headerBaseTop = headerLayout.getPaddingTop();
        View statusScrim = findViewById(R.id.memorizeStatusScrim);
        final int listBottom = rvVerses.getPaddingBottom();
        final int fabBottom = ((android.view.ViewGroup.MarginLayoutParams) fabAddVerse.getLayoutParams()).bottomMargin;
        ViewCompat.setOnApplyWindowInsetsListener(statusScrim, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.getLayoutParams().height = bars.top;
            v.requestLayout();
            rvVerses.setPadding(rvVerses.getPaddingLeft(), rvVerses.getPaddingTop(),
                    rvVerses.getPaddingRight(), listBottom + bars.bottom);
            ((android.view.ViewGroup.MarginLayoutParams) fabAddVerse.getLayoutParams()).bottomMargin = fabBottom + bars.bottom;
            fabAddVerse.requestLayout();
            return insets;
        });
        ViewCompat.setOnApplyWindowInsetsListener(headerLayout, (v, insets) -> {
            int statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            int extraPadding    = getResources()
                    .getDimensionPixelSize(R.dimen.header_top_padding);
            v.setPadding(
                    v.getPaddingLeft(),
                    statusBarHeight + extraPadding + headerBaseTop,
                    v.getPaddingRight(),
                    v.getPaddingBottom());
            return insets;
        });

        adapter = new VerseAdapter(displayList, this::onVerseTapped, this::onVerseLongTapped, this::showTogetherDialog);
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

                        }

                        db.collection("users").document(partnerId).get()
                                .addOnSuccessListener(pDoc -> {
                                    // Show partner's name on the pill label (your nickname for them if you set one)
                                    String pName = PartnerName.display(this, pDoc);
                                    if (tvTogglePartnerLabel != null) {
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
                        // (due is tallied below; myDueCount is set once the loop finishes)
                        v.setId(doc.getId());
                        if ("mastered".equals(v.getStage())) {
                            masteredList.add(v);
                            if (isDue(v)) due++; // refresh due
                        } else {
                            activeList.add(v);
                            if (isDue(v)) due++;
                        }
                    }

                    bringForward();
                    buildTogether();
                    for (Together t : togetherList) if (t.due()) due++;
                    myDueCount = due;
                    unlockReadyParts();
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
                    if (viewingPartner) {
                        rebuildPartnerDisplayList();
                        showStatsFor(true);
                    }
                });
    }

    // ─── Toggle my / partner view ────────────────────────────────────────────

    private void togglePartnerView() {
        viewingPartner = !viewingPartner;
        if (viewingPartner) {
            tvHeaderTitle.setText(PartnerName.get(this) + "'s Verses");
            if (tvTogglePartnerLabel != null) tvTogglePartnerLabel.setText("My verses");
            // Stop pulse when tapped — it served its purpose
            btnTogglePartner.clearAnimation();
            fabAddVerse.setVisibility(View.GONE);
            adapter.setPartnerMode(true);
            rebuildPartnerDisplayList();
            showStatsFor(true);
        } else {
            tvHeaderTitle.setText(getString(R.string.your_verses));
            // Restore partner name on the label
            if (tvTogglePartnerLabel != null) tvTogglePartnerLabel.setText(PartnerName.get(this) + "'s verses");
            fabAddVerse.setVisibility(View.VISIBLE);
            adapter.setPartnerMode(false);
            rebuildMyDisplayList();
            showStatsFor(false);
        }
    }

    private void rebuildMyDisplayList() {
        displayList.clear();
        java.util.LinkedHashMap<String, LockedGroup> locked = new java.util.LinkedHashMap<>();
        for (MemoryVerse v : activeList) {
            if (!isLocked(v)) { displayList.add(v); continue; }
            String key = v.groupKey() != null ? v.groupKey() : v.getReference();
            String title = v.getPassageId() != null ? v.getPassageId() : v.getReference();
            LockedGroup g = locked.get(key);
            if (g == null) locked.put(key, g = new LockedGroup(title, v));
            g.count++;
        }
        if (!togetherList.isEmpty()) {
            displayList.add("SAY IT TOGETHER");
            displayList.addAll(togetherList);
        }
        if (!locked.isEmpty()) {
            displayList.add("UP NEXT  \u00B7  unlock as you go");
            displayList.addAll(locked.values());
        }
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
                    partnerMasteredTotal = q.size();
                    if (tvPartnerMastered != null) tvPartnerMastered.setText(q.size() + " mastered");
                    if (viewingPartner) showStatsFor(true);
                })
                .addOnFailureListener(e -> {
                    if (layoutPartnerPanel != null) layoutPartnerPanel.setVisibility(View.GONE);
                });
    }

    private int myDueCount = 0;
    private int partnerMasteredTotal = 0;

    /** Header counts for whichever person's verses are on screen. */
    private void showStatsFor(boolean partner) {
        if (!partner) {
            updateStats(myDueCount, masteredList.size());
            return;
        }
        int due = 0, mastered = 0;
        for (MemoryVerse v : partnerSharedList) {
            if ("mastered".equals(v.getStage())) mastered++;
            else if (isDue(v)) due++;
        }
        // Use the partner's true total (includes verses they haven't shared) when we have it.
        updateStats(due, Math.max(mastered, partnerMasteredTotal));
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
        PartnerName.apply(v);
        TextInputEditText etRef   = v.findViewById(R.id.etVerseReference);
        TextInputEditText etText  = v.findViewById(R.id.etVerseText);
        CheckBox          cbShare = v.findViewById(R.id.cbSharedVerse);
        if (cbShare != null) cbShare.setVisibility(partnerId != null ? View.VISIBLE : View.GONE);

        androidx.appcompat.app.AlertDialog dialog =
                new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_App_MaterialAlertDialog)
                        .setView(v)
                        .setPositiveButton("Add Verse", null)
                        .setNegativeButton("Cancel", null)
                        .show();
        dialog.setCanceledOnTouchOutside(false);
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(b -> {
            String ref  = etRef.getText()  != null ? etRef.getText().toString().trim() : "";
            String text = etText.getText() != null ? etText.getText().toString().trim() : "";
            boolean shared = cbShare != null && cbShare.isChecked();
            if (ref.isEmpty() || text.isEmpty()) {
                Toast.makeText(this, "Please fill both fields", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            saveVerse(ref, text, shared, null, null, -1);
        });
    }

    /** Fix a typo in a verse or part. Its place in the schedule doesn't change. */
    private void showEditVerseDialog(MemoryVerse verse) {
        if (verse.getId() == null) return;
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_add_verse, null);
        ((TextView) v.findViewById(R.id.tvAddVerseTitle)).setText("Edit");
        ((TextView) v.findViewById(R.id.tvAddVerseSubtitle))
                .setText("Fix anything typed wrongly. Your progress stays the same.");
        TextInputEditText etRef  = v.findViewById(R.id.etVerseReference);
        TextInputEditText etText = v.findViewById(R.id.etVerseText);
        etRef.setText(verse.getReference());
        etText.setText(verse.getText());
        // A whole part is several verses; give it room instead of a small scrolling box.
        etText.setMinLines(12);
        etText.setMaxLines(16);
        v.findViewById(R.id.cbSharedVerse).setVisibility(View.GONE);
        // No share checkbox below it here, so no gap to leave above the buttons.
        ((ViewGroup.MarginLayoutParams) v.findViewById(R.id.tilVerseText).getLayoutParams()).bottomMargin = 0;
        v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), 0);

        androidx.appcompat.app.AlertDialog dialog =
                new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_App_MaterialAlertDialog)
                        .setView(v)
                        .setPositiveButton("Save", null)
                        .setNegativeButton("Cancel", null)
                        .show();
        // Kept open on an empty field instead of closing and losing the edit.
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(b -> {
            String ref  = etRef.getText()  != null ? etRef.getText().toString().trim()  : "";
            String text = etText.getText() != null ? etText.getText().toString().trim() : "";
            if (ref.isEmpty() || text.isEmpty()) {
                Toast.makeText(this, "Please fill both fields", Toast.LENGTH_SHORT).show();
                return;
            }
            Map<String, Object> updates = new HashMap<>();
            updates.put("reference", ref);
            updates.put("text", text);
            db.collection("users").document(currentUserId)
                    .collection("memory_verses").document(verse.getId())
                    .update(updates)
                    .addOnSuccessListener(x -> Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show())
                    .addOnFailureListener(e -> Toast.makeText(this, "Couldn't save. Try again.", Toast.LENGTH_SHORT).show());
            dialog.dismiss();
        });
    }

    // ─── Passage / chunk dialog ───────────────────────────────────────────────

    /**
     * Add a passage (full screen): reference, the pasted text, verses per part and sharing, with a
     * live preview of the parts it will make. Back asks before throwing away a pasted passage.
     */
    private void showAddPassageDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_add_passage, null);
        TextInputEditText etRef   = v.findViewById(R.id.etPassageReference);
        TextInputEditText etText  = v.findViewById(R.id.etPassageText);
        android.widget.CompoundButton share = v.findViewById(R.id.cbPassageShared);
        TextView          btn3    = v.findViewById(R.id.btnChunk3);
        TextView          btn4    = v.findViewById(R.id.btnChunk4);
        TextView          btn5    = v.findViewById(R.id.btnChunk5);
        MaterialButton    btnAdd  = v.findViewById(R.id.btnPassageAdd);

        if (partnerId != null) {
            v.findViewById(R.id.rowPassageShare).setVisibility(View.VISIBLE);
            ((TextView) v.findViewById(R.id.tvPassageShare)).setText("Share with " + PartnerName.get(this));
            v.findViewById(R.id.rowPassageShare).setOnClickListener(r -> share.toggle());
        }

        android.app.Dialog dialog = openScreen(v, v.findViewById(R.id.passageTopBar), v.findViewById(R.id.passageBottomBar));

        final int[] chunkSize = {3};
        android.os.Handler later = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable preview = () -> previewPassage(v, btnAdd,
                etRef.getText() != null ? etRef.getText().toString().trim() : "",
                etText.getText() != null ? etText.getText().toString() : "", chunkSize[0]);

        View.OnClickListener chunkClick = cv -> {
            chunkSize[0] = Integer.parseInt(((TextView) cv).getText().toString());
            for (TextView t : new TextView[]{btn3, btn4, btn5}) {
                boolean on = t == cv;
                t.setBackgroundResource(on ? R.drawable.chunk_btn_selected : R.drawable.chunk_btn_unselected);
                t.setTextColor(on ? 0xFFFFFFFF : androidx.core.content.ContextCompat.getColor(this, R.color.st_accent));
            }
            preview.run();
        };
        btn3.setOnClickListener(chunkClick);
        btn4.setOnClickListener(chunkClick);
        btn5.setOnClickListener(chunkClick);

        // Re-split shortly after typing or pasting stops.
        android.text.TextWatcher watcher = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence x, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence x, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                later.removeCallbacks(preview);
                later.postDelayed(preview, 300);
            }
        };
        etRef.addTextChangedListener(watcher);
        etText.addTextChangedListener(watcher);

        // The text box scrolls inside the page: while it has more to show, a drag scrolls it.
        etText.setOnTouchListener((tv, e) -> {
            if (tv.canScrollVertically(1) || tv.canScrollVertically(-1)) {
                tv.getParent().requestDisallowInterceptTouchEvent(
                        e.getActionMasked() != android.view.MotionEvent.ACTION_UP
                                && e.getActionMasked() != android.view.MotionEvent.ACTION_CANCEL);
            }
            return false;
        });

        Runnable leave = () -> {
            boolean typed = etText.getText() != null && !etText.getText().toString().trim().isEmpty();
            if (!typed) { dialog.dismiss(); return; }
            new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_App_MaterialAlertDialog)
                    .setMessage("Leave without adding? The pasted passage will be lost.")
                    .setPositiveButton("Leave", (x, y) -> dialog.dismiss())
                    .setNegativeButton("Keep editing", null)
                    .show();
        };
        v.findViewById(R.id.btnPassageBack).setOnClickListener(b -> leave.run());
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode != android.view.KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == android.view.KeyEvent.ACTION_UP) leave.run();
            return true;
        });

        btnAdd.setOnClickListener(b -> {
            String baseRef  = etRef.getText()  != null ? etRef.getText().toString().trim()  : "";
            String fullText = etText.getText() != null ? etText.getText().toString().trim() : "";
            if (baseRef.isEmpty() || fullText.isEmpty()) return;
            dialog.dismiss();
            splitAndSavePassage(baseRef, fullText, chunkSize[0], share.isChecked());
        });
        dialog.show();
        etRef.requestFocus();
    }

    /** The parts a passage will make, as the person types or pastes; the Add button follows. */
    private void previewPassage(View v, MaterialButton btnAdd, String ref, String text, int size) {
        List<List<String>> parts = new ArrayList<>();
        List<String> verses = text.trim().isEmpty() ? new ArrayList<>() : PassageSplit.verses(text);
        for (int i = 0; i < verses.size(); i += size) {
            parts.add(verses.subList(i, Math.min(verses.size(), i + size)));
        }
        View card = v.findViewById(R.id.cardPassagePreview);
        card.setVisibility(parts.isEmpty() ? View.GONE : View.VISIBLE);
        btnAdd.setEnabled(!parts.isEmpty() && !ref.isEmpty());
        btnAdd.setText(parts.isEmpty() ? "Add passage"
                : "Add " + (parts.size() == 1 ? "1 part" : parts.size() + " parts"));
        if (parts.isEmpty()) return;

        ((TextView) v.findViewById(R.id.tvPassagePreviewTitle)).setText(
                verses.size() + (verses.size() == 1 ? " verse" : " verses") + "  \u2192  "
                        + parts.size() + (parts.size() == 1 ? " part" : " parts"));
        LinearLayout list = v.findViewById(R.id.listPassageParts);
        list.removeAllViews();
        java.util.regex.Pattern number = java.util.regex.Pattern.compile("^(\\d+)\\s");
        int shown = Math.min(parts.size(), 8);
        for (int i = 0; i < shown; i++) {
            List<String> p = parts.get(i);
            java.util.regex.Matcher first = number.matcher(p.get(0)), last = number.matcher(p.get(p.size() - 1));
            String range = first.find() && last.find()
                    ? (first.group(1).equals(last.group(1)) ? "verse " + first.group(1) : "verses " + first.group(1) + "\u2013" + last.group(1))
                    : (p.size() == 1 ? "1 line" : p.size() + " lines");
            String start = p.get(0).replaceFirst("^\\d+\\s+", "");
            if (start.length() > 42) start = start.substring(0, 40).trim() + "\u2026";

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            TextView num = new TextView(this);
            num.setText(String.valueOf(i + 1));
            num.setGravity(android.view.Gravity.CENTER);
            num.setTextColor(0xFFFFFFFF);
            num.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
            num.setTypeface(null, android.graphics.Typeface.BOLD);
            android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
            dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dot.setColor(0xFF9575CD);
            num.setBackground(dot);
            row.addView(num, new LinearLayout.LayoutParams(dp(24), dp(24)));
            TextView label = new TextView(this);
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
            sb.append("Part ").append(String.valueOf(i + 1)).append(" \u00b7 ").append(range);
            sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, sb.length(), 0);
            sb.append("\n").append(start);
            label.setText(sb);
            label.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.st_text));
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginStart(dp(12));
            row.addView(label, lp);
            list.addView(row);
        }
        if (parts.size() > shown) {
            TextView more = new TextView(this);
            more.setText("+ " + (parts.size() - shown) + " more");
            more.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.st_accent));
            more.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            more.setPadding(dp(36), dp(4), 0, 0);
            list.addView(more);
        }
    }

    private void splitAndSavePassage(String baseRef, String fullText, int chunkSize, boolean shared) {
        // Step 1: one verse per line, however the passage was copied (see PassageSplit)
        List<String> verses = PassageSplit.verses(fullText);

        if (verses.isEmpty()) {
            Toast.makeText(this, "Could not read the passage", Toast.LENGTH_SHORT).show();
            return;
        }

        // Step 2: Group verses into chunks of the selected size
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

        // Its own id, so another passage typed with the same name can't unlock these parts.
        String passageKey = java.util.UUID.randomUUID().toString();
        // All parts in one go, so a dropped connection can't leave a passage with a part missing.
        com.google.firebase.firestore.WriteBatch batch = db.batch();
        com.google.firebase.firestore.CollectionReference verses_ =
                db.collection("users").document(currentUserId).collection("memory_verses");
        for (int i = 0; i < chunks.size(); i++) {
            String chunkRef = baseRef + " (Part " + (i + 1) + " of " + chunks.size() + ")";
            batch.set(verses_.document(), verseData(chunkRef, chunks.get(i), shared, baseRef, passageKey, i));
        }
        int parts = chunks.size();
        batch.commit()
                .addOnSuccessListener(x -> Toast.makeText(this,
                        (parts == 1 ? "1 part" : parts + " parts") + " added for \"" + baseRef + "\"",
                        Toast.LENGTH_LONG).show())
                .addOnFailureListener(e -> Toast.makeText(this,
                        "Couldn't add the passage. Check your connection and try again.", Toast.LENGTH_LONG).show());
    }

    // ─── Save ────────────────────────────────────────────────────────────────

    private void saveVerse(String reference, String text, boolean shared,
                           String passageId, String passageKey, int chunkIndex) {
        db.collection("users").document(currentUserId)
                .collection("memory_verses")
                .add(verseData(reference, text, shared, passageId, passageKey, chunkIndex))
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Failed to save verse", Toast.LENGTH_SHORT).show());
    }

    private Map<String, Object> verseData(String reference, String text, boolean shared,
                                          String passageId, String passageKey, int chunkIndex) {
        String today  = todayString();

        // Part 1 (or a single verse) is ready to learn now; later parts are locked
        // (parked 10 years out) until the part before them advances.
        Date dueDate = (chunkIndex <= 0)
                ? new Date()
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
        data.put("needsLearning",    true);
        if (passageId != null) {
            data.put("passageId",  passageId);
            data.put("passageKey", passageKey);
            data.put("chunkIndex", chunkIndex);
        }
        return data;
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

        // Only the first review of the day moves a part up or down; extra practice just counts.
        if (sameDay) {
            Toast.makeText(this,
                    gotIt ? "Great practice! Come back tomorrow to advance 📖"
                          : "Practice counted. Your schedule stays the same.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if ("mastered".equals(verse.getStage())) {
            markRefreshed(verse, gotIt, today);
            return;
        }
        if (gotIt) {
            // A Got it on the fifth review masters it; it then stays on the last index.
            boolean mastered = currentIdx + 1 >= INTERVALS.length;
            nextIdx  = Math.min(currentIdx + 1, INTERVALS.length - 1);
            newStage = mastered        ? "mastered"
                    : (nextIdx >= 2)   ? "reviewing"
                    : "learning";
        } else {
            nextIdx  = Math.max(0, currentIdx - 1);
            newStage = "learning";
        }

        Map<String, Object> updates = new HashMap<>();
        updates.put("intervalIndex",    nextIdx);
        updates.put("stage",            newStage);
        updates.put("nextReviewDate",   new Timestamp(addDays(new Date(),
                "mastered".equals(newStage) ? REFRESH_DAYS : INTERVALS[nextIdx])));
        updates.put("lastReviewedDate", today);
        if ("mastered".equals(newStage)) updates.put("refreshes", 0);

        db.collection("users").document(currentUserId)
                .collection("memory_verses")
                .document(verse.getId())
                .update(updates)
                .addOnSuccessListener(unused -> {
                    if ("mastered".equals(newStage)) {
                        Toast.makeText(this, "🎉 Verse mastered!", Toast.LENGTH_LONG).show();
                        notifyPartnerOfMastery(verse.shownReference());
                    }
                    // The list listener sees the new stage and unlocks the next part if it's ready.
                });
    }

    /**
     * A mastered verse's refresh. It stays mastered either way: remembered, it comes back in
     * 30 days; not quite, it comes back in a week for another go.
     */
    private void markRefreshed(MemoryVerse verse, boolean gotIt, String today) {
        int days = gotIt ? REFRESH_DAYS : REFRESH_AGAIN_DAYS;
        Map<String, Object> updates = new HashMap<>();
        updates.put("nextReviewDate",   new Timestamp(addDays(new Date(), days)));
        updates.put("lastReviewedDate", today);
        // Always set, so a verse mastered before refreshes existed stops counting as due straight away.
        int done = verse.getRefreshes() != null ? verse.getRefreshes() : 0;
        updates.put("refreshes", gotIt ? done + 1 : done);
        db.collection("users").document(currentUserId)
                .collection("memory_verses").document(verse.getId())
                .update(updates)
                .addOnSuccessListener(x -> Toast.makeText(this, gotIt
                        ? "Still there! Next refresh in " + REFRESH_DAYS + " days."
                        : "No problem. It'll come back in a week for another go.", Toast.LENGTH_LONG).show());
    }

    /**
     * Opens the first locked part of each passage once the part just before it has reached
     * "Reviewing" (or Mastered), the same rule as always. Checked every time the list changes,
     * so a part whose earlier part was deleted (nothing left in front of it) opens too
     * instead of staying locked for good.
     */
    private void unlockReadyParts() {
        Map<String, List<MemoryVerse>> passages = new HashMap<>();
        List<MemoryVerse> all = new ArrayList<>(activeList);
        all.addAll(masteredList);
        for (MemoryVerse v : all) {
            String key = v.groupKey();
            if (key == null || v.getId() == null) continue;
            List<MemoryVerse> parts = passages.get(key);
            if (parts == null) passages.put(key, parts = new ArrayList<>());
            parts.add(v);
        }
        for (List<MemoryVerse> parts : passages.values()) {
            java.util.Collections.sort(parts, (a, b) -> Integer.compare(a.getChunkIndex(), b.getChunkIndex()));
            MemoryVerse before = null;
            for (MemoryVerse part : parts) {
                if (!isLocked(part)) { before = part; continue; }
                boolean ready = before == null
                        || "reviewing".equals(before.getStage()) || "mastered".equals(before.getStage());
                if (ready && unlocking.add(part.getId())) unlock(part);
                break; // only the first locked part of a passage can be next
            }
        }
    }

    /** Verses already asked about this session, so a write in flight isn't sent twice. */
    private final java.util.Set<String> broughtForward = new java.util.HashSet<>();

    /**
     * Reviews used to be up to a week apart; a verse still waiting longer than the current
     * schedule allows is brought forward to its new day (or today, if that has passed).
     */
    private void bringForward() {
        for (MemoryVerse v : activeList) {
            if (v.getId() == null || v.getNextReviewDate() == null || isLocked(v) || needsLearning(v)) continue;
            int idx = v.getIntervalIndex() != null ? v.getIntervalIndex() : 0;
            Date earlier = ProgressMath.pulledForward(v.getNextReviewDate().toDate(), v.getLastReviewedDate(), idx);
            if (earlier == null || !broughtForward.add(v.getId())) continue;
            db.collection("users").document(currentUserId)
                    .collection("memory_verses").document(v.getId())
                    .update("nextReviewDate", new Timestamp(earlier));
        }
    }

    private void unlock(MemoryVerse part) {
        // Ready to learn straight away; its first test comes the day after it's learned.
        Map<String, Object> unlock = new HashMap<>();
        unlock.put("nextReviewDate", new Timestamp(new Date()));
        unlock.put("needsLearning", true);
        db.collection("users").document(currentUserId)
                .collection("memory_verses")
                .document(part.getId())
                .update(unlock)
                .addOnSuccessListener(v -> Toast.makeText(this,
                        "🔓 Next part unlocked: " + part.shownReference(), Toast.LENGTH_LONG).show())
                .addOnFailureListener(e -> unlocking.remove(part.getId()));
    }

    private void logPractice(String reference) {
        ActivityLog.addMemorize(currentUserId, reference);
        db.collection("users").document(currentUserId)
                .set(java.util.Collections.singletonMap("memorizedTodayDate", todayString()),
                        com.google.firebase.firestore.SetOptions.merge());
    }

    // ─── Notifications ───────────────────────────────────────────────────────

    private void notifyPartnerOfMastery(String reference) {
        // Debug builds: a throwaway "TEST" passage used for testing on the phone stays quiet.
        if (BuildConfig.DEBUG && reference != null && reference.startsWith("TEST")) return;
        PartnerNotifier.notifyPartner(partnerId,
                myName + " just mastered a verse!",
                "\"" + reference + "\" — keep going!", "memorize", "");
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
        if (needsLearning(verse)) showLearnDialog(verse, false);
        else if (isDue(verse))    showQuizDialog(verse);
        else                      showVerseDetailDialog(verse);
    }

    private void onVerseLongTapped(MemoryVerse verse) {
        if (viewingPartner) return;
        new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setTitle("Delete Verse?")
                .setMessage("\"" + verse.shownReference() + "\" will be permanently removed.")
                .setPositiveButton("Delete", (d, w) -> deleteVerse(verse))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ─── Quiz dialog ─────────────────────────────────────────────────────────

    private void showQuizDialog(MemoryVerse verse) {
        int idx = verse.getIntervalIndex() != null ? verse.getIntervalIndex() : 0;
        boolean refresh = "mastered".equals(verse.getStage());
        // Day 3 onwards gives a hint by itself; a mastered verse's refresh doesn't.
        int autoHint = refresh || idx < 2 ? -1
                : idx >= 4 ? LearnText.ALL_HIDDEN : idx == 3 ? LearnText.EVERY_OTHER : LearnText.EVERY_THIRD;
        openRecallWindow(verse.shownReference(),
                refresh ? "Refresh" : "Day " + (idx + 1) + " of " + INTERVALS.length,
                null, leadInFor(verse), verse.getReference(), verse.getText(), autoHint,
                () -> markReviewed(verse, true),
                () -> {
                    markReviewed(verse, false);
                    if (!refresh) Toast.makeText(this, "Keep going, you'll get it!", Toast.LENGTH_SHORT).show();
                },
                idx == 0 && !refresh ? () -> showLearnDialog(verse, false) : null);
    }

    /**
     * The recall screen (full screen, over the list): hints (rounded gaps or letter tiles, tap one
     * to peek), Reveal, then Listen and Got it / Try again. Peeks are counted and shown after
     * Reveal; they don't affect the marking. {@code autoHint} is a LearnText mode shown straight
     * away, or -1.
     */
    private void openRecallWindow(String title, String pill, String prompt, CharSequence leadIn,
                                  String listenRef, String text, int autoHint,
                                  Runnable onGotIt, Runnable onTryAgain, Runnable learnFirst) {
        View dv = LayoutInflater.from(this).inflate(R.layout.dialog_verse_quiz, null);
        TextView       tvHint         = dv.findViewById(R.id.tvQuizHint);
        CappedScrollView hintBox      = dv.findViewById(R.id.hintScroll);
        TextView       tvText         = dv.findViewById(R.id.tvQuizText);
        MaterialButton btnFirstLetter = dv.findViewById(R.id.btnFirstLetter);
        MaterialButton btnReveal      = dv.findViewById(R.id.btnReveal);
        MaterialButton btnLearnFirst  = dv.findViewById(R.id.btnLearnFirst);
        View           layoutActions  = dv.findViewById(R.id.layoutQuizActions);

        ((TextView) dv.findViewById(R.id.tvQuizReference)).setText(withPartChip(title, dv.findViewById(R.id.tvQuizPart)));
        ((TextView) dv.findViewById(R.id.tvQuizProgress)).setText(pill);
        if (prompt != null) ((TextView) dv.findViewById(R.id.tvQuizPrompt)).setText(prompt);
        showLeadIn(dv.findViewById(R.id.tvQuizLeadIn), leadIn);

        LearnText hint = new LearnText(text);
        int[] hintMode = {autoHint};
        Runnable[] showHint = new Runnable[1];
        showHint[0] = () -> tvHint.setText(hint.render(hintMode[0], showHint[0]));
        tvHint.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        tvHint.setHighlightColor(android.graphics.Color.TRANSPARENT);
        View intro = dv.findViewById(R.id.cardRecallIntro);
        if (autoHint >= 0) {
            showHint[0].run();
            hintBox.setVisibility(View.VISIBLE);
            intro.setVisibility(View.GONE);
        }

        android.app.Dialog dialog = openScreen(dv, dv.findViewById(R.id.reviewTopBar), dv.findViewById(R.id.reviewBottomBar));

        if (learnFirst != null) {
            btnLearnFirst.setVisibility(View.VISIBLE);
            btnLearnFirst.setOnClickListener(lv -> {
                dialog.dismiss();
                learnFirst.run();
            });
        }
        btnFirstLetter.setOnClickListener(fv -> {
            hintMode[0] = LearnText.FIRST_LETTERS;
            showHint[0].run();
            hintBox.setVisibility(View.VISIBLE);
            intro.setVisibility(View.GONE);
            btnFirstLetter.setVisibility(View.GONE);
        });
        Runnable showAnswer = () -> {
            intro.setVisibility(View.GONE);
            ((TextView) dv.findViewById(R.id.tvQuizPrompt)).setText("How did you do?");
            tvText.setText(text);
            tvText.setVisibility(View.VISIBLE);
            btnReveal.setVisibility(View.GONE);
            dv.findViewById(R.id.rowHints).setVisibility(View.GONE);
            btnLearnFirst.setVisibility(View.GONE);
            hintBox.setVisibility(View.GONE);
            int peeked = hint.peeked();
            if (peeked > 0) {
                TextView tvPeeks = dv.findViewById(R.id.tvQuizPeeks);
                int total = hint.wordCount();
                // Just information for marking yourself honestly; it doesn't change the result.
                tvPeeks.setText("You peeked at " + peeked + " of " + total + " words"
                        + (peeked * 2 > total ? ", more than half." : "."));
                tvPeeks.setVisibility(View.VISIBLE);
            }
            layoutActions.setVisibility(View.VISIBLE);
            View listen = dv.findViewById(R.id.quizListen);
            speaker.bind(listen, listenRef, text);
            listen.setVisibility(View.VISIBLE);
        };
        btnReveal.setOnClickListener(rv -> showAnswer.run());

        // Type it out: typed (or said with the keyboard's mic), then checked word by word, and
        // for meaning by the relay. Like the peek count, it only informs your own marking.
        MaterialButton    btnTypeIt = dv.findViewById(R.id.btnTypeIt);
        View              tilTypeIt = dv.findViewById(R.id.tilTypeIt);
        TextInputEditText etTypeIt  = dv.findViewById(R.id.etTypeIt);
        MaterialButton    btnCheck  = dv.findViewById(R.id.btnCheck);
        TextView          tvSummary = dv.findViewById(R.id.tvCheckSummary);
        android.view.inputmethod.InputMethodManager keyboard =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        // The box scrolls inside a scrolling window, which would otherwise take every drag:
        // while there's more of a long recitation to see, the drag scrolls the box itself.
        etTypeIt.setOnTouchListener((v, e) -> {
            if (v.canScrollVertically(1) || v.canScrollVertically(-1)) {
                v.getParent().requestDisallowInterceptTouchEvent(
                        e.getActionMasked() != android.view.MotionEvent.ACTION_UP
                                && e.getActionMasked() != android.view.MotionEvent.ACTION_CANCEL);
            }
            return false;
        });
        btnTypeIt.setOnClickListener(b -> {
            // While typing, the bottom bar holds just Check.
            dv.findViewById(R.id.rowHints).setVisibility(View.GONE);
            btnReveal.setVisibility(View.GONE);
            intro.setVisibility(View.GONE);
            btnLearnFirst.setVisibility(View.GONE);
            tilTypeIt.setVisibility(View.VISIBLE);
            btnCheck.setVisibility(View.VISIBLE);
            etTypeIt.requestFocus();
            keyboard.showSoftInput(etTypeIt, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
        // The typing box fills the page down to the bottom bar (with or without the keyboard),
        // so a long recitation has room; past that, the page scrolls.
        View scroll = dv.findViewById(R.id.reviewScroll);
        scroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (tilTypeIt.getVisibility() != View.VISIBLE) return;
            // First letters showing above a long passage: keep them to about a third of the page
            // (they scroll in their own box), so the typing box still gets most of the room.
            if (hintBox.getVisibility() == View.VISIBLE) {
                int cap = Math.max(dp(120), Math.round(v.getHeight() * 0.32f));
                hintBox.post(() -> hintBox.setMaxHeight(cap));
            }
            // The typing box takes exactly the rest of the page and scrolls inside itself, so the
            // page stays put: the title and any first letters stay in view however much is typed.
            int room = v.getHeight() - tilTypeIt.getTop() - etTypeIt.getTop() - dp(84);
            int h = Math.max(dp(140), room);
            if (Math.abs(etTypeIt.getMinimumHeight() - h) > 2 || etTypeIt.getMaxHeight() != h) {
                etTypeIt.setMinHeight(h);
                etTypeIt.setMaxHeight(h);
                etTypeIt.post(() -> {
                    etTypeIt.requestLayout();
                    ((androidx.core.widget.NestedScrollView) v).scrollTo(0, 0);
                });
            }
        });
        // Back (the arrow or the phone's): typing takes a while, so ask before throwing it away.
        Runnable leave = () -> {
            boolean typing = tilTypeIt.getVisibility() == View.VISIBLE && etTypeIt.getText() != null
                    && !etTypeIt.getText().toString().trim().isEmpty();
            if (!typing) { dialog.dismiss(); return; }
            new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_App_MaterialAlertDialog)
                    .setMessage("Leave without checking? What you typed will be lost.")
                    .setPositiveButton("Leave", (x, y) -> dialog.dismiss())
                    .setNegativeButton("Keep typing", null)
                    .show();
        };
        dv.findViewById(R.id.btnReviewBack).setOnClickListener(v -> leave.run());
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode != android.view.KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == android.view.KeyEvent.ACTION_UP) leave.run();
            return true;
        });
        btnCheck.setOnClickListener(b -> {
            String typed = etTypeIt.getText() != null ? etTypeIt.getText().toString().trim() : "";
            if (typed.isEmpty()) {
                Toast.makeText(this, "Type what you remember first", Toast.LENGTH_SHORT).show();
                return;
            }
            keyboard.hideSoftInputFromWindow(etTypeIt.getWindowToken(), 0);
            TypeCheck check = new TypeCheck(text, typed);
            showAnswer.run();
            tilTypeIt.setVisibility(View.GONE);
            btnCheck.setVisibility(View.GONE);
            tvText.setText(renderCheck(check, null));
            tvSummary.setVisibility(View.VISIBLE);
            // The result is at the top of the page.
            ((androidx.core.widget.NestedScrollView) dv.findViewById(R.id.reviewScroll)).post(() ->
                    ((androidx.core.widget.NestedScrollView) dv.findViewById(R.id.reviewScroll)).smoothScrollTo(0, 0));
            if (check.exact == check.total()) {
                tvSummary.setText(checkSummary(check, null, false));
                return;
            }
            tvSummary.setText(checkSummary(check, null, false));
            askMeaning(check, typed, meaning -> {
                if (!dialog.isShowing()) return;
                tvText.setText(renderCheck(check, meaning));
                tvSummary.setText(checkSummary(check, meaning, true));
            });
        });
        dialog.setOnDismissListener(d -> speaker.stop());

        View gotIt = dv.findViewById(R.id.btnGotIt);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            // A purple glow under the glossy button instead of a grey shadow.
            gotIt.setOutlineSpotShadowColor(0xFF8E44C9);
            gotIt.setOutlineAmbientShadowColor(0xFF8E44C9);
        }
        // Got it: saved straight away, then a burst of sparkles before the screen closes.
        gotIt.setOnClickListener(gv -> {
            gv.setEnabled(false);
            dv.findViewById(R.id.btnTryAgain).setEnabled(false);
            onGotIt.run();
            SparkleBurst.play(dv, gv, dialog::dismiss);
        });
        dv.findViewById(R.id.btnTryAgain).setOnClickListener(tv -> { onTryAgain.run(); dialog.dismiss(); });
        dialog.show();
    }

    /**
     * "Exodus 15:1-3 (Part 1 of 7)": returns the reference for the title and shows the part in
     * {@code chip}; a reference without a part leaves the chip hidden.
     */
    private static String withPartChip(String title, TextView chip) {
        java.util.regex.Matcher part = java.util.regex.Pattern.compile("\\s*\\((Part \\d+ of \\d+)\\)\\s*$").matcher(title);
        if (!part.find()) return title;
        chip.setText(part.group(1));
        chip.setVisibility(View.VISIBLE);
        return title.substring(0, part.start());
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /**
     * Shows {@code content} as a full screen over this one (review, learn, add passage, details),
     * drawn edge to edge: the top bar is padded for the status bar, the bottom bar for the
     * navigation bar or, while typing, the keyboard.
     */
    private android.app.Dialog openScreen(View content, View topBar, View bottomBar) {
        android.app.Dialog dialog = new android.app.Dialog(this, R.style.Theme_SpiritualTracker_Screen);
        dialog.setContentView(content);
        SpringPress.onAllButtons(content); // every button sinks and springs back, with a light tap
        android.view.Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false);
            w.setStatusBarColor(android.graphics.Color.TRANSPARENT);
            w.setNavigationBarColor(androidx.core.content.ContextCompat.getColor(this, R.color.st_surface));
            androidx.core.view.WindowInsetsControllerCompat bars =
                    androidx.core.view.WindowCompat.getInsetsController(w, w.getDecorView());
            boolean light = !Appearance.isDark(this);
            bars.setAppearanceLightStatusBars(light);
            bars.setAppearanceLightNavigationBars(light);
        }
        int top = topBar.getPaddingTop(), bottom = bottomBar.getPaddingBottom();
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            androidx.core.graphics.Insets sys = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            androidx.core.graphics.Insets ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime());
            topBar.setPadding(topBar.getPaddingLeft(), top + sys.top, topBar.getPaddingRight(), topBar.getPaddingBottom());
            bottomBar.setPadding(bottomBar.getPaddingLeft(), bottomBar.getPaddingTop(), bottomBar.getPaddingRight(),
                    bottom + Math.max(sys.bottom, ime.bottom));
            return androidx.core.view.WindowInsetsCompat.CONSUMED;
        });
        return dialog;
    }

    // ─── Type it out ─────────────────────────────────────────────────────────

    /** Set from the palette in onCreate, so they suit Light or Dark. */
    private static int SAID_DIFFERENTLY = 0xFF7E57C2;
    private static int MISSED_WORD      = 0xFFC0392B;
    private static int SCORE_COLOR = 0xFF4A2C8F, SCORE_TEXT = 0xFF4A4458;

    /** The verse with every word that wasn't typed exactly picked out: purple said differently, red missed. */
    private static CharSequence renderCheck(TypeCheck check, Map<Integer, String[]> meaning) {
        android.text.SpannableStringBuilder out = new android.text.SpannableStringBuilder();
        for (int i = 0; i < check.pieces.size(); i++) {
            TypeCheck.Piece p = check.pieces.get(i);
            if (i > 0) out.append(p.newLine ? "\n" : p.glued ? "" : " ");
            out.append(p.lead);
            if (p.word) {
                int start = out.length();
                out.append(p.core);
                if (p.status != TypeCheck.EXACT) {
                    // A verse whose meaning changed shows its different words in red too.
                    boolean changed = meaning != null && meaning.get(p.line) != null
                            && !"same".equals(meaning.get(p.line)[0]);
                    int color = p.status == TypeCheck.MISSED || changed ? MISSED_WORD : SAID_DIFFERENTLY;
                    out.setSpan(new android.text.style.ForegroundColorSpan(color), start, out.length(),
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, out.length(),
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                out.append(p.trail);
            }
        }
        return out;
    }

    /**
     * Score, then a suggestion: word for word, or (when the relay could check) whether the
     * meaning held in each verse. {@code asked} is false while the meaning check is on its way.
     */
    private static CharSequence checkSummary(TypeCheck check, Map<Integer, String[]> meaning, boolean asked) {
        int total = check.total();
        int pct = total == 0 ? 0 : Math.round(check.exact * 100f / total);
        android.text.SpannableStringBuilder out = new android.text.SpannableStringBuilder();
        out.append(pct + "%");
        out.setSpan(new android.text.style.RelativeSizeSpan(2f), 0, out.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new android.text.style.ForegroundColorSpan(SCORE_COLOR), 0, out.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int words = out.length();
        out.append("  " + check.exact + " of " + total + " words exactly");
        out.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, out.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new android.text.style.ForegroundColorSpan(SCORE_TEXT), words, out.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (check.different > 0) colored(out.append("\n"), check.different + " said differently", SAID_DIFFERENTLY);
        if (check.missed > 0) colored(out.append(check.different > 0 ? " · " : "\n"), check.missed + " missed", MISSED_WORD);

        if (check.exact == total) return out.append("\nWord for word. That's a Got it!");
        if (!asked) return out.append("\nChecking the meaning…");
        if (meaning == null) {
            int close = Math.round((check.exact + check.different) * 100f / total);
            out.append(pct >= 90 ? "\nThat's a Got it!"
                    : close >= 90 ? "\nClose! " + check.different + " words were said differently. Your call."
                    : NOT_YET);
            return out.append("\n(The meaning couldn't be checked just now, so only the words were compared.)");
        }
        StringBuilder notes = new StringBuilder();
        for (int line = 0; line < check.lines.size(); line++) {
            String[] m = meaning.get(line);
            if (check.notExactOn(line) == 0 || m == null || "same".equals(m[0])) continue;
            // Verses missing one after another make one line ("Verses 6–21: not said"), so a
            // long passage recited only in part doesn't push the buttons off the screen.
            int end = line;
            if ("missing".equals(m[0])) {
                while (end + 1 < check.lines.size() && meaning.get(end + 1) != null
                        && "missing".equals(meaning.get(end + 1)[0]) && check.notExactOn(end + 1) > 0) end++;
            }
            String first = check.verseNumber(line), last = check.verseNumber(end);
            String label = end > line
                    ? (first != null && last != null ? "Verses " + first + "–" + last : "Lines " + (line + 1) + "–" + (end + 1))
                    : (first != null ? "Verse " + first : "Line " + (line + 1));
            notes.append("\n• ").append(label).append(": ")
                 .append(end > line ? "not said" : !m[1].isEmpty() ? m[1]
                         : "missing".equals(m[0]) ? "mostly missing" : "the meaning changed");
            line = end;
        }
        if (notes.length() == 0) {
            return out.append(pct >= 90 ? "\nSame meaning. That's a Got it!"
                    : "\nSame meaning, in your own words. That's a Got it!");
        }
        // 90% word for word is a Got it even if a verse was flagged; the notes are then just things to watch.
        if (pct >= 90) return out.append("\nThat's a Got it! Small things to watch:").append(notes);
        return out.append(NOT_YET).append(notes);
    }

    /** Points at the button, so it isn't read as "type it again". */
    private static final String NOT_YET = "\nNot quite yet. Tap Try again and it comes back for more practice.";

    private static void colored(android.text.SpannableStringBuilder out, String text, int color) {
        int start = out.length();
        out.append(text);
        out.setSpan(new android.text.style.ForegroundColorSpan(color), start, out.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    interface MeaningAnswer { void answer(Map<Integer, String[]> byLine); }

    /** Asks the relay whether each verse kept its meaning; null if it couldn't be checked. */
    private void askMeaning(TypeCheck check, String typed, MeaningAnswer answer) {
        org.json.JSONObject body = new org.json.JSONObject();
        try {
            org.json.JSONArray verses = new org.json.JSONArray();
            for (int i = 0; i < check.lines.size(); i++) {
                verses.put(new org.json.JSONObject().put("n", i + 1).put("text", check.lines.get(i)));
            }
            body.put("verses", verses).put("typed", typed);
        } catch (org.json.JSONException e) {
            answer.answer(null);
            return;
        }
        Relay.ask("/api/check-meaning", body, reply -> {
            org.json.JSONArray verses = reply != null ? reply.optJSONArray("verses") : null;
            if (verses == null) { answer.answer(null); return; }
            Map<Integer, String[]> byLine = new HashMap<>();
            for (int i = 0; i < verses.length(); i++) {
                org.json.JSONObject v = verses.optJSONObject(i);
                if (v == null || "unknown".equals(v.optString("verdict"))) continue;
                byLine.put(v.optInt("n") - 1, new String[]{v.optString("verdict"), v.optString("note")});
            }
            answer.answer(byLine.isEmpty() ? null : byLine);
        });
    }

    // ─── Joining parts up ────────────────────────────────────────────────────

    /**
     * The last verse of the part before this one, shown as a lead-in so a part is practised
     * from where you'd really be when reciting. Null for Part 1 and single verses.
     */
    private CharSequence leadInFor(MemoryVerse verse) {
        String key = verse.groupKey();
        if (key == null) return null;
        MemoryVerse before = null;
        for (MemoryVerse v : myVerses()) {
            if (!key.equals(v.groupKey()) || v.getChunkIndex() >= verse.getChunkIndex()) continue;
            if (before == null || v.getChunkIndex() > before.getChunkIndex()) before = v;
        }
        if (before == null || before.getText() == null || before.getText().trim().isEmpty()) return null;
        String[] lines = before.getText().trim().split("\n");
        String last = lines[lines.length - 1].trim();
        String[] words = last.split("\\s+");
        if (words.length > 30) {
            last = "…" + String.join(" ", java.util.Arrays.copyOfRange(words, words.length - 30, words.length));
        }
        android.text.SpannableStringBuilder out = new android.text.SpannableStringBuilder();
        out.append("Leading in from Part " + (before.getChunkIndex() + 1));
        out.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, out.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new android.text.style.ForegroundColorSpan(0xFF7E57C2), 0, out.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append("\n").append(last);
        return out;
    }

    private static void showLeadIn(TextView view, CharSequence leadIn) {
        view.setVisibility(leadIn == null ? View.GONE : View.VISIBLE);
        if (leadIn != null) view.setText(leadIn);
    }

    private List<MemoryVerse> myVerses() {
        List<MemoryVerse> all = new ArrayList<>(activeList);
        all.addAll(masteredList);
        return all;
    }

    /**
     * For each passage, the run of parts from the first one that have all reached Reviewing or
     * Mastered. Two or more of them can be practised together; the run grows as parts are learned.
     */
    private void buildTogether() {
        togetherList.clear();
        Map<String, List<MemoryVerse>> passages = new java.util.LinkedHashMap<>();
        for (MemoryVerse v : myVerses()) {
            String key = v.groupKey();
            if (key == null || v.getId() == null) continue;
            List<MemoryVerse> parts = passages.get(key);
            if (parts == null) passages.put(key, parts = new ArrayList<>());
            parts.add(v);
        }
        for (List<MemoryVerse> parts : passages.values()) {
            java.util.Collections.sort(parts, (a, b) -> Integer.compare(a.getChunkIndex(), b.getChunkIndex()));
            List<MemoryVerse> run = new ArrayList<>();
            for (MemoryVerse part : parts) {
                boolean learned = !isLocked(part) && !needsLearning(part)
                        && ("reviewing".equals(part.getStage()) || "mastered".equals(part.getStage()));
                if (!learned) break;
                run.add(part);
            }
            if (run.size() >= 2) togetherList.add(new Together(run));
        }
    }

    private void showTogetherDialog(Together t) {
        String text = t.text();
        openRecallWindow(VerseSpeaker.referenceForSpeech(t.title, text), "Together",
                "Can you say " + t.label().replace("Parts", "parts") + " as one, without stopping?",
                null, t.title, text, -1,
                () -> markTogether(t, true), () -> markTogether(t, false), null);
    }

    /** Remembered: together again in a week. Not quite: back tomorrow. Part schedules don't change. */
    private void markTogether(Together t, boolean gotIt) {
        logPractice(t.title);
        int days = gotIt ? 7 : 1;
        db.collection("users").document(currentUserId)
                .collection("memory_verses").document(t.anchor.getId())
                .update("togetherNextDate", new Timestamp(addDays(new Date(), days)))
                .addOnSuccessListener(x -> Toast.makeText(this, gotIt
                        ? "Well done! Together again in a week."
                        : "No problem. It'll be back tomorrow.", Toast.LENGTH_LONG).show());
    }

    // ─── Learn steps ─────────────────────────────────────────────────────────

    private static final String[] LEARN_TITLES = {
            "Read and listen", "Fill the gaps", "Fewer clues", "First letters only"};
    private static final String[] LEARN_TIPS = {
            "Read it aloud slowly, two or three times. Listen along if it helps.",
            "Say the whole thing, filling in the hidden words. Tap a gap if you're stuck.",
            "Every other word is hidden now. Tap a gap if you're stuck.",
            "Only the first letter of each word is left. Say it all. Tap a letter to see the word."};

    /**
     * Four short steps from reading the verse to recalling it from first letters. Learning a new
     * part sets its first test for tomorrow; practising ({@code practice}) leaves the schedule alone.
     */
    private void showLearnDialog(MemoryVerse verse, boolean practice) {
        View dv = LayoutInflater.from(this).inflate(R.layout.dialog_verse_learn, null);
        TextView       tvRef   = dv.findViewById(R.id.tvLearnReference);
        TextView       tvStep  = dv.findViewById(R.id.tvLearnStep);
        TextView       tvTitle = dv.findViewById(R.id.tvLearnTitle);
        TextView       tvTip   = dv.findViewById(R.id.tvLearnTip);
        TextView       tvText  = dv.findViewById(R.id.tvLearnText);
        MaterialButton btnBack = dv.findViewById(R.id.btnLearnBack);
        MaterialButton btnNext = dv.findViewById(R.id.btnLearnNext);

        MaterialButton btnDone = dv.findViewById(R.id.btnLearnDone);
        tvRef.setText(withPartChip(verse.shownReference(), dv.findViewById(R.id.tvLearnPart)));
        ((TextView) dv.findViewById(R.id.tvLearnChip)).setText(practice ? "Practise" : "Learn");
        showLeadIn(dv.findViewById(R.id.tvLearnLeadIn), leadInFor(verse));
        tvText.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        tvText.setHighlightColor(android.graphics.Color.TRANSPARENT);
        speaker.bind(dv.findViewById(R.id.learnListen), verse.getReference(), verse.getText());

        android.app.Dialog dialog = openScreen(dv, dv.findViewById(R.id.learnTopBar), dv.findViewById(R.id.learnBottomBar));
        dialog.setOnDismissListener(d -> speaker.stop());
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            btnDone.setOutlineSpotShadowColor(0xFF8E44C9);
            btnDone.setOutlineAmbientShadowColor(0xFF8E44C9);
        }

        // Four segments filling up as the steps go by.
        LinearLayout segments = dv.findViewById(R.id.learnSteps);
        for (int i = 0; i < LEARN_TITLES.length; i++) {
            View seg = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (i > 0) lp.setMarginStart(dp(6));
            segments.addView(seg, lp);
        }

        LearnText learnText = new LearnText(verse.getText());
        int[] step = {0};
        Runnable[] show = new Runnable[1];
        show[0] = () -> {
            int s = step[0];
            boolean last = s == LEARN_TITLES.length - 1;
            for (int i = 0; i < segments.getChildCount(); i++) {
                android.graphics.drawable.GradientDrawable bar = new android.graphics.drawable.GradientDrawable();
                bar.setCornerRadius(dp(3));
                bar.setColor(i <= s ? 0xFF7E57C2 : androidx.core.content.ContextCompat.getColor(this, R.color.st_soft));
                segments.getChildAt(i).setBackground(bar);
            }
            tvStep.setText("STEP " + (s + 1) + " OF " + LEARN_TITLES.length);
            tvTitle.setText(LEARN_TITLES[s]);
            tvTip.setText(LEARN_TIPS[s]);
            tvText.setText(learnText.render(s, show[0]));
            btnBack.setText(s == 0 ? "Close" : "Back");
            btnNext.setText("Next");
            btnNext.setVisibility(last ? View.GONE : View.VISIBLE);
            btnDone.setVisibility(last ? View.VISIBLE : View.GONE);
            btnDone.setText(practice ? "Done \u2713" : "I've learned it \u2713");
        };
        Runnable stepBack = () -> {
            if (step[0] == 0) { dialog.dismiss(); return; }
            step[0]--;
            learnText.hideAgain();
            show[0].run();
        };
        btnBack.setOnClickListener(b -> stepBack.run());
        dv.findViewById(R.id.btnLearnClose).setOnClickListener(b -> dialog.dismiss());
        // The phone's Back goes back a step (and closes from the first).
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode != android.view.KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == android.view.KeyEvent.ACTION_UP) stepBack.run();
            return true;
        });
        btnNext.setOnClickListener(b -> {
            step[0]++;
            learnText.hideAgain();
            show[0].run();
        });
        btnDone.setOnClickListener(b -> {
            b.setEnabled(false);
            btnBack.setEnabled(false);
            SparkleBurst.play(dv, b, dialog::dismiss);
            if (practice) {
                logPractice(verse.getReference());
                Toast.makeText(this, "Practice counted. Your schedule stays the same.", Toast.LENGTH_LONG).show();
            } else {
                finishLearning(verse);
            }
        });
        show[0].run();
        dialog.show();
    }

    /** Learned: first test tomorrow, then days 3, 5, 6 and 7 (mastered after a week). */
    private void finishLearning(MemoryVerse verse) {
        if (verse.getId() == null) return;
        logPractice(verse.getReference());
        Map<String, Object> updates = new HashMap<>();
        updates.put("needsLearning",    false);
        updates.put("intervalIndex",    0);
        updates.put("stage",            "learning");
        updates.put("lastReviewedDate", todayString());
        updates.put("nextReviewDate",   new Timestamp(addDays(new Date(), INTERVALS[0])));
        db.collection("users").document(currentUserId)
                .collection("memory_verses").document(verse.getId())
                .update(updates)
                .addOnSuccessListener(x -> Toast.makeText(this,
                        "Learned! Your first test is tomorrow.", Toast.LENGTH_LONG).show())
                .addOnFailureListener(e -> Toast.makeText(this,
                        "Couldn't save. Check your connection and try again.", Toast.LENGTH_LONG).show());
    }

    /**
     * A verse's details (full screen): its text with Listen, where it is in the schedule (stage,
     * day, next review), and Edit / Practise / Delete for your own verses.
     */
    private void showVerseDetailDialog(MemoryVerse verse) {
        View v = LayoutInflater.from(this).inflate(R.layout.screen_verse_detail, null);
        android.app.Dialog dialog = openScreen(v, v.findViewById(R.id.detailTopBar), v.findViewById(R.id.detailBottomBar));
        dialog.setOnDismissListener(d -> speaker.stop());
        v.findViewById(R.id.btnDetailBack).setOnClickListener(b -> dialog.dismiss());

        ((TextView) v.findViewById(R.id.tvDetailReference)).setText(
                withPartChip(verse.shownReference(), v.findViewById(R.id.tvDetailPart)));
        ((TextView) v.findViewById(R.id.tvDetailText)).setText(verse.getText());
        if (viewingPartner) {
            TextView owner = v.findViewById(R.id.tvDetailOwner);
            owner.setText(PartnerName.get(this) + " is learning this");
            owner.setVisibility(View.VISIBLE);
        } else if (verse.isShared()) {
            v.findViewById(R.id.tvDetailShared).setVisibility(View.VISIBLE);
        }

        // Where it is in the schedule.
        int idx = verse.getIntervalIndex() != null ? verse.getIntervalIndex() : 0;
        boolean mastered = "mastered".equals(verse.getStage()), locked = isLocked(verse);
        java.text.SimpleDateFormat when = new java.text.SimpleDateFormat("d MMM", Locale.getDefault());
        ((TextView) v.findViewById(R.id.tvDetailStage)).setText(
                locked ? "Locked" : needsLearning(verse) ? "New" : getStageLabel(verse));
        if (mastered) {
            ((TextView) v.findViewById(R.id.tvDetailProgressLabel)).setText("REFRESHES");
            int done = verse.getRefreshes() != null ? verse.getRefreshes() : 0;
            ((TextView) v.findViewById(R.id.tvDetailDay)).setText(done == 1 ? "1 done" : done + " done");
            ((TextView) v.findViewById(R.id.tvDetailNextLabel)).setText("NEXT REFRESH");
        } else {
            ((TextView) v.findViewById(R.id.tvDetailDay)).setText("Day " + (idx + 1) + " of " + INTERVALS.length);
        }
        String next = locked ? "After the part before"
                : verse.getNextReviewDate() == null ? "\u2013"
                : isDue(verse) ? "Today"
                : when.format(verse.getNextReviewDate().toDate());
        ((TextView) v.findViewById(R.id.tvDetailNext)).setText(next);

        // A locked part's text is for later; everything else can be listened to and practised.
        View listen = v.findViewById(R.id.detailListen);
        if (locked) {
            listen.setVisibility(View.GONE);
            v.findViewById(R.id.tvDetailLocked).setVisibility(View.VISIBLE);
        } else {
            speaker.bind(listen, verse.getReference(), verse.getText());
        }

        if (viewingPartner) {
            v.findViewById(R.id.detailBottomBar).setVisibility(View.GONE);
        } else {
            View delete = v.findViewById(R.id.btnDetailDelete);
            delete.setVisibility(View.VISIBLE);
            delete.setOnClickListener(b -> { dialog.dismiss(); onVerseLongTapped(verse); });
            v.findViewById(R.id.btnDetailEdit).setOnClickListener(b -> { dialog.dismiss(); showEditVerseDialog(verse); });
            View practise = v.findViewById(R.id.btnDetailPractise);
            // The learn steps any time, e.g. to brush up before a test. Doesn't change the schedule.
            if (locked) practise.setVisibility(View.GONE);
            practise.setOnClickListener(b -> { dialog.dismiss(); showLearnDialog(verse, true); });
        }
        dialog.show();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String todayString() {
        return ActivityLog.today();
    }

    /** Due any time on its review day, not only after the exact minute it was scheduled. */
    static boolean isDue(MemoryVerse v) {
        if (v.getNextReviewDate() == null) return false;
        // Mastered before refreshes existed: their first refresh is due now.
        if ("mastered".equals(v.getStage()) && v.getRefreshes() == null) return true;
        if (needsLearning(v)) return true; // ready to learn as soon as it's open
        return ProgressMath.isDueOn(v.getNextReviewDate().toDate(), new Date());
    }

    /**
     * Not learned yet, so it gets the learn steps instead of a test. Parts saved before the learn
     * step existed count too if they've never been reviewed: still on Day 1, with no review
     * since the day they were added.
     */
    static boolean needsLearning(MemoryVerse v) {
        if (isLocked(v) || "mastered".equals(v.getStage())) return false;
        if (v.getNeedsLearning() != null) return v.getNeedsLearning();
        int idx = v.getIntervalIndex() != null ? v.getIntervalIndex() : 0;
        return idx == 0 && v.getAddedAt() != null
                && ActivityLog.dayKey(v.getAddedAt().toDate()).equals(v.getLastReviewedDate());
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
        if (speaker != null) speaker.shutdown();
    }

    /** Learned parts of one passage, from the first, practised as one. */
    static class Together {
        final List<MemoryVerse> parts;
        final MemoryVerse anchor; // first part: holds when it's next due
        final String title;

        Together(List<MemoryVerse> parts) {
            this.parts = parts;
            this.anchor = parts.get(0);
            this.title = anchor.getPassageId() != null ? anchor.getPassageId() : anchor.getReference();
        }

        String text() {
            StringBuilder sb = new StringBuilder();
            for (MemoryVerse p : parts) {
                if (sb.length() > 0) sb.append("\n");
                if (p.getText() != null) sb.append(p.getText().trim());
            }
            return sb.toString();
        }

        String label() {
            return "Parts " + (anchor.getChunkIndex() + 1) + "–" + (parts.get(parts.size() - 1).getChunkIndex() + 1);
        }

        /** Due once a week; due straight away the first time. */
        boolean due() {
            return anchor.getTogetherNextDate() == null
                    || ProgressMath.isDueOn(anchor.getTogetherNextDate().toDate(), new Date());
        }
    }

    /** The still-locked parts of one passage, shown as a single row. */
    static class LockedGroup {
        final String title;
        final MemoryVerse firstPart;
        int count;
        LockedGroup(String title, MemoryVerse firstPart) {
            this.title = title;
            this.firstPart = firstPart;
        }
    }

    // ─── Adapter ─────────────────────────────────────────────────────────────

    static class VerseAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int   TYPE_VERSE  = 0;
        private static final int   TYPE_HEADER = 1;
        private static final int   TYPE_LOCKED = 2;
        private static final int   TYPE_TOGETHER = 3;
        private static final int[] INTERVALS   = ProgressMath.REVIEW_GAPS;

        private final List<Object>                              list;
        private final java.util.function.Consumer<MemoryVerse> onTap, onLong;
        private final java.util.function.Consumer<Together>    onTogether;
        private boolean partnerMode = false;

        /** Partner's verses are read-only: no "Review now", and "Shared" goes without saying. */
        void setPartnerMode(boolean on) { partnerMode = on; }

        VerseAdapter(List<Object> list,
                     java.util.function.Consumer<MemoryVerse> onTap,
                     java.util.function.Consumer<MemoryVerse> onLong,
                     java.util.function.Consumer<Together> onTogether) {
            this.list   = list;
            this.onTogether = onTogether;
            this.onTap  = onTap;
            this.onLong = onLong;
        }

        @Override public int getItemViewType(int pos) {
            Object item = list.get(pos);
            if (item instanceof String) return TYPE_HEADER;
            if (item instanceof LockedGroup) return TYPE_LOCKED;
            if (item instanceof Together) return TYPE_TOGETHER;
            return MemorizeActivity.isLocked((MemoryVerse) item) ? TYPE_LOCKED : TYPE_VERSE;
        }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (vt == TYPE_HEADER)
                return new HeaderVH(inf.inflate(R.layout.item_section_header, parent, false));
            if (vt == TYPE_LOCKED)
                return new LockedVH(inf.inflate(R.layout.item_verse_locked, parent, false));
            return new VerseVH(inf.inflate(R.layout.item_memory_verse, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
            if (holder instanceof HeaderVH) {
                String header = (String) list.get(pos);
                ((HeaderVH) holder).tvHeader.setText(
                        "NO_SHARED".equals(header)
                                ? PartnerName.get(holder.itemView.getContext()) + " hasn't shared any verses yet"
                                : header);
                return;
            }

            if (holder instanceof LockedVH) {
                Object item = list.get(pos);
                MemoryVerse lv;
                LockedVH lh = (LockedVH) holder;
                if (item instanceof LockedGroup) {
                    LockedGroup g = (LockedGroup) item;
                    lv = g.firstPart;
                    lh.tvReference.setText(g.title);
                    lh.tvCount.setText(g.count == 1 ? "1 part to unlock" : g.count + " parts to unlock");
                } else {
                    lv = (MemoryVerse) item;
                    lh.tvReference.setText(lv.shownReference());
                    lh.tvCount.setText("Locked");
                }
                holder.itemView.setOnClickListener(view -> onTap.accept(lv));
                holder.itemView.setOnLongClickListener(view -> { onLong.accept(lv); return true; });
                return;
            }

            if (list.get(pos) instanceof Together) {
                bindTogether((VerseVH) holder, (Together) list.get(pos));
                return;
            }

            VerseVH     h = (VerseVH) holder;
            MemoryVerse v = (MemoryVerse) list.get(pos);

            h.tvReference.setText(v.shownReference());
            h.tvText.setText(v.getText());

            int idx = v.getIntervalIndex() != null ? v.getIntervalIndex() : 0;
            if (h.tvProgress != null)
                h.tvProgress.setText("Day " + (idx + 1) + " / " + INTERVALS.length);

            if (h.tvShared != null)
                h.tvShared.setVisibility(v.isShared() && !partnerMode ? View.VISIBLE : View.GONE);

            boolean isDue  = MemorizeActivity.isDue(v);
            boolean isMast = "mastered".equals(v.getStage());

            if (isMast) {
                boolean refreshDue = isDue && !partnerMode;
                h.tvStage.setText(refreshDue ? "Mastered · refresh due" : "Mastered");
                h.tvStage.setTextColor(androidx.core.content.ContextCompat.getColor(h.itemView.getContext(), R.color.st_mastered));
                h.viewStripe.setBackgroundColor(0xFFA23FB5);
                if (h.tvDuePill != null) {
                    h.tvDuePill.setVisibility(refreshDue ? View.VISIBLE : View.GONE);
                    h.tvDuePill.setText("Refresh");
                }
            } else if (MemorizeActivity.needsLearning(v)) {
                h.tvStage.setText(partnerMode ? "New, learning it" : "New · learn it first");
                h.tvStage.setTextColor(androidx.core.content.ContextCompat.getColor(h.itemView.getContext(), R.color.st_accent_strong));
                h.viewStripe.setBackgroundColor(0xFF7E57C2);
                if (h.tvDuePill != null) {
                    h.tvDuePill.setVisibility(partnerMode ? View.GONE : View.VISIBLE);
                    h.tvDuePill.setText("Learn now");
                }
            } else if (isDue) {
                h.tvStage.setText(partnerMode ? "Reviewing today" : "Due for review");
                h.tvStage.setTextColor(androidx.core.content.ContextCompat.getColor(h.itemView.getContext(), R.color.st_accent_strong));
                h.viewStripe.setBackgroundColor(0xFF7E57C2);
                if (h.tvDuePill != null && partnerMode) {
                    h.tvDuePill.setVisibility(View.GONE);
                } else if (h.tvDuePill != null) {
                    h.tvDuePill.setVisibility(View.VISIBLE);
                    h.tvDuePill.setText("Review now");
                }
            } else if (MemorizeActivity.isLocked(v)) {
                h.tvStage.setText("Locked");
                h.tvStage.setTextColor(androidx.core.content.ContextCompat.getColor(h.itemView.getContext(), R.color.st_text_3));
                h.viewStripe.setBackgroundColor(androidx.core.content.ContextCompat.getColor(h.itemView.getContext(), R.color.st_stroke));
                if (h.tvDuePill != null) h.tvDuePill.setVisibility(View.GONE);
            } else {
                h.tvStage.setText(v.getStage() != null ? capitalize(v.getStage()) : "Learning");
                h.tvStage.setTextColor(androidx.core.content.ContextCompat.getColor(h.itemView.getContext(), R.color.st_accent));
                h.viewStripe.setBackgroundColor(0xFFB39DDB);
                if (h.tvDuePill != null) h.tvDuePill.setVisibility(View.GONE);
            }

            h.itemView.setOnClickListener(view -> onTap.accept(v));
            h.itemView.setOnLongClickListener(view -> { onLong.accept(v); return true; });
        }

        private void bindTogether(VerseVH h, Together t) {
            boolean due = t.due();
            h.tvReference.setText(t.title + " · " + t.label());
            h.tvText.setText(t.text());
            if (h.tvProgress != null) h.tvProgress.setText(t.parts.size() + " parts");
            if (h.tvShared != null) h.tvShared.setVisibility(View.GONE);
            h.tvStage.setText(due ? "Say them as one" : "Next: " + new SimpleDateFormat("d MMM", Locale.getDefault())
                    .format(t.anchor.getTogetherNextDate().toDate()));
            h.tvStage.setTextColor(due ? androidx.core.content.ContextCompat.getColor(h.itemView.getContext(), R.color.st_accent_strong) : androidx.core.content.ContextCompat.getColor(h.itemView.getContext(), R.color.st_accent));
            h.viewStripe.setBackgroundColor(due ? 0xFF7E57C2 : 0xFFB39DDB);
            if (h.tvDuePill != null) {
                h.tvDuePill.setVisibility(due ? View.VISIBLE : View.GONE);
                h.tvDuePill.setText("Say it together");
            }
            h.itemView.setOnClickListener(view -> onTogether.accept(t));
            h.itemView.setOnLongClickListener(null);
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

        static class LockedVH extends RecyclerView.ViewHolder {
            TextView tvReference, tvCount;
            LockedVH(@NonNull View v) {
                super(v);
                tvReference = v.findViewById(R.id.tvLockedReference);
                tvCount     = v.findViewById(R.id.tvLockedCount);
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