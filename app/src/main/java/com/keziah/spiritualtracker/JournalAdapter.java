package com.keziah.spiritualtracker;

import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.io.IOException;
import java.util.ArrayList; // Added this import just in case
import java.util.List;

public class JournalAdapter extends RecyclerView.Adapter<JournalAdapter.JournalViewHolder> {

    private Context context;
    private List<JournalEntry> entryList;

    // --- Audio Player Tools ---
    private MediaPlayer mediaPlayer;
    private int playingPosition = -1;

    public JournalAdapter(Context context, List<JournalEntry> entryList) {
        this.context = context;
        this.entryList = entryList;
    }

    // --- NEW: METHOD TO UPDATE LIST FOR SEARCH ---
    public void setFilteredList(List<JournalEntry> filteredList) {
        this.entryList = filteredList;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public JournalViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_journal_entry, parent, false);
        return new JournalViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull JournalViewHolder holder, int position) {
        JournalEntry entry = entryList.get(position);

        // 1. Set Text Data
        holder.tvTitle.setText(entry.getTitle());

        if (entry.getContent() != null) {
            holder.tvPreview.setText(entry.getContent());
        }

        if (entry.getDate() != null) {
            CharSequence prettyTime = DateFormat.format("MMM d, yyyy", entry.getDate().toDate());
            holder.tvDate.setText(prettyTime);
        }

        // 2. --- Handle Audio Button ---
        String audioUrl = entry.getAudioUrl();

        if (audioUrl != null && !audioUrl.isEmpty()) {
            holder.btnPlayAudio.setVisibility(View.VISIBLE);

            if (position == playingPosition) {
                holder.btnPlayAudio.setImageResource(android.R.drawable.ic_media_pause);
            } else {
                holder.btnPlayAudio.setImageResource(android.R.drawable.ic_media_play);
            }

            holder.btnPlayAudio.setOnClickListener(v -> {
                if (position == playingPosition) {
                    stopAudio();
                } else {
                    playAudio(audioUrl, position);
                }
            });

        } else {
            holder.btnPlayAudio.setVisibility(View.GONE);
        }

        // 3. Handle Card Click
        holder.itemView.setOnClickListener(v -> {
            Intent intent = new Intent(context, JournalDetailActivity.class);
            intent.putExtra("title", entry.getTitle());
            intent.putExtra("content", entry.getContent());
            intent.putExtra("audioUrl", entry.getAudioUrl());
            intent.putExtra("docId", entry.getDocId());
            if (entry.getDate() != null) {
                String dateStr = DateFormat.format("MMM d, yyyy", entry.getDate().toDate()).toString();
                intent.putExtra("date", dateStr);
            }
            context.startActivity(intent);
        });
    }

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
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        playingPosition = -1;
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return entryList.size();
    }

    public static class JournalViewHolder extends RecyclerView.ViewHolder {
        TextView tvTitle, tvDate, tvPreview;
        ImageButton btnPlayAudio;

        public JournalViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tvTitle);
            tvDate = itemView.findViewById(R.id.tvDate);
            tvPreview = itemView.findViewById(R.id.tvPreview);
            btnPlayAudio = itemView.findViewById(R.id.btnPlayAudio);
        }
    }
}