package com.keziah.spiritualtracker;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

public class VaultAdapter extends RecyclerView.Adapter<VaultAdapter.VH> {

    public interface OnItemClick { void onClick(PrayerRequest r); }
    public interface OnItemLongClick { void onLongClick(PrayerRequest r); }

    private final Context context;
    private List<PrayerRequest> list;
    private final OnItemClick onClick;
    private final OnItemLongClick onLongClick;
    private final String currentUid;

    public VaultAdapter(Context context, List<PrayerRequest> list,
                        OnItemClick onClick, OnItemLongClick onLongClick) {
        this.context     = context;
        this.list        = list;
        this.onClick     = onClick;
        this.onLongClick = onLongClick;
        String uid = "";
        if (FirebaseAuth.getInstance().getCurrentUser() != null)
            uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        this.currentUid = uid;
    }

    public void updateList(List<PrayerRequest> newList) {
        this.list = newList;
        notifyDataSetChanged();
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context)
                .inflate(R.layout.item_vault_entry, parent, false);
        SpringPress.on(v, 0.97f);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        PrayerRequest r = list.get(position);

        // Title
        h.tvTitle.setText(r.getTitle());

        // Meta: "Prayed by me · Apr 2, 2026" or "Prayed by Brian · Apr 2, 2026"
        boolean isMe = currentUid.equals(r.getAuthorId());
        String author = isMe ? "me"
                : (r.getAuthorName() != null && !r.getAuthorName().isEmpty()
                        ? r.getAuthorName() : "Partner");
        if (!isMe && PartnerName.isKnown(context)) author = PartnerName.get(context);
        String date = r.getTimestamp() != null
                ? new SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                        .format(r.getTimestamp().toDate())
                : "";
        h.tvMeta.setText("Prayed by " + author + (date.isEmpty() ? "" : "  ·  " + date));

        // God's answer is the heart of the Vault, so show it; fall back to the request itself.
        String testimony = r.getAnsweredComment();
        String desc = r.getDescription();
        if (testimony != null && !testimony.trim().isEmpty()) {
            h.layoutTestimony.setVisibility(View.VISIBLE);
            h.tvTestimony.setText("\u201c" + testimony.trim() + "\u201d");
            h.tvDescription.setVisibility(View.GONE);
        } else {
            h.layoutTestimony.setVisibility(View.GONE);
            if (desc != null && !desc.isEmpty()) {
                h.tvDescription.setVisibility(View.VISIBLE);
                h.tvDescription.setText(desc);
            } else {
                h.tvDescription.setVisibility(View.GONE);
            }
        }

        // Divider — hide on last item
        h.itemView.setOnClickListener(v -> onClick.onClick(r));
        h.itemView.setOnLongClickListener(v -> { onLongClick.onLongClick(r); return true; });
    }

    @Override public int getItemCount() { return list.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvTitle, tvMeta, tvDescription, tvTestimony;
        View layoutTestimony;

        VH(@NonNull View v) {
            super(v);
            tvTestimony     = v.findViewById(R.id.tvTestimonyPreview);
            layoutTestimony = v.findViewById(R.id.layoutTestimony);
            tvTitle      = v.findViewById(R.id.tvRequestTitle);
            tvMeta       = v.findViewById(R.id.tvRequestMeta);
            tvDescription = v.findViewById(R.id.tvRequestDescription);
        }
    }
}
