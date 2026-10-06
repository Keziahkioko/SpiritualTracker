package com.keziah.spiritualtracker;

import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class JournalAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ENTRY  = 1;

    // Internal list item — either a header string or a JournalEntry
    private static class ListItem {
        final String      header; // non-null = header row
        final JournalEntry entry; // non-null = entry row
        ListItem(String h)       { header = h; entry = null; }
        ListItem(JournalEntry e) { header = null; entry = e; }
        boolean isHeader() { return header != null; }
    }

    private final Context context;
    private final String  currentUid;
    private List<JournalEntry> sourceList = new ArrayList<>();
    private List<ListItem>     displayList = new ArrayList<>();

    private MediaPlayer mediaPlayer;
    private int playingPosition = -1;

    public JournalAdapter(Context context, List<JournalEntry> list) {
        this.context     = context;
        this.sourceList  = list;
        String uid = "";
        if (FirebaseAuth.getInstance().getCurrentUser() != null)
            uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        this.currentUid  = uid;
        buildDisplayList();
    }

    // Called by Activity whenever data or filters change
    public void setFilteredList(List<JournalEntry> filtered) {
        this.sourceList = filtered;
        buildDisplayList();
        notifyDataSetChanged();
    }

    // Called by swipe-to-delete - return the entry (or null if it's a header)
    public JournalEntry getItem(int position) {
        ListItem item = displayList.get(position);
        return item.isHeader() ? null : item.entry;
    }

    /**
     * Check if current user is the author of an entry
     * Used by swipe-to-delete to verify permission before deleting
     */
    public boolean canDeleteEntry(int position) {
        JournalEntry entry = getItem(position);
        if (entry == null) return false; // Can't delete headers
        return entry.getUserId() != null && entry.getUserId().equals(currentUid);
    }

    // ── Build grouped display list ────────────────────────────────────────

    private void buildDisplayList() {
        displayList = new ArrayList<>();
        if (sourceList == null || sourceList.isEmpty()) return;

        String lastGroup = null;
        for (JournalEntry e : sourceList) {
            String group = getGroupLabel(e);
            if (!group.equals(lastGroup)) {
                displayList.add(new ListItem(group));
                lastGroup = group;
            }
            displayList.add(new ListItem(e));
        }
    }

    private String getGroupLabel(JournalEntry e) {
        if (e.getDate() == null) return "Earlier";
        Calendar entryDay = Calendar.getInstance();
        entryDay.setTime(e.getDate().toDate());

        Calendar today = Calendar.getInstance();
        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);

        if (isSameDay(entryDay, today))     return "Today";
        if (isSameDay(entryDay, yesterday)) return "Yesterday";

        Calendar weekAgo = Calendar.getInstance();
        weekAgo.add(Calendar.DAY_OF_YEAR, -7);
        if (entryDay.after(weekAgo)) return "This week";

        return "Earlier";
    }

    private boolean isSameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR)       == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    // ── RecyclerView ──────────────────────────────────────────────────────

    @Override public int getItemViewType(int position) {
        return displayList.get(position).isHeader() ? TYPE_HEADER : TYPE_ENTRY;
    }

    @Override public int getItemCount() { return displayList.size(); }

    @NonNull @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(context);
        if (viewType == TYPE_HEADER) {
            View v = inf.inflate(R.layout.item_journal_header, parent, false);
            return new HeaderVH(v);
        }
        View v = inf.inflate(R.layout.item_journal_entry, parent, false);
        return new EntryVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (getItemViewType(position) == TYPE_HEADER) {
            ((HeaderVH) holder).tvLabel.setText(displayList.get(position).header);
            return;
        }
        bindEntry((EntryVH) holder, displayList.get(position).entry, position);
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof EntryVH) {
            clearReplyListener((EntryVH) holder);
        }
        super.onViewRecycled(holder);
    }

    /** Stops listening so rows don't stack listeners or update the wrong card after scroll. */
    private void clearReplyListener(EntryVH h) {
        if (h.repliesListener != null) {
            h.repliesListener.remove();
            h.repliesListener = null;
        }
        h.boundDocIdForReplies = null;
    }

    /** Call from Activity.onDestroy so visible rows don't keep Firestore listeners. */
    public void detachVisibleReplyListeners(@NonNull RecyclerView rv) {
        for (int i = 0; i < rv.getChildCount(); i++) {
            RecyclerView.ViewHolder vh = rv.getChildViewHolder(rv.getChildAt(i));
            if (vh instanceof EntryVH) {
                clearReplyListener((EntryVH) vh);
            }
        }
    }

    private void bindEntry(EntryVH h, JournalEntry entry, int position) {
        clearReplyListener(h);

        h.tvTitle.setText(entry.getTitle());

        // Time (short h:mm a)
        if (entry.getDate() != null) {
            h.tvTime.setText(DateFormat.format("h:mm a", entry.getDate().toDate()));
            h.tvDate.setText(DateFormat.format("MMM d, yyyy", entry.getDate().toDate()));
        }

        // Author pill
        boolean isMe = entry.getUserId() != null && entry.getUserId().equals(currentUid);
        if (isMe) {
            h.tvAuthor.setText("Me");
            h.tvAuthor.setBackgroundResource(R.drawable.pill_me);
            h.tvAuthor.setTextColor(0xFF5B21B6);
        } else {
            String name = entry.getAuthorName() != null ? entry.getAuthorName() : "Partner";
            h.tvAuthor.setText(name);
            h.tvAuthor.setBackgroundResource(R.drawable.pill_partner);
            h.tvAuthor.setTextColor(0xFF166534);
        }

        // Status pill (Draft / NEW)
        if ("draft".equals(entry.getStatus())) {
            h.tvStatus.setVisibility(View.VISIBLE);
            h.tvStatus.setText("Draft");
            h.tvStatus.setBackgroundResource(R.drawable.pill_draft);
            h.tvStatus.setTextColor(0xFFBF6000);
        } else if (!entry.isReadByPartner() && !isMe) {
            h.tvStatus.setVisibility(View.VISIBLE);
            h.tvStatus.setText("New");
            h.tvStatus.setBackgroundResource(R.drawable.pill_me);
            h.tvStatus.setTextColor(0xFF5B21B6);
        } else {
            h.tvStatus.setVisibility(View.GONE);
        }

        // Text preview vs audio footer
        String audioUrl = entry.getAudioUrl();
        boolean hasAudio = audioUrl != null && !audioUrl.isEmpty();

        if (hasAudio) {
            h.tvPreview       .setVisibility(View.GONE);
            h.layoutAudioFooter.setVisibility(View.VISIBLE);

            String dur = entry.getAudioDuration();
            h.tvAudioDuration.setText(dur != null ? dur : "");

            h.btnPlayAudio.setImageResource(
                    position == playingPosition
                            ? android.R.drawable.ic_media_pause
                            : android.R.drawable.ic_media_play);

            h.btnPlayAudio.setOnClickListener(v -> {
                if (position == playingPosition) stopAudio();
                else playAudio(audioUrl, position);
            });
        } else {
            h.layoutAudioFooter.setVisibility(View.GONE);
            if (entry.getContent() != null && !entry.getContent().isEmpty()) {
                h.tvPreview.setVisibility(View.VISIBLE);
                h.tvPreview.setText(entry.getContent());
            } else {
                h.tvPreview.setVisibility(View.GONE);
            }
        }

        // Unread reply badge (one listener per row; removed on re-bind / recycle)
        h.tvUnreadBadge.setVisibility(View.GONE);
        if (entry.getDocId() != null && !currentUid.isEmpty()) {
            final String docId = entry.getDocId();
            h.boundDocIdForReplies = docId;
            h.repliesListener = FirebaseFirestore.getInstance()
                    .collection("journals")
                    .document(docId)
                    .collection("replies")
                    .whereNotEqualTo("senderId", currentUid)
                    .addSnapshotListener((value, error) -> {
                        if (error != null || value == null) return;
                        if (!docId.equals(h.boundDocIdForReplies)) return;
                        int unread = 0;
                        for (DocumentSnapshot doc : value.getDocuments()) {
                            if (!"read".equals(doc.getString("status"))) unread++;
                        }
                        if (unread > 0) {
                            h.tvUnreadBadge.setVisibility(View.VISIBLE);
                            h.tvUnreadBadge.setText(String.valueOf(unread));
                        } else {
                            h.tvUnreadBadge.setVisibility(View.GONE);
                        }
                    });
        }

        // Card click
        h.itemView.setOnClickListener(v -> {
            Intent intent = new Intent(context, JournalDetailActivity.class);
            intent.putExtra("title",         entry.getTitle());
            intent.putExtra("content",       entry.getContent());
            intent.putExtra("audioUrl",      entry.getAudioUrl());
            intent.putExtra("audioDuration", entry.getAudioDuration());
            intent.putExtra("docId",         entry.getDocId());
            intent.putExtra("status",        entry.getStatus());
            intent.putExtra("authorName",    entry.getAuthorName());
            intent.putExtra("authorId",      entry.getUserId());
            if (entry.getDate() != null) {
                intent.putExtra("date",
                        DateFormat.format("MMM d, yyyy 'at' h:mm a",
                                entry.getDate().toDate()).toString());
            }
            context.startActivity(intent);
        });
    }

    // ── Audio ─────────────────────────────────────────────────────────────

    private void playAudio(String url, int position) {
        stopAudio();
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(url);
            mediaPlayer.prepareAsync();
            mediaPlayer.setOnPreparedListener(mp -> {
                mp.start();
                playingPosition = position;
                notifyDataSetChanged();
            });
            mediaPlayer.setOnCompletionListener(mp -> stopAudio());
        } catch (IOException e) {
            Toast.makeText(context, "Error playing audio", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopAudio() {
        if (mediaPlayer != null) { mediaPlayer.release(); mediaPlayer = null; }
        playingPosition = -1;
        notifyDataSetChanged();
    }

    // ── View holders ──────────────────────────────────────────────────────

    static class HeaderVH extends RecyclerView.ViewHolder {
        TextView tvLabel;
        HeaderVH(@NonNull View v) {
            super(v);
            tvLabel = v.findViewById(R.id.tvGroupLabel);
        }
    }

    static class EntryVH extends RecyclerView.ViewHolder {
        TextView    tvTitle, tvDate, tvTime, tvPreview, tvAuthor, tvStatus,
                tvAudioDuration, tvUnreadBadge;
        ImageButton btnPlayAudio;
        LinearLayout layoutAudioFooter;
        ListenerRegistration repliesListener;
        String boundDocIdForReplies;

        EntryVH(@NonNull View v) {
            super(v);
            tvTitle          = v.findViewById(R.id.tvTitle);
            tvDate           = v.findViewById(R.id.tvDate);
            tvTime           = v.findViewById(R.id.tvTime);
            tvPreview        = v.findViewById(R.id.tvPreview);
            tvAuthor         = v.findViewById(R.id.tvAuthor);
            tvStatus         = v.findViewById(R.id.tvStatus);
            tvAudioDuration  = v.findViewById(R.id.tvAudioDuration);
            tvUnreadBadge    = v.findViewById(R.id.tvUnreadBadge);
            btnPlayAudio     = v.findViewById(R.id.btnPlayAudio);
            layoutAudioFooter = v.findViewById(R.id.layoutAudioFooter);
        }
    }
}