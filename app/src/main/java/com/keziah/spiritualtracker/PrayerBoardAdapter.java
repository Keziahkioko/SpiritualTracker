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
        return new PrayerBoardViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PrayerBoardViewHolder holder, int position) {
        PrayerRequest request = requestList.get(position);

        holder.tvTitle.setText(request.getTitle());
        holder.tvDescription.setText(request.getDescription());

        boolean isMyPost = currentUserId != null && currentUserId.equals(request.getAuthorId());
        String displayName = isMyPost ? "Me" : (request.getAuthorName() != null ? request.getAuthorName() : "Partner");
        String authorLine = "By " + displayName;

        if (request.getTimestamp() != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());
            authorLine += " · " + sdf.format(request.getTimestamp().toDate());
        }
        holder.tvAuthor.setText(authorLine);

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
        TextView tvTitle, tvDescription, tvAuthor;
        View badgeContainer;

        public PrayerBoardViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tvRequestTitle);
            tvDescription = itemView.findViewById(R.id.tvRequestDescription);
            tvAuthor = itemView.findViewById(R.id.tvRequestAuthor);
            badgeContainer = itemView.findViewById(R.id.answeredBadge);
        }
    }
}