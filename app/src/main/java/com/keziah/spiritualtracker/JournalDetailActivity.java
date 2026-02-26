package com.keziah.spiritualtracker;

import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import java.io.IOException;

public class JournalDetailActivity extends AppCompatActivity {

    private TextView tvTitle, tvContent, tvDate;
    private LinearLayout audioContainer;
    private ImageButton btnPlayAudio;
    private FloatingActionButton fabEdit; // Added for editing

    private MediaPlayer mediaPlayer;
    private boolean isPlaying = false;
    private String audioUrl;
    private String docId; // Added to track the document ID

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_journal_detail);

        // 1. Initialize Views
        tvTitle = findViewById(R.id.tvDetailTitle);
        tvContent = findViewById(R.id.tvDetailContent);
        tvDate = findViewById(R.id.tvDetailDate);
        audioContainer = findViewById(R.id.audioPlayerContainer);
        btnPlayAudio = findViewById(R.id.btnPlayEntryAudio);
        fabEdit = findViewById(R.id.fabEditEntry); // Initialize the new FAB

        // 2. Get Data from Intent
        String title = getIntent().getStringExtra("title");
        String content = getIntent().getStringExtra("content");
        String date = getIntent().getStringExtra("date");
        audioUrl = getIntent().getStringExtra("audioUrl");
        docId = getIntent().getStringExtra("docId"); // Crucial for editing!

        // 3. Set Text
        tvTitle.setText(title);
        tvContent.setText(content);
        tvDate.setText(date);

        // 4. Setup Audio Player (if link exists)
        if (audioUrl != null && !audioUrl.isEmpty()) {
            audioContainer.setVisibility(View.VISIBLE);
            btnPlayAudio.setOnClickListener(v -> {
                if (isPlaying) {
                    stopAudio();
                } else {
                    playAudio(audioUrl);
                }
            });
        } else {
            audioContainer.setVisibility(View.GONE);
        }

        // 5. Setup Edit Button
        fabEdit.setOnClickListener(v -> {
            // Send the data back to JournalActivity for editing
            Intent intent = new Intent(JournalDetailActivity.this, JournalActivity.class);
            intent.putExtra("docId", docId);
            intent.putExtra("title", title);
            intent.putExtra("content", content);
            intent.putExtra("audioUrl", audioUrl);
            intent.putExtra("originalDate", date);
            startActivity(intent);

            // Finish this activity so the user doesn't see "stale" data
            // after they finish editing
            finish();
        });
    }

    private void playAudio(String url) {
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(url);
            mediaPlayer.prepareAsync();

            btnPlayAudio.setEnabled(false);
            Toast.makeText(this, "Loading Audio...", Toast.LENGTH_SHORT).show();

            mediaPlayer.setOnPreparedListener(mp -> {
                mp.start();
                isPlaying = true;
                btnPlayAudio.setEnabled(true);
                btnPlayAudio.setImageResource(android.R.drawable.ic_media_pause);
            });

            mediaPlayer.setOnCompletionListener(mp -> stopAudio());

        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "Error playing audio", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopAudio() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        isPlaying = false;
        btnPlayAudio.setImageResource(android.R.drawable.ic_media_play);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }
}