package com.keziah.spiritualtracker;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MySpaceFragment extends Fragment {

    private RecyclerView rvMyPrayers;
    private LinearLayout layoutMySpaceEmpty;
    private TextView tvActiveCount, tvAnsweredCount;
    private ExtendedFloatingActionButton fabAddPersonalPrayer;

    private PersonalPrayerAdapter adapter;
    private List<PrayerRequest> personalList;

    private FirebaseFirestore db;
    private String currentUserId;
    private ListenerRegistration prayerListener;

    public MySpaceFragment() {}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_my_space, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        db = FirebaseFirestore.getInstance();
        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            currentUserId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        }

        rvMyPrayers       = view.findViewById(R.id.rvMyPrayers);
        layoutMySpaceEmpty = view.findViewById(R.id.layoutMySpaceEmpty);
        tvActiveCount     = view.findViewById(R.id.tvActiveCount);
        tvAnsweredCount   = view.findViewById(R.id.tvAnsweredCount);
        fabAddPersonalPrayer = view.findViewById(R.id.fabAddPersonalPrayer);

        personalList = new ArrayList<>();
        adapter = new PersonalPrayerAdapter(personalList, this::onPrayerTapped, this::onPrayerLongTapped);
        rvMyPrayers.setLayoutManager(new LinearLayoutManager(getContext()));
        rvMyPrayers.setAdapter(adapter);

        fabAddPersonalPrayer.setOnClickListener(v -> showAddPersonalPrayerDialog());

        listenToPersonalPrayers();
    }

    // ───── Firestore ─────

    private void listenToPersonalPrayers() {
        if (currentUserId == null) return;

        prayerListener = db.collection("users").document(currentUserId)
                .collection("personal_prayers")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .addSnapshotListener((value, error) -> {
                    if (error != null || value == null) return;

                    personalList.clear();
                    int active = 0, answered = 0;

                    for (DocumentSnapshot doc : value.getDocuments()) {
                        PrayerRequest pr = doc.toObject(PrayerRequest.class);
                        if (pr != null) {
                            pr.setRequestId(doc.getId());
                            personalList.add(pr);
                            if (pr.isAnswered()) answered++;
                            else active++;
                        }
                    }

                    adapter.notifyDataSetChanged();

                    if (tvActiveCount   != null) tvActiveCount.setText(String.valueOf(active));
                    if (tvAnsweredCount != null) tvAnsweredCount.setText(String.valueOf(answered));

                    if (layoutMySpaceEmpty != null) {
                        layoutMySpaceEmpty.setVisibility(personalList.isEmpty() ? View.VISIBLE : View.GONE);
                    }
                });
    }

    private void savePersonalPrayer(String title, String desc) {
        if (currentUserId == null) return;

        Map<String, Object> data = new HashMap<>();
        data.put("title", title);
        data.put("description", desc);
        data.put("authorId", currentUserId);
        data.put("isAnswered", false);
        data.put("timestamp", FieldValue.serverTimestamp());

        db.collection("users").document(currentUserId)
                .collection("personal_prayers")
                .add(data)
                .addOnSuccessListener(ref ->
                        Toast.makeText(getContext(), "Prayer added 🙏", Toast.LENGTH_SHORT).show());
    }

    private void markPersonalAnswered(PrayerRequest pr, String testimony) {
        if (currentUserId == null) return;

        Map<String, Object> updates = new HashMap<>();
        updates.put("isAnswered", true);
        updates.put("answeredComment", testimony);

        db.collection("users").document(currentUserId)
                .collection("personal_prayers")
                .document(pr.getRequestId())
                .update(updates)
                .addOnSuccessListener(v ->
                        Toast.makeText(getContext(), "Marked as answered! ✨", Toast.LENGTH_SHORT).show());
    }

    private void deletePersonalPrayer(PrayerRequest pr) {
        if (currentUserId == null) return;

        db.collection("users").document(currentUserId)
                .collection("personal_prayers")
                .document(pr.getRequestId())
                .delete()
                .addOnSuccessListener(v ->
                        Toast.makeText(getContext(), "Prayer removed", Toast.LENGTH_SHORT).show());
    }

    // ───── Tap handlers ─────

    private void onPrayerTapped(PrayerRequest pr) {
        showPrayerDetailDialog(pr);
    }

    private void onPrayerLongTapped(PrayerRequest pr) {
        showDeletePersonalDialog(pr);
    }

    // ───── Dialogs ─────

    private void showAddPersonalPrayerDialog() {
        View dialogView = LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_add_personal_prayer, null);
        TextInputEditText etTitle = dialogView.findViewById(R.id.etPersonalTitle);
        TextInputEditText etDesc  = dialogView.findViewById(R.id.etPersonalDescription);

        new MaterialAlertDialogBuilder(getContext(), R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setView(dialogView)
                .setPositiveButton("Add Prayer", (d, w) -> {
                    String title = etTitle.getText() != null ? etTitle.getText().toString().trim() : "";
                    String desc  = etDesc.getText()  != null ? etDesc.getText().toString().trim()  : "";
                    if (!title.isEmpty()) savePersonalPrayer(title, desc);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showPrayerDetailDialog(PrayerRequest pr) {
        // If not yet answered, show options: view details / mark answered
        // If answered, show testimony
        if (pr.isAnswered()) {
            showTestimonyDetailDialog(pr);
        } else {
            showActiveDetailDialog(pr);
        }
    }

    private void showActiveDetailDialog(PrayerRequest pr) {
        String desc = (pr.getDescription() != null && !pr.getDescription().isEmpty())
                ? pr.getDescription() : "No details added.";

        new MaterialAlertDialogBuilder(getContext(), R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setTitle(pr.getTitle())
                .setMessage(desc)
                .setPositiveButton("Mark as Answered ✨", (d, w) -> showMarkAnsweredDialog(pr))
                .setNeutralButton("Delete", (d, w) -> showDeletePersonalDialog(pr))
                .setNegativeButton("Close", null)
                .show();
    }

    private void showMarkAnsweredDialog(PrayerRequest pr) {
        View dialogView = LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_save_to_vault, null);
        TextView tvTitle = dialogView.findViewById(R.id.tvDialogRequestTitle);
        TextInputEditText etTestimony = dialogView.findViewById(R.id.etTestimony);

        if (tvTitle != null) tvTitle.setText(pr.getTitle());

        new MaterialAlertDialogBuilder(getContext(), R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setView(dialogView)
                .setPositiveButton("Save", (d, w) -> {
                    String testimony = etTestimony.getText() != null
                            ? etTestimony.getText().toString().trim() : "";
                    markPersonalAnswered(pr, testimony);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showTestimonyDetailDialog(PrayerRequest pr) {
        View dialogView = LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_view_testimony, null);

        TextView tvTitle      = dialogView.findViewById(R.id.tvDialogTitle);
        TextView tvAuthor     = dialogView.findViewById(R.id.tvDialogAuthor);
        TextView tvDesc       = dialogView.findViewById(R.id.tvDialogDescription);
        TextView tvTestimony  = dialogView.findViewById(R.id.tvDialogTestimony);
        com.google.android.material.button.MaterialButton btnDelete =
                dialogView.findViewById(R.id.btnDeleteTestimony);

        if (tvTitle != null) tvTitle.setText(pr.getTitle());
        if (tvAuthor != null) {
            String date = pr.getTimestamp() != null
                    ? new SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(pr.getTimestamp().toDate())
                    : "";
            tvAuthor.setText("Personal · " + date);
        }
        if (tvDesc != null) {
            tvDesc.setText(pr.getDescription() != null && !pr.getDescription().isEmpty()
                    ? pr.getDescription() : "No details recorded.");
        }
        if (tvTestimony != null) {
            String c = pr.getAnsweredComment();
            tvTestimony.setText(c != null && !c.isEmpty() ? "\"" + c + "\"" : "No testimony recorded.");
        }

        androidx.appcompat.app.AlertDialog dialog =
                new MaterialAlertDialogBuilder(getContext(), R.style.ThemeOverlay_App_MaterialAlertDialog)
                        .setView(dialogView)
                        .setPositiveButton("Praise God! 🙌", null)
                        .create();

        if (btnDelete != null) {
            btnDelete.setText("Delete Prayer");
            btnDelete.setOnClickListener(v -> {
                dialog.dismiss();
                showDeletePersonalDialog(pr);
            });
        }

        dialog.show();
    }

    private void showDeletePersonalDialog(PrayerRequest pr) {
        new MaterialAlertDialogBuilder(getContext(), R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setTitle("Delete Prayer?")
                .setMessage("\"" + pr.getTitle() + "\" will be permanently deleted.")
                .setPositiveButton("Delete", (d, w) -> deletePersonalPrayer(pr))
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (prayerListener != null) prayerListener.remove();
    }

    // ───── Inner Adapter ─────

    interface OnPrayerAction {
        void onAction(PrayerRequest pr);
    }

    static class PersonalPrayerAdapter extends RecyclerView.Adapter<PersonalPrayerAdapter.VH> {

        private final List<PrayerRequest> list;
        private final OnPrayerAction onTap, onLongTap;

        PersonalPrayerAdapter(List<PrayerRequest> list, OnPrayerAction onTap, OnPrayerAction onLongTap) {
            this.list = list;
            this.onTap = onTap;
            this.onLongTap = onLongTap;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_personal_prayer, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            PrayerRequest pr = list.get(position);

            h.tvTitle.setText(pr.getTitle());

            String desc = pr.getDescription();
            if (desc != null && !desc.isEmpty()) {
                h.tvDescription.setText(desc);
                h.tvDescription.setVisibility(View.VISIBLE);
            } else {
                h.tvDescription.setVisibility(View.GONE);
            }

            if (pr.getTimestamp() != null) {
                String date = new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                        .format(pr.getTimestamp().toDate());
                h.tvDate.setText("Added " + date);
            }

            // Answered state: dim the stripe, hide the badge (answered prayers show testimony on tap)
            if (pr.isAnswered()) {
                h.stripeView.setBackgroundColor(Color.parseColor("#B39DDB")); // muted purple
                h.answeredBadge.setVisibility(View.GONE);
                h.tvTitle.setTextColor(Color.parseColor("#9575CD"));
            } else {
                h.stripeView.setBackgroundColor(Color.parseColor("#7E57C2")); // vivid purple
                h.answeredBadge.setVisibility(View.VISIBLE);
                h.tvTitle.setTextColor(Color.parseColor("#1A1A1A"));
                h.answeredBadge.setOnClickListener(v ->
                        h.answeredBadge.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80)
                                .withEndAction(() -> h.answeredBadge.animate().scaleX(1f).scaleY(1f)
                                        .setDuration(80).withEndAction(() -> onTap.onAction(pr))));
            }

            h.itemView.setOnClickListener(v -> onTap.onAction(pr));
            h.itemView.setOnLongClickListener(v -> { onLongTap.onAction(pr); return true; });
        }

        @Override
        public int getItemCount() { return list.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvTitle, tvDescription, tvDate;
            View stripeView;
            View answeredBadge;

            VH(@NonNull View v) {
                super(v);
                tvTitle      = v.findViewById(R.id.tvPersonalTitle);
                tvDescription = v.findViewById(R.id.tvPersonalDescription);
                tvDate       = v.findViewById(R.id.tvPersonalDate);
                stripeView   = v.findViewById(R.id.viewAccentStripe);
                answeredBadge = v.findViewById(R.id.personalAnsweredBadge);
            }
        }
    }
}