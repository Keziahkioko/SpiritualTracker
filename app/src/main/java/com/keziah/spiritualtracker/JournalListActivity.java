package com.keziah.spiritualtracker;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;
import com.keziah.spiritualtracker.databinding.ActivityJournalListBinding;

import java.util.ArrayList;
import java.util.List;

public class JournalListActivity extends AppCompatActivity {

    private ActivityJournalListBinding binding;
    private FirebaseFirestore db;
    private FirebaseAuth auth;
    private JournalAdapter adapter;

    private List<JournalEntry> fullJournalList = new ArrayList<>();
    private List<JournalEntry> activeList      = new ArrayList<>();

    // Filter state: "all" | "mine" | "partner" | "audio" | "week"
    private String activeFilter = "all";
    private String activeSearch = "";
    private String myId         = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityJournalListBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        PartnerName.apply(findViewById(android.R.id.content));

        db   = FirebaseFirestore.getInstance();
        auth = FirebaseAuth.getInstance();

        adapter = new JournalAdapter(this, activeList);
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerView.setAdapter(adapter);

        binding.btnJournalListBack.setOnClickListener(v -> finish());
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(false);
        StatusBarUtil.padTop(binding.journalHeader);
        final int listBottom = binding.recyclerView.getPaddingBottom();
        final int fabBottom = ((android.view.ViewGroup.MarginLayoutParams) binding.fabAdd.getLayoutParams()).bottomMargin;
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.recyclerView, (v, insets) -> {
            int nav = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom;
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), listBottom + nav);
            ((android.view.ViewGroup.MarginLayoutParams) binding.fabAdd.getLayoutParams()).bottomMargin = fabBottom + nav;
            binding.fabAdd.requestLayout();
            return insets;
        });

        FabScroll.attach(binding.fabAdd, binding.recyclerView);
        binding.fabAdd.setOnClickListener(v ->
                startActivity(new Intent(this, JournalActivity.class)));

        setupChips();
        setupSearch();
        setupSwipeToDelete();
        loadJournalEntries();
        handleNotificationIntent();

        android.app.NotificationManager nm =
                (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancelAll();
    }

    // ── Chips ─────────────────────────────────────────────────────────────

    private void setupChips() {
        binding.chipAll     .setOnClickListener(v -> selectChip("all"));
        binding.chipMine    .setOnClickListener(v -> selectChip("mine"));
        binding.chipPartner .setOnClickListener(v -> selectChip("partner"));
        binding.chipAudio   .setOnClickListener(v -> selectChip("audio"));
        binding.chipThisWeek.setOnClickListener(v -> selectChip("week"));
    }

    private void selectChip(String filter) {
        activeFilter = filter;
        updateChipUI(filter);
        applyFilters();
    }

    private void updateChipUI(String active) {
        TextView[] all = {
                binding.chipAll, binding.chipMine, binding.chipPartner,
                binding.chipAudio, binding.chipThisWeek
        };
        for (TextView chip : all) {
            boolean isActive = chip.getTag() != null && chip.getTag().equals(active)
                    || (active.equals("all")     && chip == binding.chipAll)
                    || (active.equals("mine")    && chip == binding.chipMine)
                    || (active.equals("partner") && chip == binding.chipPartner)
                    || (active.equals("audio")   && chip == binding.chipAudio)
                    || (active.equals("week")    && chip == binding.chipThisWeek);

            if (isActive) {
                chip.setBackgroundResource(R.drawable.chip_active);
                chip.setTextColor(0xFFFFFFFF);
                chip.setTypeface(null, Typeface.BOLD);
            } else {
                chip.setBackgroundResource(R.drawable.chip_inactive);
                chip.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.st_accent));
                chip.setTypeface(null, Typeface.NORMAL);
            }
        }
    }

    // ── Search ────────────────────────────────────────────────────────────

    private void setupSearch() {
        // Style the inner EditText to match the new white header
        android.widget.EditText et = binding.searchView
                .findViewById(androidx.appcompat.R.id.search_src_text);
        android.widget.ImageView searchIcon = binding.searchView
                .findViewById(androidx.appcompat.R.id.search_mag_icon);
        android.widget.ImageView closeIcon = binding.searchView
                .findViewById(androidx.appcompat.R.id.search_close_btn);

        et.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.st_text));
        et.setHintTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.st_text_3));
        if (searchIcon != null) searchIcon.setColorFilter(androidx.core.content.ContextCompat.getColor(this, R.color.st_text_3));
        if (closeIcon  != null) closeIcon .setColorFilter(androidx.core.content.ContextCompat.getColor(this, R.color.st_text_3));
        android.view.View plate = binding.searchView.findViewById(androidx.appcompat.R.id.search_plate);
        if (plate != null) plate.setBackgroundColor(android.graphics.Color.TRANSPARENT); // no underline

        binding.searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override public boolean onQueryTextSubmit(String q)  { return false; }
            @Override public boolean onQueryTextChange(String q) {
                activeSearch = q == null ? "" : q;
                applyFilters();
                return true;
            }
        });
    }

    // ── Filtering logic ───────────────────────────────────────────────────

    private void applyFilters() {
        long weekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000;
        List<JournalEntry> result = new ArrayList<>();

        for (JournalEntry e : fullJournalList) {
            // Chip filter
            switch (activeFilter) {
                case "mine":
                    if (!myId.equals(e.getUserId())) continue;
                    break;
                case "partner":
                    if (myId.equals(e.getUserId())) continue;
                    break;
                case "audio":
                    if (e.getAudioUrl() == null || e.getAudioUrl().isEmpty()) continue;
                    break;
                case "week":
                    if (e.getDate() == null || e.getDate().toDate().getTime() < weekAgo) continue;
                    break;
            }

            // Search filter
            if (!activeSearch.isEmpty()) {
                String q = activeSearch.toLowerCase();
                boolean titleMatch   = e.getTitle()   != null && e.getTitle()  .toLowerCase().contains(q);
                boolean contentMatch = e.getContent() != null && e.getContent().toLowerCase().contains(q);
                if (!titleMatch && !contentMatch) continue;
            }

            result.add(e);
        }

        adapter.setFilteredList(result, activeSearch);
    }

    // ── Swipe to delete ───────────────────────────────────────────────────

    private void setupSwipeToDelete() {
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh,
                                  @NonNull RecyclerView.ViewHolder t) { return false; }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) {
                int pos = vh.getAdapterPosition();

                // ─── PERMISSION CHECK: Only allow delete if it's the user's own entry ───
                if (!adapter.canDeleteEntry(pos)) {
                    Toast.makeText(JournalListActivity.this,
                            "You can only delete your own entries", Toast.LENGTH_SHORT).show();
                    adapter.notifyItemChanged(pos); // Restore the swiped view
                    return;
                }

                JournalEntry entry = adapter.getItem(pos);
                new AlertDialog.Builder(JournalListActivity.this)
                        .setTitle("Delete Entry")
                        .setMessage("Are you sure you want to delete this memory?")
                        .setPositiveButton("Delete", (d, w) -> deleteEntry(entry.getDocId()))
                        .setNegativeButton("Cancel", (d, w) -> adapter.notifyItemChanged(pos))
                        .setCancelable(false)
                        .show();
            }
        }).attachToRecyclerView(binding.recyclerView);
    }

    private void deleteEntry(String docId) {
        if (docId == null) { Toast.makeText(this, "Error finding entry", Toast.LENGTH_SHORT).show(); return; }
        // Remove its photos and voice notes from storage first (the relay checks the entry while
        // it still exists); the entry is deleted either way, even if that clean-up fails.
        Relay.deleteJournalMedia(docId, null, ok -> removeEntryDoc(docId));
    }

    private void removeEntryDoc(String docId) {
        db.collection("journal_entries").document(docId)
                .delete()
                .addOnSuccessListener(v -> Toast.makeText(this, "Entry deleted", Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e -> Toast.makeText(this, "Error deleting", Toast.LENGTH_SHORT).show());
    }

    // ── Firestore load ────────────────────────────────────────────────────

    private void loadJournalEntries() {
        if (auth.getCurrentUser() == null) return;
        myId = auth.getCurrentUser().getUid();

        db.collection("users").document(myId).get().addOnSuccessListener(doc -> {
            String partnerId = doc.getString("partnerId");
            queryFirestore(myId, (partnerId != null && !partnerId.isEmpty()) ? partnerId : null);
        });
    }

    private void queryFirestore(String myId, @Nullable String partnerId) {
        List<String> ids = new ArrayList<>();
        ids.add(myId);
        if (partnerId != null) ids.add(partnerId);

        db.collection("journal_entries")
                .whereIn("userId", ids)
                .orderBy("date", Query.Direction.DESCENDING)
                .addSnapshotListener(new EventListener<QuerySnapshot>() {
                    @Override
                    public void onEvent(@Nullable QuerySnapshot value,
                                        @Nullable FirebaseFirestoreException error) {
                        if (error != null) {
                            Toast.makeText(JournalListActivity.this,
                                    "Error: " + error.getMessage(), Toast.LENGTH_SHORT).show();
                            return;
                        }
                        fullJournalList.clear();
                        if (value != null) {
                            for (DocumentSnapshot d : value.getDocuments()) {
                                JournalEntry entry = d.toObject(JournalEntry.class);
                                // Drafts are private until published.
                                boolean partnersDraft = entry != null && "draft".equals(entry.getStatus())
                                        && !myId.equals(entry.getUserId());
                                if (entry != null && !partnersDraft) {
                                    entry.setDocId(d.getId());
                                    fullJournalList.add(entry);
                                }
                            }
                        }
                        applyFilters();
                    }
                });
    }

    // ── Notification routing ──────────────────────────────────────────────

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNotificationIntent();
    }

    private void handleNotificationIntent() {
        Intent intent = getIntent();
        if (intent == null || !intent.getBooleanExtra("openFromNotification", false)) return;

        String targetDocId = intent.getStringExtra("targetDocId");
        if (targetDocId == null || targetDocId.isEmpty()) return;

        db.collection("journal_entries").document(targetDocId).get()
                .addOnSuccessListener(doc -> {
                    if (!doc.exists()) return;
                    String dateStr = "";
                    if (doc.getTimestamp("date") != null) {
                        dateStr = android.text.format.DateFormat
                                .format("MMM d, yyyy 'at' h:mm a",
                                        doc.getTimestamp("date").toDate()).toString();
                    }
                    Intent detail = new Intent(this, JournalDetailActivity.class);
                    detail.putExtra("title",         doc.getString("title"));
                    detail.putExtra("content",       doc.getString("content"));
                    detail.putExtra("date",          dateStr);
                    detail.putExtra("authorName",    doc.getString("authorName"));
                    detail.putExtra("authorId",      doc.getString("userId"));
                    detail.putExtra("audioUrl",      doc.getString("audioUrl"));
                    detail.putStringArrayListExtra("imageUrls",
                            JournalPhotos.from(doc.get("imageUrls"), doc.getString("imageUrl")));
                    detail.putExtra("docId",         targetDocId);
                    detail.putExtra("status",        doc.getString("status"));
                    detail.putExtra("audioDuration", doc.getString("audioDuration"));
                    startActivity(detail);
                });

        intent.removeExtra("openFromNotification");
    }

    @Override
    protected void onDestroy() {
        if (adapter != null) {
            adapter.detachVisibleReplyListeners(binding.recyclerView);
        }
        super.onDestroy();
    }
}