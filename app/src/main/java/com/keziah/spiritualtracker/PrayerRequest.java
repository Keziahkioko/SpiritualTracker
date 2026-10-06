package com.keziah.spiritualtracker;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.PropertyName;

public class PrayerRequest {
    private String requestId;
    private String title;
    private String description;
    private String authorId;
    private String authorName;
    private Timestamp timestamp;
    private boolean isAnswered;
    private String answeredComment;

    public PrayerRequest() {
        // Required for Firebase
    }

    public PrayerRequest(String requestId, String title, String description, String authorId, String authorName, Timestamp timestamp, boolean isAnswered) {
        this.requestId = requestId;
        this.title = title;
        this.description = description;
        this.authorId = authorId;
        this.authorName = authorName;
        this.timestamp = timestamp;
        this.isAnswered = isAnswered;
    }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getAuthorId() { return authorId; }
    public void setAuthorId(String authorId) { this.authorId = authorId; }

    public String getAuthorName() { return authorName; }
    public void setAuthorName(String authorName) { this.authorName = authorName; }

    public Timestamp getTimestamp() { return timestamp; }
    public void setTimestamp(Timestamp timestamp) { this.timestamp = timestamp; }

    // Use PropertyName to ensure Firestore matches the field exactly
    @PropertyName("isAnswered")
    public boolean isAnswered() { return isAnswered; }
    
    @PropertyName("isAnswered")
    public void setAnswered(boolean answered) { isAnswered = answered; }

    public String getAnsweredComment() { return answeredComment; }
    public void setAnsweredComment(String answeredComment) { this.answeredComment = answeredComment; }
}
