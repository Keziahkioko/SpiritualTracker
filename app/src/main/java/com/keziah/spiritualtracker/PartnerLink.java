package com.keziah.spiritualtracker;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Links two accounts with a short invite code, stored at invites/{code}.
 *
 * Each person only ever writes their own user doc: the joiner records themselves on the invite
 * and sets their own partnerId; the inviter's app then sees the accepted invite and sets theirs.
 * Partnership only counts once both partnerIds point at each other (see firestore.rules).
 */
public final class PartnerLink {

    public interface Result {
        void onSuccess(String value);
        void onError(String message);
    }

    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"; // no 0/O, 1/I/L
    private static final int CODE_LENGTH = 6;
    private static final long EXPIRY_MS = 24L * 60 * 60 * 1000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PartnerLink() {}

    private static FirebaseFirestore db() { return FirebaseFirestore.getInstance(); }

    static String newCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    /** Uppercases and strips spaces/dashes so "abc-d23" works. */
    static String normalize(String input) {
        return input == null ? "" : input.toUpperCase().replaceAll("[^A-Z0-9]", "");
    }

    /** Creates a fresh invite code owned by {@code myId}. */
    public static void createInvite(String myId, Result result) {
        String code = newCode();
        DocumentReference ref = db().collection("invites").document(code);
        Map<String, Object> data = new HashMap<>();
        data.put("ownerId", myId);
        data.put("createdAt", Timestamp.now());
        db().runTransaction(t -> {
            if (t.get(ref).exists()) throw new LinkError("That code is taken; try again.");
            t.set(ref, data);
            return code;
        }).addOnSuccessListener(result::onSuccess)
          .addOnFailureListener(e -> result.onError(message(e, "Couldn't create a code. Check your connection and try again.")));
    }

    /** Joins the person who created {@code rawCode}. Succeeds with their uid. */
    public static void acceptInvite(String myId, String rawCode, Result result) {
        String code = normalize(rawCode);
        if (code.length() != CODE_LENGTH) {
            result.onError("Codes are " + CODE_LENGTH + " letters and numbers.");
            return;
        }
        DocumentReference ref = db().collection("invites").document(code);
        db().runTransaction(t -> {
            DocumentSnapshot invite = t.get(ref);
            if (!invite.exists()) throw fail("That code doesn't exist. Check it and try again.");
            String ownerId = invite.getString("ownerId");
            Timestamp created = invite.getTimestamp("createdAt");
            if (myId.equals(ownerId)) throw fail("That's your own code. Ask your partner to enter it.");
            if (invite.getString("acceptedBy") != null) throw fail("That code has already been used.");
            if (created == null || System.currentTimeMillis() - created.toDate().getTime() > EXPIRY_MS)
                throw fail("That code has expired. Ask for a new one.");
            t.update(ref, "acceptedBy", myId);
            return ownerId;
        }).addOnSuccessListener(ownerId ->
                db().collection("users").document(myId)
                        .set(Collections.singletonMap("partnerId", ownerId), SetOptions.merge())
                        .addOnSuccessListener(v -> result.onSuccess(ownerId))
                        .addOnFailureListener(e -> result.onError("Couldn't save the link. Try again.")))
          .addOnFailureListener(e -> result.onError(message(e, "Couldn't use that code. Check your connection and try again.")));
    }

    /** A user-facing reason the link can't be made. Not a Firestore error, so the transaction isn't retried. */
    private static final class LinkError extends RuntimeException {
        LinkError(String message) { super(message); }
    }

    private static LinkError fail(String message) {
        return new LinkError(message);
    }

    private static String message(Exception e, String fallback) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof LinkError) return t.getMessage();
        }
        return fallback;
    }

    /**
     * Inviter side: if someone accepted one of my codes, point my partnerId at them and
     * clean up. Safe to call on every app start.
     */
    public static void completeAcceptedInvites(String myId, Result result) {
        db().collection("invites").whereEqualTo("ownerId", myId).get()
                .addOnSuccessListener(snap -> {
                    for (DocumentSnapshot invite : snap.getDocuments()) {
                        String joiner = invite.getString("acceptedBy");
                        if (joiner == null) {
                            Timestamp created = invite.getTimestamp("createdAt");
                            boolean expired = created == null
                                    || System.currentTimeMillis() - created.toDate().getTime() > EXPIRY_MS;
                            if (expired) invite.getReference().delete();
                            continue;
                        }
                        Map<String, Object> update = new HashMap<>();
                        update.put("partnerId", joiner);
                        update.put("partnerLinkedAt", FieldValue.serverTimestamp());
                        db().collection("users").document(myId).set(update, SetOptions.merge())
                                .addOnSuccessListener(v -> {
                                    invite.getReference().delete();
                                    if (result != null) result.onSuccess(joiner);
                                });
                        return; // only one partner
                    }
                });
    }
}
