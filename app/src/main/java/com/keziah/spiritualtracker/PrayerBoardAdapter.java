package com.keziah.spiritualtracker;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

public class PrayerBoardAdapter extends RecyclerView.Adapter<PrayerBoardAdapter.PrayerBoardViewHolder> {

    private final List<PrayerRequest> requestList;
    private final OnRequestActionListener actionListener;
    private final boolean isVaultView;
    private final String currentUserId;

    public interface OnRequestActionListener {
        void onMarkAnswered(PrayerRequest request);
        void onItemClicked(PrayerRequest request);
        void onItemLongClicked(PrayerRequest request);
    }

    public PrayerBoardAdapter(List<PrayerRequest> requestList, boolean isVaultView, OnRequestActionListener actionListener) {
        this.requestList = requestList;
        this.isVaultView = isVaultView;
        this.actionListener = actionListener;

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        this.currentUserId = user != null ? user.getUid() : null;
    }

    @NonNull
    @Override
    public PrayerBoardViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layoutId = isVaultView ? R.layout.item_vault_entry : R.layout.item_prayer_request;
        View view = LayoutInflater.from(parent.getContext()).inflate(layoutId, parent, false);
        SpringPress.on(view, 0.97f);
        return new PrayerBoardViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PrayerBoardViewHolder holder, int position) {
        PrayerRequest request = requestList.get(position);

        holder.tvTitle.setText(request.getTitle());
        holder.tvDescription.setText(request.getDescription());

        boolean isMyPost = currentUserId != null && currentUserId.equals(request.getAuthorId());
        android.content.Context ctx = holder.itemView.getContext();
        String displayName = isMyPost ? "Me" : (PartnerName.isKnown(ctx) ? PartnerName.get(ctx)
                : (request.getAuthorName() != null ? request.getAuthorName() : "Partner"));
        String authorLine = "By " + displayName;

        if (request.getTimestamp() != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());
            authorLine += " · " + sdf.format(request.getTimestamp().toDate());
        }
        holder.tvAuthor.setText(authorLine);

        // Weekly reminder bell, on the board only (answered requests have left it).
        if (holder.bell != null) {
            boolean remindable = !isVaultView && !request.isAnswered() && request.getRequestId() != null;
            holder.bell.setVisibility(remindable ? View.VISIBLE : View.GONE);
            if (holder.reminderDay != null && !remindable) holder.reminderDay.setVisibility(View.GONE);
            if (remindable) PrayerReminders.bind(holder.bell, holder.reminderDay,
                    PrayerReminders.SHARED + request.getRequestId(), request.getTimestamp());
        }

        // ─── PERMISSION CHECK: Only show answered badge if user is the author ───
        if (isVaultView) {
            if (holder.badgeContainer != null) {
                holder.badgeContainer.setVisibility(View.VISIBLE);
                holder.badgeContainer.setOnClickListener(null);
            }
        } else {
            if (holder.badgeContainer != null) {
                // Only enable if this is the user's own post
                if (isMyPost) {
                    holder.badgeContainer.setVisibility(View.VISIBLE);
                    holder.badgeContainer.setEnabled(true);
                    holder.badgeContainer.setAlpha(1.0f);
                    holder.badgeContainer.setOnClickListener(v ->
                            v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).withEndAction(() ->
                                    v.animate().scaleX(1f).scaleY(1f).setDuration(100).withEndAction(() -> {
                                        if (actionListener != null) actionListener.onMarkAnswered(request);
                                    })
                            )
                    );
                } else {
                    // Hide or disable for other users' posts
                    holder.badgeContainer.setVisibility(View.GONE);
                    holder.badgeContainer.setEnabled(false);
                    holder.badgeContainer.setOnClickListener(null);
                }
            }
        }

        holder.itemView.setOnClickListener(v -> {
            if (actionListener != null) actionListener.onItemClicked(request);
        });

        holder.itemView.setOnLongClickListener(v -> {
            // Only allow delete if it's the user's own post
            if (actionListener != null && !isVaultView && isMyPost) {
                actionListener.onItemLongClicked(request);
                return true;
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return requestList.size();
    }

    public static class PrayerBoardViewHolder extends RecyclerView.ViewHolder {
        TextView tvTitle, tvDescription, tvAuthor, reminderDay;
        View badgeContainer;
        android.widget.ImageView bell;

        public PrayerBoardViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tvRequestTitle);
            tvDescription = itemView.findViewById(R.id.tvRequestDescription);
            tvAuthor = itemView.findViewById(R.id.tvRequestAuthor);
            badgeContainer = itemView.findViewById(R.id.answeredBadge);
            bell = itemView.findViewById(R.id.requestReminderBell);
            reminderDay = itemView.findViewById(R.id.requestReminderDay);
        }
    }
}