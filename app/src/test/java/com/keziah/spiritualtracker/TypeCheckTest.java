package com.keziah.spiritualtracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** "Type it out": grading a typed recitation word by word. */
public class TypeCheckTest {

    private static final String VERSE = "16 For God so loved the world, that he gave his only Son.";

    @Test
    public void perfectRecitationIsAllExact() {
        TypeCheck c = new TypeCheck(VERSE, "For God so loved the world that he gave his only Son");
        assertEquals(12, c.total());
        assertEquals(12, c.exact);
        assertEquals(0, c.different + c.missed);
    }

    @Test
    public void capitalsPunctuationAndVerseNumbersDoNotCount() {
        TypeCheck c = new TypeCheck(VERSE, "16 for god, so LOVED the world... that he gave his only son!");
        assertEquals(12, c.exact);
    }

    @Test
    public void aSwappedWordIsDifferent() {
        TypeCheck c = new TypeCheck(VERSE, "For God so loved the earth that he gave his only Son");
        assertEquals(11, c.exact);
        assertEquals(1, c.different);
        assertEquals(0, c.missed);
    }

    @Test
    public void leftOutWordsAreMissed() {
        TypeCheck c = new TypeCheck(VERSE, "For God so loved the world");
        assertEquals(6, c.exact);
        assertEquals(6, c.missed);
    }

    @Test
    public void hyphenatedWordsCountAsTwo() {
        TypeCheck c = new TypeCheck("1 Do not use your freedom for self-indulgence", "Do not use your freedom for self indulgence");
        assertEquals(c.total(), c.exact);
    }

    @Test
    public void numbersInTheVerseAreKept() {
        TypeCheck c = new TypeCheck("1 About 5000 men were fed", "About 5000 men were fed");
        assertEquals(5, c.exact);
        assertEquals(5, c.total());
    }

    @Test
    public void nothingTypedMissesEverything() {
        TypeCheck c = new TypeCheck(VERSE, "");
        assertEquals(0, c.exact);
        assertEquals(12, c.missed);
    }

    @Test
    public void verseNumberOfALine() {
        TypeCheck c = new TypeCheck("1 First line\n2 Second line\nNo number", "");
        assertEquals("1", c.verseNumber(0));
        assertEquals("2", c.verseNumber(1));
        assertNull(c.verseNumber(2));
        assertEquals(2, c.notExactOn(1));
    }
}
