package com.keziah.spiritualtracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** Breaking a pasted Bible passage into one entry per verse. */
public class PassageSplitTest {

    private static void assertVerses(String pasted, String... expected) {
        assertEquals(Arrays.asList(expected), PassageSplit.verses(pasted));
    }

    @Test
    public void plainNumbersSplitIntoVerses() {
        assertVerses("1 In the beginning God created the heavens and the earth. 2 Now the earth was formless.",
                "1 In the beginning God created the heavens and the earth.",
                "2 Now the earth was formless.");
    }

    @Test
    public void numbersStuckToTheWordAreVerseNumbers() {
        assertVerses("16For God so loved the world 17For God did not send his Son",
                "16 For God so loved the world",
                "17 For God did not send his Son");
    }

    @Test
    public void chapterAndVerseStyle() {
        assertVerses("23:1 The Lord is my shepherd; I shall not want. 23:2 He makes me lie down in green pastures.",
                "1 The Lord is my shepherd; I shall not want.",
                "2 He makes me lie down in green pastures.");
    }

    @Test
    public void bracketedAndDottedNumbers() {
        assertVerses("[1] Blessed is the man [2] But his delight",
                "1 Blessed is the man", "2 But his delight");
        assertVerses("1. Blessed is the man 2. But his delight",
                "1 Blessed is the man", "2 But his delight");
    }

    @Test
    public void countsInsideAVerseDoNotSplitIt() {
        // "12" and "3" are not the next verse number in sequence, so they stay in the text.
        List<String> out = PassageSplit.verses(
                "1 Jesus called the 12 disciples together and it was about 3 hours later. 2 Then he went up the mountain.");
        assertEquals(2, out.size());
        assertTrue(out.get(0).contains("the 12 disciples"));
        assertTrue(out.get(0).contains("about 3 hours"));
    }

    @Test
    public void headingAboveVerseOneIsDropped() {
        assertVerses("The Song of Moses\n1 Then Moses and the Israelites sang 2 The Lord is my strength",
                "1 Then Moses and the Israelites sang", "2 The Lord is my strength");
    }

    @Test
    public void footnotesAndCrossReferencesAreRemoved() {
        assertVerses("1 In the beginning[a] God created(A) the heavens 2 Now the earth",
                "1 In the beginning God created the heavens", "2 Now the earth");
    }

    @Test
    public void superscriptVerseNumbers() {
        assertVerses("¹In the beginning was the Word ²He was with God",
                "1 In the beginning was the Word", "2 He was with God");
    }

    @Test
    public void withoutNumbersSplitsByLine() {
        assertVerses("The Lord is my shepherd\nI shall not want\n\nHe restores my soul",
                "The Lord is my shepherd", "I shall not want", "He restores my soul");
    }

    @Test
    public void withoutNumbersOrLinesSplitsBySentence() {
        assertVerses("Jesus wept. Then the Jews said, See how he loved him!",
                "Jesus wept.", "Then the Jews said, See how he loved him!");
    }

    @Test
    public void emptyOrNullGivesNothing() {
        assertTrue(PassageSplit.verses("").isEmpty());
        assertTrue(PassageSplit.verses(null).isEmpty());
    }
}
