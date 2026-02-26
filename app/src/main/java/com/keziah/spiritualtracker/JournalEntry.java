package com.keziah.spiritualtracker;

import com.google.firebase.Timestamp;

public class JournalEntry {
    private String title;
    private String content;
    private Timestamp date; // Firebase Format for time
    private String userId;

    // We are adding these NOW so we are ready for your future update!
    private String imageUrl;
    private String audioUrl;
    private String docId;

    // Empty constructor is REQUIRED for Firebase
    public JournalEntry() {}

    // Constructor to create a new entry
    public JournalEntry(String title, String content, Timestamp date, String userId) {
        this.title = title;
        this.content = content;
        this.date = date;
        this.userId = userId;
    }

    // Getters (Java needs these to read the data)
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public Timestamp getDate() { return date; }
    public String getUserId() { return userId; }
    public String getImageUrl() { return imageUrl; }
    public String getAudioUrl() { return audioUrl; }
    public void setAudioUrl(String audioUrl) { this.audioUrl = audioUrl; }
    public String getDocId() { return docId; }
    public void setDocId(String docId) { this.docId = docId; }
}