package com.keziah.spiritualtracker;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Type it out": lines a recitation up against the verse, word by word and in order. Each word
 * of the verse ends up EXACT, DIFFERENT (another word was typed in its place) or MISSED.
 * Capitals, punctuation, verse numbers and one-letter typos don't count against you.
 */
final class TypeCheck {

    static final int EXACT = 0, DIFFERENT = 1, MISSED = 2;

    private static final Pattern PARTS = Pattern.compile("^([^\\p{L}\\p{N}]*)(.*?)([^\\p{L}\\p{N}]*)$");
    private static final Pattern VERSE_NUMBER = Pattern.compile("^[\\[(]?\\d+[\\])]?[.:]?$");

    /** One piece of the verse as shown: a verse number / punctuation (no status), or a word. */
    static final class Piece {
        String lead, core, trail;
        boolean newLine, word;
        int line, status;
    }

    final List<Piece> pieces = new ArrayList<>();
    final List<String> lines = new ArrayList<>();
    int exact, different, missed;

    TypeCheck(String verse, String typed) {
        // The verse, keeping its layout so the result can be shown the same way.
        List<Piece> words = new ArrayList<>();
        for (String raw : (verse == null ? "" : verse).split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            String[] tokens = line.split("\\s+");
            for (int i = 0; i < tokens.length; i++) {
                Piece p = new Piece();
                p.newLine = i == 0 && !lines.isEmpty();
                p.line = lines.size();
                Matcher m = PARTS.matcher(tokens[i]);
                boolean number = i == 0 && VERSE_NUMBER.matcher(tokens[i]).matches();
                if (!number && m.matches() && !normal(m.group(2)).isEmpty()) {
                    p.lead = m.group(1);
                    p.core = m.group(2);
                    p.trail = m.group(3);
                    p.word = true;
                    words.add(p);
                } else {
                    p.lead = tokens[i];
                    p.core = "";
                    p.trail = "";
                }
                pieces.add(p);
            }
            lines.add(line);
        }

        // What was typed: just the words.
        List<String> said = new ArrayList<>();
        for (String t : (typed == null ? "" : typed).split("\\s+")) {
            String n = normal(t);
            if (!n.isEmpty() && !n.chars().allMatch(Character::isDigit)) said.add(n);
        }

        // Longest run of matching words, in order.
        int a = words.size(), b = said.size();
        int[][] best = new int[a + 1][b + 1];
        for (int i = a - 1; i >= 0; i--) {
            String w = normal(words.get(i).core);
            for (int j = b - 1; j >= 0; j--) {
                best[i][j] = same(w, said.get(j)) ? best[i + 1][j + 1] + 1 : Math.max(best[i + 1][j], best[i][j + 1]);
            }
        }

        // Walk it: between two matches, verse words with typed words opposite them were said
        // differently; any left over were missed.
        int i = 0, j = 0, gapWords = 0, gapSaid = 0;
        List<Piece> gap = new ArrayList<>();
        while (i < a || j < b) {
            if (i < a && j < b && same(normal(words.get(i).core), said.get(j))) {
                closeGap(gap, gapSaid);
                gap.clear();
                gapSaid = 0;
                words.get(i).status = EXACT;
                exact++;
                i++;
                j++;
            } else if (j < b && (i == a || best[i][j + 1] >= best[i + 1][j])) {
                gapSaid++;
                j++;
            } else {
                gap.add(words.get(i));
                i++;
            }
        }
        closeGap(gap, gapSaid);
    }

    private void closeGap(List<Piece> gap, int saidInGap) {
        for (int k = 0; k < gap.size(); k++) {
            if (k < saidInGap) { gap.get(k).status = DIFFERENT; different++; }
            else               { gap.get(k).status = MISSED;    missed++; }
        }
    }

    int total() {
        return exact + different + missed;
    }

    /** Words of the verse on {@code line} that weren't typed exactly. */
    int notExactOn(int line) {
        int n = 0;
        for (Piece p : pieces) if (p.word && p.line == line && p.status != EXACT) n++;
        return n;
    }

    /** The verse number a line starts with, or null. */
    String verseNumber(int line) {
        String first = lines.get(line).split("\\s+")[0];
        return VERSE_NUMBER.matcher(first).matches() ? first.replaceAll("\\D", "") : null;
    }

    static String normal(String word) {
        StringBuilder sb = new StringBuilder();
        for (char c : word.toLowerCase().toCharArray()) if (Character.isLetterOrDigit(c)) sb.append(c);
        return sb.toString();
    }

    /**
     * Same word, allowing one slip in words of four letters or more: a wrong, missing or extra
     * letter, or two neighbouring letters swapped ("tempel" for "temple").
     */
    static boolean same(String x, String y) {
        if (x.equals(y)) return true;
        if (Math.min(x.length(), y.length()) < 4 || Math.abs(x.length() - y.length()) > 1) return false;
        if (x.length() == y.length()) {
            int first = 0;
            while (x.charAt(first) == y.charAt(first)) first++;
            if (first + 1 < x.length() && x.charAt(first) == y.charAt(first + 1) && x.charAt(first + 1) == y.charAt(first)
                    && x.substring(first + 2).equals(y.substring(first + 2))) return true;
        }
        int i = 0, j = 0, edits = 0;
        while (i < x.length() && j < y.length()) {
            if (x.charAt(i) == y.charAt(j)) { i++; j++; continue; }
            if (++edits > 1) return false;
            if (x.length() > y.length()) i++;
            else if (y.length() > x.length()) j++;
            else { i++; j++; }
        }
        return edits + (x.length() - i) + (y.length() - j) <= 1;
    }
}
