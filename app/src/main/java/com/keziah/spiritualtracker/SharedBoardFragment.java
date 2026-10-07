package com.keziah.spiritualtracker;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
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
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SharedBoardFragment extends Fragment {

    private RecyclerView rvPrayerBoard;
    private PrayerBoardAdapter adapter;
    private List<PrayerRequest> requestList;
    private ExtendedFloatingActionButton fabAddRequest;
    private LinearLayout layoutJointListItems;
    private TextView btnViewVault, tvJointListTitle;

    private FirebaseFirestore db;
    private String currentUserId;
    private String partnerId = null;
    private ListenerRegistration boardListener;
    private ListenerRegistration jointListListener;
    private String myName = UserProfile.FALLBACK_NAME;

    public SharedBoardFragment() {}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_shared_board, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        db = FirebaseFirestore.getInstance();
        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            currentUserId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        }

        rvPrayerBoard        = view.findViewById(R.id.rvPrayerBoard);
        fabAddRequest        = view.findViewById(R.id.fabAddRequest);
        layoutJointListItems = view.findViewById(R.id.layoutJointListItems);
        btnViewVault         = view.findViewById(R.id.btnViewVault);
        tvJointListTitle     = view.findViewById(R.id.tvJointListTitle);

        // Live day label
        String dayOfWeek = new java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault())
                .format(new java.util.Date()).toUpperCase();
        if (tvJointListTitle != null) tvJointListTitle.setText(dayOfWeek);

        // Clear all wired through btnClearJointList in layout
        View btnClear = view.findViewById(R.id.btnClearJointList);
        if (btnClear != null) btnClear.setOnClickListener(v -> showClearConfirmation());

        View btnAdd = view.findViewById(R.id.btnAddJointItem);
        if (btnAdd != null) btnAdd.setOnClickListener(v -> showAddJointItemDialog());

        requestList = new ArrayList<>();
        adapter = new PrayerBoardAdapter(requestList, false, new PrayerBoardAdapter.OnRequestActionListener() {
            @Override public void onMarkAnswered(PrayerRequest request) { showAnsweredCommentDialog(request); }
            @Override public void onItemClicked(PrayerRequest request)  { showFullRequestDialog(request); }
            @Override public void onItemLongClicked(PrayerRequest request) { showDeleteRequestDialog(request); }
        });

        rvPrayerBoard.setLayoutManager(new LinearLayoutManager(getContext()));
        rvPrayerBoard.setAdapter(adapter);

        fabAddRequest.setOnClickListener(v -> showAddRequestDialog());

        if (btnViewVault != null) {
            btnViewVault.setOnClickListener(v -> {
                Intent intent = new Intent(getContext(), PrayerVaultActivity.class);
                intent.putExtra("partnerId", partnerId);
                startActivity(intent);
            });
        }

        fetchPartnerId();
    }

    // ─── Permission Checks ─────────────────────────────────────

    /**
     * Check if the current user is the author of the prayer request
     */
    private boolean isUserAuthor(PrayerRequest request) {
        return currentUserId != null && currentUserId.equals(request.getAuthorId());
    }

    // ─── Dialogs ───────────────────────────────────────────────

    private void showAddRequestDialog() {
        View dialogView = LayoutInflater.from(requireActivity())
                .inflate(R.layout.dialog_add_prayer_request, null);
        TextInputEditText etTitle = dialogView.findViewById(R.id.etRequestTitle);
        TextInputEditText etDesc  = dialogView.findViewById(R.id.etRequestDescription);

        new MaterialAlertDialogBuilder(getContext(), R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setView(dialogView)
                .setPositiveButton("Post Request", (d, w) -> {
                    String title = etTitle.getText() != null ? etTitle.getText().toString().trim() : "";
                    String desc  = etDesc.getText()  != null ? etDesc.getText().toString().trim()  : "";
                    if (!title.isEmpty()) saveRequestToFirebase(title, desc);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAnsweredCommentDialog(PrayerRequest request) {
        // Permission check: only author can mark as answered
        if (!isUserAuthor(request)) {
            Toast.makeText(getContext(), "Only the prayer author can mark this as answered", Toast.LENGTH_SHORT).show();
            return;
        }

        View dialogView = LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_save_to_vault, null);
        TextView tvTitle     = dialogView.findViewById(R.id.tvDialogRequestTitle);
        TextInputEditText et = dialogView.findViewById(R.id.etTestimony);

        if (tvTitle != null) tvTitle.setText(request.getTitle());

        new MaterialAlertDialogBuilder(getContext(), R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setView(dialogView)
                .setPositiveButton("Save to Vault", (d, w) -> {
                    String comment = et.getText() != null ? et.getText().toString().trim() : "";
                    markRequestAsAnswered(request, comment);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAddJointItemDialog() {
        View dialogView = LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_add_focus_item, null);
        TextInputEditText et = dialogView.findViewById(R.id.etFocusItem);

        new MaterialAlertDialogBuilder(getContext(), R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setView(dialogView)
                .setPositiveButton("Add", (d, w) -> {
                    String item = et.getText() != null ? et.getText().toString().trim() : "";
                    if (!item.isEmpty()) saveJointItem(item);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showFullRequestDialog(PrayerRequest request) {
        String desc = (request.getDescription() != null && !request.getDescription().isEmpty())
                ? request.getDescription() : "No details added.";

        boolean isAuthor = isUserAuthor(request);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(getContext(), R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setTitle(request.getTitle())
                .setMessage(desc);

        // Only show "Mark as Answered" button if user is the author
        if (isAuthor) {
            builder.setPositiveButton("Mark as Answered ✨", (d, w) -> showAnsweredCommentDialog(request));
        }

        // Only show "Delete" button if user is the author
        if (isAuthor) {
            builder.setNeutralButton("Delete", (d, w) -> showDeleteRequestDialog(request));
        }

        builder.setNegativeButton("Close", null)
                .show();
    }

    private void showDeleteRequestDialog(PrayerRequest request) {
        // Permission check: only author can delete
        if (!isUserAuthor(request)) {
            Toast.makeText(getContext(), "You can only delete your own prayer requests", Toast.LENGTH_SHORT).show();
            return;
        }

        new MaterialAlertDialogBuilder(getContext(), R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setTitle("Remove Prayer Request?")
                .setMessage("\"" + request.getTitle() + "\" will be permanently removed from the board.")
                .setPositiveButton("Delete", (d, w) ->
                        db.collection("shared_prayers").document(request.getRequestId()).delete())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showClearConfirmation() {
        new MaterialAlertDialogBuilder(getContext(), R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setTitle("Clear Focus Items?")
                .setMessage("All focus items will be removed from the prayer list.")
                .setPositiveButton("Clear All", (d, w) -> clearAllJointItems())
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ─── Focus list UI ──────────────────────────────────────────

    private void addItemToLayout(String id, String title) {
        int dp10 = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10, getResources().getDisplayMetrics());
        int dp8  = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8,  getResources().getDisplayMetrics());

        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp10, 0, dp10);

        android.view.View dot = new android.view.View(getContext());
        int dotSizePx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, getResources().getDisplayMetrics());
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dotSizePx, dotSizePx);
        dotParams.setMarginEnd(dp10);
        dot.setLayoutParams(dotParams);
        android.graphics.drawable.GradientDrawable dotShape = new android.graphics.drawable.GradientDrawable();
        dotShape.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dotShape.setColor(Color.parseColor("#7E57C2"));
        dot.setBackground(dotShape);

        TextView tv = new TextView(getContext());
        tv.setText(title);
        tv.setTextColor(Color.parseColor("#1A1A1A"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        row.addView(dot);
        row.addView(tv);

        android.view.View divider = new android.view.View(getContext());
        divider.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        divider.setBackgroundColor(Color.parseColor("#12000000"));

        LinearLayout wrapper = new LinearLayout(getContext());
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(row);
        wrapper.addView(divider);

        wrapper.setOnLongClickListener(v -> {
            new MaterialAlertDialogBuilder(getContext(), R.style.ThemeOverlay_App_MaterialAlertDialog)
                    .setTitle("Remove Item?")
                    .setMessage("Remove \"" + title + "\" from your focus list?")
                    .setPositiveButton("Remove", (dialog, which) ->
                            db.collection("partnerships").document(getPartnershipId())
                                    .collection("joint_list").document(id).delete())
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        });

        layoutJointListItems.addView(wrapper);
    }

    // ─── Firestore ───────────────────────────────────────────────

    private void fetchPartnerId() {
        if (currentUserId == null) return;
        db.collection("users").document(currentUserId).get().addOnSuccessListener(doc -> {
            partnerId = doc.getString("partnerId");
            if (partnerId != null && partnerId.isEmpty()) partnerId = null;
            myName = UserProfile.displayName(doc);
            if (!isAdded()) return;
            listenToPrayerBoard();
            listenToJointList();
        });
    }

    private String getPartnershipId() {
        if (partnerId == null || partnerId.isEmpty()) return null;
        return currentUserId.compareTo(partnerId) < 0
                ? currentUserId + "_" + partnerId
                : partnerId + "_" + currentUserId;
    }

    private void listenToJointList() {
        String partnershipId = getPartnershipId();
        if (partnershipId == null) return;

        jointListListener = db.collection("partnerships").document(partnershipId)
                .collection("joint_list")
                .orderBy("timestamp", Query.Direction.ASCENDING)
                .addSnapshotListener((value, error) -> {
                    if (error != null || value == null) return;
                    layoutJointListItems.removeAllViews();
                    for (com.google.firebase.firestore.DocumentSnapshot doc : value.getDocuments()) {
                        String title = doc.getString("title");
                        if (title != null) addItemToLayout(doc.getId(), title);
                    }
                });
    }

    private void listenToPrayerBoard() {
        if (currentUserId == null) return;
        if (boardListener != null) boardListener.remove();
        // Only ask Firestore for our two authors' requests instead of downloading everyone's
        // and filtering on the phone. Sorted here so no composite index is needed.
        List<String> authors = new ArrayList<>();
        authors.add(currentUserId);
        if (partnerId != null) authors.add(partnerId);
        boardListener = db.collection("shared_prayers")
                .whereIn("authorId", authors)
                .addSnapshotListener((value, error) -> {
                    if (error != null || value == null) return;
                    requestList.clear();
                    for (com.google.firebase.firestore.DocumentSnapshot doc : value.getDocuments()) {
                        PrayerRequest request = doc.toObject(PrayerRequest.class);
                        if (request == null || Boolean.TRUE.equals(doc.getBoolean("isAnswered"))) continue;
                        request.setRequestId(doc.getId());
                        requestList.add(request);
                    }
                    java.util.Collections.sort(requestList, (a, b) -> {
                        long ta = a.getTimestamp() != null ? a.getTimestamp().toDate().getTime() : Long.MAX_VALUE;
                        long tb = b.getTimestamp() != null ? b.getTimestamp().toDate().getTime() : Long.MAX_VALUE;
                        return Long.compare(tb, ta);
                    });
                    adapter.notifyDataSetChanged();
                });
    }

    private void saveJointItem(String title) {
        String pId = getPartnershipId();
        if (pId == null) return;
        Map<String, Object> data = new HashMap<>();
        data.put("title", title);
        data.put("isChecked", false);
        data.put("timestamp", FieldValue.serverTimestamp());
        db.collection("partnerships").document(pId).collection("joint_list").add(data);
        incrementPartnerUnread("unreadPrayer");
        notifyPartner("New Focus Item ✨", myName + " added a new focus item", "prayer_update");
    }

    private void clearAllJointItems() {
        String pId = getPartnershipId();
        if (pId == null) return;
        db.collection("partnerships").document(pId).collection("joint_list").get()
                .addOnSuccessListener(qs -> { for (com.google.firebase.firestore.DocumentSnapshot doc : qs) doc.getReference().delete(); });
    }

    private void markRequestAsAnswered(PrayerRequest request, String comment) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("isAnswered", true);
        updates.put("answeredComment", comment);
        db.collection("shared_prayers").document(request.getRequestId()).update(updates)
                .addOnSuccessListener(v -> {
                    if (getContext() != null) Toast.makeText(getContext(), "Moved to Vault!", Toast.LENGTH_SHORT).show();
                });
        incrementPartnerUnread("unreadPrayer");
        notifyPartner("Prayer Answered! 🎉", myName + " marked a prayer as answered", "prayer_update");
    }

    private void saveRequestToFirebase(String title, String desc) {
        db.collection("users").document(currentUserId).get().addOnSuccessListener(doc -> {
            myName = UserProfile.displayName(doc);
            DocumentReference ref = db.collection("shared_prayers").document();
            PrayerRequest pr = new PrayerRequest(ref.getId(), title, desc, currentUserId, myName,
                    new Timestamp(new Date()), false);
            ref.set(pr).addOnSuccessListener(v -> {
                if (getContext() != null) Toast.makeText(getContext(), "Request Posted!", Toast.LENGTH_SHORT).show();
            });
            incrementPartnerUnread("unreadPrayer");
            notifyPartner("New Prayer Request 🙏", myName + " posted a new prayer request", "prayer_update");
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (boardListener    != null) boardListener.remove();
        if (jointListListener != null) jointListListener.remove();
    }

    private void incrementPartnerUnread(String field) {
        PartnerNotifier.incrementUnread(partnerId, field);
    }

    private void notifyPartner(String title, String body, String type) {
        PartnerNotifier.notifyPartner(partnerId, title, body, type, currentUserId, BuildConfig.PIPEDREAM_URL);
    }
}
