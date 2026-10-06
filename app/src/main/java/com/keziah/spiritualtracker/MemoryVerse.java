package com.keziah.spiritualtracker;

import com.google.firebase.Timestamp;

public class MemoryVerse {
    private String    id;
    private String    reference;
    private String    text;
    private Integer   intervalIndex;
    private String    stage;             // "learning" | "reviewing" | "mastered"
    private Timestamp nextReviewDate;
    private Timestamp addedAt;
    private String    lastReviewedDate;  // "yyyy-MM-dd" — gates one advancement per day
    private boolean   shared;            // visible to partner
    private String    ownerName;         // shown on partner's shared view
    private String    passageId;         // non-null for chunk entries — groups chunks together
    private Integer   chunkIndex;        // 0-based position within the passage

    public MemoryVerse() {}

    // Getters
    public String    getId()               { return id; }
    public String    getReference()        { return reference; }
    public String    getText()             { return text; }
    public Integer   getIntervalIndex()    { return intervalIndex; }
    public String    getStage()            { return stage; }
    public Timestamp getNextReviewDate()   { return nextReviewDate; }
    public Timestamp getAddedAt()          { return addedAt; }
    public String    getLastReviewedDate() { return lastReviewedDate; }
    public boolean   isShared()            { return shared; }
    public String    getOwnerName()        { return ownerName; }
    public String    getPassageId()        { return passageId; }
    public Integer   getChunkIndex()       { return chunkIndex; }

    // Setters
    public void setId(String id)                         { this.id = id; }
    public void setReference(String reference)           { this.reference = reference; }
    public void setText(String text)                     { this.text = text; }
    public void setIntervalIndex(Integer intervalIndex)  { this.intervalIndex = intervalIndex; }
    public void setStage(String stage)                   { this.stage = stage; }
    public void setNextReviewDate(Timestamp t)           { this.nextReviewDate = t; }
    public void setAddedAt(Timestamp addedAt)            { this.addedAt = addedAt; }
    public void setLastReviewedDate(String d)            { this.lastReviewedDate = d; }
    public void setShared(boolean shared)                { this.shared = shared; }
    public void setOwnerName(String ownerName)           { this.ownerName = ownerName; }
    public void setPassageId(String passageId)           { this.passageId = passageId; }
    public void setChunkIndex(Integer chunkIndex)        { this.chunkIndex = chunkIndex; }
}