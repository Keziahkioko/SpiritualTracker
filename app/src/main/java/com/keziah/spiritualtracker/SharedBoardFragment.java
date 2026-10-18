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
    private TextView tvJointCount, tvJointEmpty;
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
        PartnerName.apply(view);

        db = FirebaseFirestore.getInstance();
        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            currentUserId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        }

        rvPrayerBoard        = view.findViewById(R.id.rvPrayerBoard);
        fabAddRequest        = view.findViewById(R.id.fabAddRequest);
        layoutJointListItems = view.findViewById(R.id.layoutJointListItems);
        btnViewVault         = view.findViewById(R.id.btnViewVault);
        tvJointListTitle     = view.findViewById(R.id.tvJointListTitle);
        StatusBarUtil.padTop(view.findViewById(R.id.boardHeader));

        // Live day label
        String dayOfWeek = new java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault())
                .format(new java.util.Date()).toUpperCase();
        if (tvJointListTitle != null) tvJointListTitle.setText(dayOfWeek);

        // "Clear all" lives in the small ⋮ menu, out of the way of the list.
        View btnClear = view.findViewById(R.id.btnClearJointList);
        if (btnClear != null) btnClear.setOnClickListener(v -> {
            android.widget.PopupMenu menu = new android.widget.PopupMenu(requireContext(), v);
            menu.getMenu().add("Clear all");
            menu.setOnMenuItemClickListener(item -> { showClearConfirmation(); return true; });
            menu.show();
        });
        tvJointCount = view.findViewById(R.id.tvJointCount);
        tvJointEmpty = view.findViewById(R.id.tvJointEmpty);

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
        PartnerName.apply(dialogView);
        TextInputEditText etTitle = dialogView.findViewById(R.id.etRequestTitle);
        TextInputEditText etDesc  = dialogView.findViewById(R.id.etRequestDescription);
        PrayerReminders.DayPicker day = PrayerReminders.addPickerTo(dialogView);

        new MaterialAlertDialogBuilder(getContext(), R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setView(dialogView)
                .setPositiveButton("Post Request", (d, w) -> {
                    String title = etTitle.getText() != null ? etTitle.getText().toString().trim() : "";
                    String desc  = etDesc.getText()  != null ? etDesc.getText().toString().trim()  : "";
                    if (!title.isEmpty()) saveRequestToFirebase(title, desc, day.days());
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
        PrayerReminders.DayPicker day = PrayerReminders.addPickerTo(dialogView);

        new MaterialAlertDialogBuilder(getContext(), R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setView(dialogView)
                .setPositiveButton("Add", (d, w) -> {
                    String item = et.getText() != null ? et.getText().toString().trim() : "";
                    if (!item.isEmpty()) saveJointItem(item, day.days());
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
            builder.setPositiveButton("Answered ✨", (d, w) -> showAnsweredCommentDialog(request));
        }

        // Only show "Delete" button if user is the author
        if (isAuthor) {
            builder.setNeutralButton("Delete", (d, w) -> showDeleteRequestDialog(request));
        }

        androidx.appcompat.app.AlertDialog dialog = builder.setNegativeButton("Close", null).create();
        dialog.show();
        android.widget.Button delete = dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL);
        if (delete != null) delete.setTextColor(Color.parseColor("#C62828"));
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

    /**
     * One focus item as a soft lavender tile: up to two lines (a long note shows its first line in
     * bold and a one-line preview), with its reminder days and bell at the bottom right. Tap to
     * read it in full; long-press to remove.
     */
    private void addItemToLayout(String id, String title, com.google.firebase.Timestamp posted) {
        android.content.Context ctx = requireContext();
        int dp4 = PrayerReminders.dp(ctx, 4), dp8 = PrayerReminders.dp(ctx, 8);

        LinearLayout tile = new LinearLayout(ctx);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(PrayerReminders.dp(ctx, 14), PrayerReminders.dp(ctx, 12), PrayerReminders.dp(ctx, 10), dp4);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.parseColor("#F6F2FB"));
        bg.setCornerRadius(PrayerReminders.dp(ctx, 14));
        tile.setBackground(bg);
        android.util.TypedValue ripple = new android.util.TypedValue();
        ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        tile.setForeground(androidx.core.content.ContextCompat.getDrawable(ctx, ripple.resourceId));
        LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tileParams.bottomMargin = dp8;
        tile.setLayoutParams(tileParams);

        // A note of several lines: its first line as the heading, the rest as a short preview.
        List<String> lines = new ArrayList<>();
        for (String l : title.split("\n")) if (!l.trim().isEmpty()) lines.add(l.replaceAll("\\s+", " ").trim());
        String heading = lines.isEmpty() ? title.trim() : lines.get(0);
        String preview = lines.size() > 1 ? String.join(" ", lines.subList(1, lines.size())) : null;

        TextView tvHeading = new TextView(ctx);
        tvHeading.setText(heading);
        tvHeading.setTextColor(Color.parseColor("#1A1A1A"));
        tvHeading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvHeading.setTypeface(Typeface.create("sans-serif-medium", preview != null ? Typeface.BOLD : Typeface.NORMAL));
        tvHeading.setMaxLines(preview != null ? 1 : 2);
        tvHeading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvHeading.setLineSpacing(PrayerReminders.dp(ctx, 2), 1f);
        tile.addView(tvHeading);

        if (preview != null) {
            TextView tvPreview = new TextView(ctx);
            tvPreview.setText(preview);
            tvPreview.setTextColor(Color.parseColor("#5F5F64"));
            tvPreview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tvPreview.setMaxLines(1);
            tvPreview.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            pp.topMargin = PrayerReminders.dp(ctx, 2);
            tile.addView(tvPreview, pp);
        }

        // Reminder days and bell, tucked bottom right.
        LinearLayout footer = new LinearLayout(ctx);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
        TextView day = new TextView(ctx);
        day.setTextColor(Color.parseColor("#7E57C2"));
        day.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        day.setTypeface(Typeface.DEFAULT_BOLD);
        day.setPadding(dp8, dp4, dp4, dp4);
        android.widget.ImageView bell = new android.widget.ImageView(ctx);
        int bellSize = PrayerReminders.dp(ctx, 32), bellPad = PrayerReminders.dp(ctx, 6);
        bell.setLayoutParams(new LinearLayout.LayoutParams(bellSize, bellSize));
        bell.setPadding(bellPad, bellPad, bellPad, bellPad);
        android.util.TypedValue round = new android.util.TypedValue();
        ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, round, true);
        bell.setBackgroundResource(round.resourceId);
        footer.addView(day);
        footer.addView(bell);
        tile.addView(footer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        PrayerReminders.bind(bell, day, PrayerReminders.JOINT + id, posted);

        tile.setOnClickListener(v -> showFocusItem(id, title));
        tile.setOnLongClickListener(v -> { confirmRemoveFocusItem(id, heading); return true; });
        layoutJointListItems.addView(tile);
    }

    /** The whole focus item, however long, with Remove to hand. */
    private void showFocusItem(String id, String title) {
        androidx.appcompat.app.AlertDialog dialog =
                new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_App_MaterialAlertDialog)
                        .setTitle("Prayer focus")
                        .setMessage(title.trim())
                        .setPositiveButton("Close", null)
                        .setNeutralButton("Remove", (d, w) -> confirmRemoveFocusItem(id,
                                PrayerReminderWorker.shorten(title)))
                        .show();
        android.widget.Button remove = dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL);
        if (remove != null) remove.setTextColor(Color.parseColor("#C62828"));
    }

    private void confirmRemoveFocusItem(String id, String heading) {
        new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_App_MaterialAlertDialog)
                .setTitle("Remove item?")
                .setMessage("Remove \"" + heading + "\" from your focus list?")
                .setPositiveButton("Remove", (dialog, which) ->
                        db.collection("partnerships").document(getPartnershipId())
                                .collection("joint_list").document(id).delete())
                .setNegativeButton("Cancel", null)
                .show();
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
                    if (!isAdded()) return;
                    layoutJointListItems.removeAllViews();
                    int count = 0;
                    for (com.google.firebase.firestore.DocumentSnapshot doc : value.getDocuments()) {
                        String title = doc.getString("title");
                        if (title == null || title.trim().isEmpty()) continue;
                        addItemToLayout(doc.getId(), title, doc.getTimestamp("timestamp"));
                        count++;
                    }
                    if (tvJointCount != null) {
                        tvJointCount.setText(count == 1 ? "1 item" : count + " items");
                        tvJointCount.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
                    }
                    if (tvJointEmpty != null) tvJointEmpty.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
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

    private void saveJointItem(String title, java.util.Set<Integer> reminderDays) {
        String pId = getPartnershipId();
        if (pId == null) return;
        Map<String, Object> data = new HashMap<>();
        data.put("title", title);
        data.put("isChecked", false);
        data.put("timestamp", FieldValue.serverTimestamp());
        DocumentReference ref = db.collection("partnerships").document(pId).collection("joint_list").document();
        PrayerReminders.setDays(requireContext(), PrayerReminders.JOINT + ref.getId(), reminderDays);
        ref.set(data);
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

    private void saveRequestToFirebase(String title, String desc, java.util.Set<Integer> reminderDays) {
        android.content.Context context = requireContext().getApplicationContext();
        db.collection("users").document(currentUserId).get().addOnSuccessListener(doc -> {
            myName = UserProfile.displayName(doc);
            DocumentReference ref = db.collection("shared_prayers").document();
            PrayerReminders.setDays(context, PrayerReminders.SHARED + ref.getId(), reminderDays);
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
        PartnerNotifier.notifyPartner(partnerId, title, body, type, currentUserId);
    }
}
