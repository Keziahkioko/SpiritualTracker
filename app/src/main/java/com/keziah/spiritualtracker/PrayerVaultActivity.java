package com.keziah.spiritualtracker;

import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PrayerVaultActivity extends AppCompatActivity {

    private RecyclerView rvVault;
    private VaultAdapter adapter;
    private LinearLayout layoutEmptyVault;

    private TextView tvStatTotal, tvStatMine, tvStatPartner;
    private TextView tvGhostNumber;
    private TextView tabAll, tabMine, tabPartner;
    private String   activeTab = "all";

    private final List<PrayerRequest> fullList     = new ArrayList<>();
    private final List<PrayerRequest> filteredList = new ArrayList<>();

    private FirebaseFirestore    db;
    private String               currentUserId;
    private String               partnerId;
    private ListenerRegistration vaultListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_prayer_vault);

        db = FirebaseFirestore.getInstance();
        if (FirebaseAuth.getInstance().getCurrentUser() != null)
            currentUserId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        partnerId = getIntent().getStringExtra("partnerId");

        rvVault          = findViewById(R.id.rvVault);
        layoutEmptyVault = findViewById(R.id.layoutEmptyVault);
        tvStatTotal      = findViewById(R.id.tvStatTotal);
        tvStatMine       = findViewById(R.id.tvStatMine);
        tvStatPartner    = findViewById(R.id.tvStatPartner);
        tvGhostNumber    = findViewById(R.id.tvGhostNumber);
        tabAll           = findViewById(R.id.tabAll);
        tabMine          = findViewById(R.id.tabMine);
        tabPartner       = findViewById(R.id.tabPartner);

        adapter = new VaultAdapter(this, filteredList,
                this::showTestimonyDialog,
                this::showDeleteDialog);

        rvVault.setLayoutManager(new LinearLayoutManager(this));
        rvVault.setAdapter(adapter);

        setupTabs();
        loadAnsweredPrayers();
    }

    private void setupTabs() {
        tabAll    .setOnClickListener(v -> selectTab("all"));
        tabMine   .setOnClickListener(v -> selectTab("mine"));
        tabPartner.setOnClickListener(v -> selectTab("partner"));
    }

    private void selectTab(String tab) {
        activeTab = tab;
        updateTabUI();
        applyFilter();
    }

    private void updateTabUI() {
        TextView[] tabs = { tabAll, tabMine, tabPartner };
        String[]   keys = { "all",  "mine",  "partner" };
        for (int i = 0; i < tabs.length; i++) {
            boolean active = keys[i].equals(activeTab);
            tabs[i].setBackgroundResource(
                    active ? R.drawable.tab_underline_active : android.R.color.transparent);
            tabs[i].setTextColor(active ? 0xFF1A1A2E : 0xFFAAAAAA);
            tabs[i].setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private void applyFilter() {
        filteredList.clear();
        int mineCount = 0, partnerCount = 0;

        for (PrayerRequest r : fullList) {
            boolean isMe = currentUserId != null && currentUserId.equals(r.getAuthorId());
            if (isMe) mineCount++; else partnerCount++;

            switch (activeTab) {
                case "mine":    if (!isMe) continue; break;
                case "partner": if (isMe)  continue; break;
            }
            filteredList.add(r);
        }

        int total = fullList.size();

        if (tvStatTotal   != null) tvStatTotal  .setText(String.valueOf(total));
        if (tvStatMine    != null) tvStatMine   .setText(String.valueOf(mineCount));
        if (tvStatPartner != null) tvStatPartner.setText(String.valueOf(partnerCount));

        // Ghost number always reflects total
        if (tvGhostNumber != null) tvGhostNumber.setText(String.valueOf(total));

        adapter.updateList(filteredList);
        layoutEmptyVault.setVisibility(filteredList.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void loadAnsweredPrayers() {
        if (currentUserId == null || partnerId == null) return;

        vaultListener = db.collection("shared_prayers")
                .whereEqualTo("isAnswered", true)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .addSnapshotListener((value, error) -> {
                    if (error != null) { Log.e("Vault", error.getMessage()); return; }
                    if (value == null) return;

                    fullList.clear();
                    for (DocumentSnapshot doc : value.getDocuments()) {
                        PrayerRequest r = doc.toObject(PrayerRequest.class);
                        if (r != null) {
                            String aid = r.getAuthorId();
                            if (aid != null &&
                                    (aid.equals(currentUserId) || aid.equals(partnerId))) {
                                r.setRequestId(doc.getId());
                                fullList.add(r);
                            }
                        }
                    }
                    applyFilter();
                });
    }

    private void showTestimonyDialog(PrayerRequest request) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_view_testimony, null);

        TextView tvTitle     = v.findViewById(R.id.tvDialogTitle);
        TextView tvAuthor    = v.findViewById(R.id.tvDialogAuthor);
        TextView tvDesc      = v.findViewById(R.id.tvDialogDescription);
        TextView tvTestimony = v.findViewById(R.id.tvDialogTestimony);
        MaterialButton btnDelete = v.findViewById(R.id.btnDeleteTestimony);

        if (tvTitle != null) tvTitle.setText(request.getTitle());

        if (tvAuthor != null) {
            boolean isMe = currentUserId != null && currentUserId.equals(request.getAuthorId());
            String name = isMe ? "Me"
                    : (request.getAuthorName() != null ? request.getAuthorName() : "Partner");
            String line = "By " + name;
            if (request.getTimestamp() != null)
                line += " · " + new SimpleDateFormat("MMM yyyy", Locale.getDefault())
                        .format(request.getTimestamp().toDate());
            tvAuthor.setText(line);
        }

        if (tvDesc != null) {
            String d = request.getDescription();
            tvDesc.setText(d != null && !d.isEmpty() ? d : "No details recorded.");
        }

        if (tvTestimony != null) {
            String c = request.getAnsweredComment();
            tvTestimony.setText(c != null && !c.isEmpty()
                    ? "\u201c" + c + "\u201d" : "No testimony recorded yet.");
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(this,
                R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setView(v)
                .setPositiveButton("Praise God!", null)
                .create();

        if (btnDelete != null)
            btnDelete.setOnClickListener(b -> { dialog.dismiss(); showDeleteDialog(request); });

        dialog.show();
    }

    private void showDeleteDialog(PrayerRequest request) {
        new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setTitle("Remove from Vault?")
                .setMessage("\"" + request.getTitle() + "\" will be permanently deleted.")
                .setPositiveButton("Delete", (d, w) -> {
                    fullList.remove(request);
                    applyFilter();
                    db.collection("shared_prayers").document(request.getRequestId())
                            .delete()
                            .addOnSuccessListener(unused ->
                                    Toast.makeText(this, "Removed from Vault",
                                            Toast.LENGTH_SHORT).show());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (vaultListener != null) vaultListener.remove();
    }
}
