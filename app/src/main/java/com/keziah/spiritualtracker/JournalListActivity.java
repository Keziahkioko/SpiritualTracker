package com.keziah.spiritualtracker;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView; // Import for the Search Bar
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

    // We need TWO lists now:
    private List<JournalEntry> journalList;      // The list currently shown on screen
    private List<JournalEntry> fullJournalList;  // A backup of ALL data (for searching)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityJournalListBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // 1. Setup Firebase
        db = FirebaseFirestore.getInstance();
        auth = FirebaseAuth.getInstance();

        // 2. Setup Lists
        journalList = new ArrayList<>();
        fullJournalList = new ArrayList<>(); // Initialize the backup list

        adapter = new JournalAdapter(this, journalList);
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerView.setAdapter(adapter);

        // 3. Setup Add Button
        binding.fabAdd.setOnClickListener(v -> {
            Intent intent = new Intent(JournalListActivity.this, JournalActivity.class);
            startActivity(intent);
        });

        // 4. Setup Features
        setupSwipeToDelete();
        setupSearch(); // <--- NEW: Turn on the search listener

        // 5. Load Data
        loadJournalEntries();
    }

    // --- NEW: SEARCH SETUP ---
    private void setupSearch() {
        // 1. Find the internal Views of the Search Bar
        android.widget.EditText searchEditText = binding.searchView.findViewById(androidx.appcompat.R.id.search_src_text);
        android.widget.ImageView searchIcon = binding.searchView.findViewById(androidx.appcompat.R.id.search_mag_icon);
        android.widget.ImageView closeIcon = binding.searchView.findViewById(androidx.appcompat.R.id.search_close_btn);

        // 2. Force them to be BLACK
        searchEditText.setTextColor(android.graphics.Color.BLACK);
        searchEditText.setHintTextColor(android.graphics.Color.BLACK);

        if (searchIcon != null) {
            searchIcon.setColorFilter(android.graphics.Color.BLACK);
        }
        if (closeIcon != null) {
            closeIcon.setColorFilter(android.graphics.Color.BLACK);
        }
        binding.searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                return false;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                filter(newText); // Filter the list every time you type a letter
                return true;
            }
        });
    }

    // --- NEW: FILTER LOGIC ---
    private void filter(String text) {
        List<JournalEntry> filteredList = new ArrayList<>();

        // Loop through the BACKUP list to find matches
        for (JournalEntry item : fullJournalList) {
            boolean matchesTitle = item.getTitle().toLowerCase().contains(text.toLowerCase());
            // Check content too (handle nulls safely)
            boolean matchesContent = item.getContent() != null && item.getContent().toLowerCase().contains(text.toLowerCase());

            if (matchesTitle || matchesContent) {
                filteredList.add(item);
            }
        }

        if (filteredList.isEmpty()) {
            // Optional: You could show a "No results" toast if you want
            // Toast.makeText(this, "No Match Found", Toast.LENGTH_SHORT).show();
        }

        // CRITICAL: Update the Activity's 'journalList' reference so Swipe-to-Delete
        // deletes the correct item (the one visible on screen, not the hidden one)
        this.journalList = filteredList;

        // Update the adapter
        adapter.setFilteredList(filteredList);
    }

    private void setupSwipeToDelete() {
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                int position = viewHolder.getAdapterPosition();

                // Get the item from the CURRENT visible list
                JournalEntry entryToDelete = journalList.get(position);

                new AlertDialog.Builder(JournalListActivity.this)
                        .setTitle("Delete Entry")
                        .setMessage("Are you sure you want to delete this memory?")
                        .setPositiveButton("Yes, Delete", (dialog, which) -> {
                            deleteEntryFromCloud(entryToDelete.getDocId());
                        })
                        .setNegativeButton("Cancel", (dialog, which) -> {
                            // If they cancel, put the item back on the screen
                            adapter.notifyItemChanged(position);
                        })
                        .setCancelable(false)
                        .show();
            }
        }).attachToRecyclerView(binding.recyclerView);
    }

    private void deleteEntryFromCloud(String docId) {
        if (docId == null) {
            Toast.makeText(this, "Error: Could not find entry ID", Toast.LENGTH_SHORT).show();
            return;
        }

        db.collection("journal_entries").document(docId)
                .delete()
                .addOnSuccessListener(aVoid -> Toast.makeText(JournalListActivity.this, "Entry Deleted", Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e -> Toast.makeText(JournalListActivity.this, "Error deleting", Toast.LENGTH_SHORT).show());
    }

    private void loadJournalEntries() {
        if (auth.getCurrentUser() == null) return;
        String userId = auth.getCurrentUser().getUid();

        db.collection("journal_entries")
                .whereEqualTo("userId", userId)
                .orderBy("date", Query.Direction.DESCENDING)
                .addSnapshotListener(new EventListener<QuerySnapshot>() {
                    @Override
                    public void onEvent(@Nullable QuerySnapshot value, @Nullable FirebaseFirestoreException error) {
                        if (error != null) {
                            Toast.makeText(JournalListActivity.this, "Error loading", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        // We must reset the 'journalList' reference to a new list
                        // so we don't accidentally keep the "Filtered" list forever.
                        journalList = new ArrayList<>();
                        fullJournalList.clear(); // Clear the backup too

                        if (value != null) {
                            for (DocumentSnapshot d : value.getDocuments()) {
                                JournalEntry entry = d.toObject(JournalEntry.class);
                                entry.setDocId(d.getId());

                                journalList.add(entry);     // Add to main list
                                fullJournalList.add(entry); // Add to backup list
                            }
                        }

                        // Pass the FULL list to the adapter initially
                        adapter.setFilteredList(journalList);
                    }
                });
    }
}