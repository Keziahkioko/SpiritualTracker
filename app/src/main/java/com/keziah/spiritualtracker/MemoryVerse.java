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
    private String    passageKey;        // unique per added passage, so two with the same name stay apart

    public MemoryVerse() {}

    public String getPassageKey()                  { return passageKey; }
    public void   setPassageKey(String passageKey) { this.passageKey = passageKey; }

    /** Refreshes passed since mastering; null on verses mastered before refreshes existed. */
    private Integer refreshes;
    public Integer getRefreshes()                   { return refreshes; }
    public void    setRefreshes(Integer n)          { this.refreshes = n; }

    /** On a passage's first part: when its parts are next practised together. */
    private com.google.firebase.Timestamp togetherNextDate;
    public com.google.firebase.Timestamp getTogetherNextDate()           { return togetherNextDate; }
    public void setTogetherNextDate(com.google.firebase.Timestamp t)     { this.togetherNextDate = t; }

    /** True until the learn steps are done; null on parts saved before the learn step existed. */
    private Boolean needsLearning;
    public Boolean getNeedsLearning()               { return needsLearning; }
    public void    setNeedsLearning(Boolean b)      { this.needsLearning = b; }

    /** What ties the parts of one passage together (older passages only have the typed name). */
    public String groupKey() {
        if (chunkIndex == null) return null;
        return passageKey != null ? passageKey : passageId;
    }

    private static final java.util.regex.Pattern PART = java.util.regex.Pattern.compile("\\s*\\(Part \\d+ of \\d+\\)");
    private static final java.util.regex.Pattern NUMBER = java.util.regex.Pattern.compile("^\\s*[\\[(]?(\\d+)[\\])]?[.:]?\\s+");

    /**
     * The reference as shown: a part names the verses it holds, so "Exodus 15:1-21 (Part 1 of 7)"
     * holding verses 1 to 3 shows as "Exodus 15:1-3 (Part 1 of 7)". Taken from the verse numbers
     * in the text; when there are none, or they don't run in order, the typed name is kept.
     */
    public String shownReference() {
        if (reference == null || text == null) return reference;
        java.util.regex.Matcher part = PART.matcher(reference);
        if (!part.find()) return reference;
        String chapter = reference.substring(0, part.start()).replaceFirst(":.*$", "").trim();
        if (!chapter.matches(".*\\d+$")) return reference;
        int first = -1, last = -1;
        for (String line : text.split("\n")) {
            java.util.regex.Matcher m = NUMBER.matcher(line);
            if (!m.find()) continue;
            int n = Integer.parseInt(m.group(1));
            if (first < 0) first = n;
            else if (n <= last) return reference;
            last = n;
        }
        if (first < 0) return reference;
        return chapter + ":" + first + (last > first ? "-" + last : "") + " " + part.group().trim();
    }

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