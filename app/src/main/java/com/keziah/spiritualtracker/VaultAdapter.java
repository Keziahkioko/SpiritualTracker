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
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        PrayerRequest r = list.get(position);

        // Row number e.g. "01", "02"
        h.tvRowNumber.setText(String.format(Locale.getDefault(), "%02d", position + 1));

        // Title
        h.tvTitle.setText(r.getTitle());

        // Meta: "Me · Apr 2, 2026" or "Partner · Apr 2, 2026"
        boolean isMe = currentUid.equals(r.getAuthorId());
        String author = isMe ? "Me"
                : (r.getAuthorName() != null && !r.getAuthorName().isEmpty()
                        ? r.getAuthorName() : "Partner");
        String date = r.getTimestamp() != null
                ? new SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                        .format(r.getTimestamp().toDate())
                : "";
        h.tvMeta.setText(author + (date.isEmpty() ? "" : " · " + date));

        // Description preview
        String desc = r.getDescription();
        if (desc != null && !desc.isEmpty()) {
            h.tvDescription.setVisibility(View.VISIBLE);
            h.tvDescription.setText(desc);
        } else {
            h.tvDescription.setVisibility(View.GONE);
        }

        // Divider — hide on last item
        h.itemView.setOnClickListener(v -> onClick.onClick(r));
        h.itemView.setOnLongClickListener(v -> { onLongClick.onLongClick(r); return true; });
    }

    @Override public int getItemCount() { return list.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvRowNumber, tvTitle, tvMeta, tvDescription;

        VH(@NonNull View v) {
            super(v);
            tvRowNumber  = v.findViewById(R.id.tvRowNumber);
            tvTitle      = v.findViewById(R.id.tvRequestTitle);
            tvMeta       = v.findViewById(R.id.tvRequestMeta);
            tvDescription = v.findViewById(R.id.tvRequestDescription);
        }
    }
}
