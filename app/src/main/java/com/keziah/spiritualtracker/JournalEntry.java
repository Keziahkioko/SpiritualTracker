package com.keziah.spiritualtracker;

import com.google.firebase.Timestamp;

public class JournalEntry {
    private String title;
    private String content;
    private Timestamp date;
    private String userId;
    private String partnerId;
    private String authorName;
    private String status;
    private String audioDuration;

    private String imageUrl;
    private String audioUrl;
    private String docId;
    private boolean readByPartner;

    // Empty constructor REQUIRED for Firebase
    public JournalEntry() {}

    public JournalEntry(String title, String content, Timestamp date, String userId, String partnerId, String authorName, String status, String audioDuration) {
        this.title = title;
        this.content = content;
        this.date = date;
        this.userId = userId;
        this.partnerId = partnerId;
        this.authorName = authorName;
        this.status = status;
        this.audioDuration = audioDuration;
    }

    // --- GETTERS ---
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public Timestamp getDate() { return date; }
    public String getUserId() { return userId; }
    public String getPartnerId() { return partnerId; }
    public String getAuthorName() { return authorName; }
    public String getImageUrl() { return imageUrl; }
    public String getAudioUrl() { return audioUrl; }
    public String getDocId() { return docId; }
    public String getStatus() { return status; }
    public String getAudioDuration() { return audioDuration; }

    // --- SETTERS (Ensuring all fields have them for Firebase) ---
    public void setTitle(String title) { this.title = title; }
    public void setContent(String content) { this.content = content; }
    public void setDate(Timestamp date) { this.date = date; }
    public void setUserId(String userId) { this.userId = userId; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public void setAudioUrl(String audioUrl) { this.audioUrl = audioUrl; }
    public void setDocId(String docId) { this.docId = docId; }
    public void setPartnerId(String partnerId) { this.partnerId = partnerId; }
    public void setAuthorName(String authorName) { this.authorName = authorName; }
    public void setStatus(String status) { this.status = status; }
    public void setAudioDuration(String audioDuration) { this.audioDuration = audioDuration; }

    public boolean isReadByPartner() {
        return readByPartner;
    }

    public void setReadByPartner(boolean readByPartner) {
        this.readByPartner = readByPartner;
    }
}