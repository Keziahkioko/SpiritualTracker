package com.keziah.spiritualtracker;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Breaks a pasted passage into verses ("1 The Lord is my shepherd…", one per line), however it
 * was copied: numbers on their own or stuck to the first word ("16For"), in brackets, with a dot,
 * as "23:1", or superscript. Numbers are only taken as verse numbers when they follow on from the
 * one before (gaps of up to two allowed, as where a translation leaves a verse out), so a count
 * inside a verse ("the 12 disciples", "5,000 men") stays part of the text. Headings above verse 1
 * and footnote marks like [a] or (A) are dropped. Without any verse numbers it goes by line, or
 * else by sentence.
 */
final class PassageSplit {

    private PassageSplit() {}

    private static final String SUPERSCRIPTS = "⁰¹²³⁴⁵⁶⁷⁸⁹";

    /** A possible verse number: "23:1", "[1]", "(1)", "1.", "1" or "16For". */
    private static final Pattern CANDIDATE = Pattern.compile(
            "(?<![\\p{L}\\p{N},.:])(?:\\d+:)?[\\[(]?(\\d{1,3})[\\])]?\\.?(?=\\s|[\\p{Lu}\"'“‘])(?![.,:]\\d)");

    private static final class Marker {
        final int start, end, number;
        Marker(int start, int end, int number) { this.start = start; this.end = end; this.number = number; }
    }

    static List<String> verses(String pasted) {
        String text = clean(pasted);
        List<Marker> markers = findMarkers(text);

        List<String> out = new ArrayList<>();
        if (markers.isEmpty()) {
            String[] lines = text.split("\n");
            List<String> nonEmpty = new ArrayList<>();
            for (String l : lines) if (!l.trim().isEmpty()) nonEmpty.add(oneLine(l));
            if (nonEmpty.size() > 1) return nonEmpty;
            for (String s : text.split("(?<=[.!?][\"'”’]?)\\s+(?=[\\p{Lu}\"'“‘])")) {
                if (!s.trim().isEmpty()) out.add(oneLine(s));
            }
            return out;
        }

        // Before the first verse number: nothing comes before verse 1, so that's a heading;
        // before a later verse it's the end of the verse before, kept as it is.
        String before = text.substring(0, markers.get(0).start);
        if (!before.trim().isEmpty() && markers.get(0).number != 1) out.add(oneLine(before));

        for (int i = 0; i < markers.size(); i++) {
            Marker m = markers.get(i);
            int end = i + 1 < markers.size() ? markers.get(i + 1).start : text.length();
            String body = oneLine(text.substring(m.end, end));
            out.add(m.number + (body.isEmpty() ? "" : " " + body));
        }
        return out;
    }

    private static String clean(String pasted) {
        if (pasted == null) return "";
        StringBuilder sb = new StringBuilder();
        String t = pasted.replace("\r\n", "\n").replace('\r', '\n').replace(' ', ' ');
        for (int i = 0; i < t.length(); i++) {
            int sup = SUPERSCRIPTS.indexOf(t.charAt(i));
            if (sup < 0) { sb.append(t.charAt(i)); continue; }
            // Superscript verse numbers become ordinary ones, with a space before the word.
            boolean first = i == 0 || SUPERSCRIPTS.indexOf(t.charAt(i - 1)) < 0;
            if (first && sb.length() > 0 && !Character.isWhitespace(sb.charAt(sb.length() - 1))) sb.append(' ');
            sb.append((char) ('0' + sup));
            boolean last = i + 1 >= t.length() || SUPERSCRIPTS.indexOf(t.charAt(i + 1)) < 0;
            if (last && i + 1 < t.length() && !Character.isWhitespace(t.charAt(i + 1))) sb.append(' ');
        }
        return sb.toString()
                .replaceAll("\\[[a-z]{1,2}\\]", "")         // footnotes: [a]
                .replaceAll("\\s?\\([A-Z]{1,2}\\)", "")      // cross-references: (A), (AB)
                .trim();
    }

    private static List<Marker> findMarkers(String text) {
        List<Marker> found = new ArrayList<>();
        Matcher m = CANDIDATE.matcher(text);
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (n == 0) continue;
            int last = found.isEmpty() ? -1 : found.get(found.size() - 1).number;
            boolean strong = strongContext(text, m.start(), m.end());
            boolean accept;
            if (found.isEmpty()) {
                accept = true;
            } else if (found.size() == 1 && n == last) {
                // The same number again: the first was in a title ("Matthew 23 Seven Woes… 23 Then…").
                found.clear();
                accept = true;
            } else if (found.size() == 1 && n == 2 && last != 1) {
                // Some sites print the chapter number where verse 1 starts ("23 The Lord… 2 He…").
                Marker first = found.remove(0);
                found.add(new Marker(first.start, first.end, 1));
                accept = true;
            } else if (n == last + 1) {
                // Next in line. Still not a verse number if it comes a word or two into the verse
                // with nothing marking a break ("2 About 3 hours…").
                accept = strong || wordsBetween(text, found.get(found.size() - 1).end, m.start()) >= 3;
            } else {
                accept = n > last + 1 && n <= last + 3 && strong;
            }
            if (accept) found.add(new Marker(m.start(), m.end(), n));
        }
        // A lone number with no verse after it is just a number in the text.
        if (found.size() == 1 && found.get(0).start > 0) found.clear();
        return found;
    }

    /** At the start of a line, after the end of a sentence or clause, or right before a capital. */
    private static boolean strongContext(String text, int start, int end) {
        int b = start - 1;
        while (b >= 0 && text.charAt(b) == ' ') b--;
        if (b < 0 || text.charAt(b) == '\n' || ".,;:!?\"'”’)]".indexOf(text.charAt(b)) >= 0) return true;
        int a = end;
        while (a < text.length() && Character.isWhitespace(text.charAt(a))) a++;
        return a < text.length() && (Character.isUpperCase(text.charAt(a)) || "\"'“‘".indexOf(text.charAt(a)) >= 0);
    }

    private static int wordsBetween(String text, int from, int to) {
        String s = text.substring(from, to).trim();
        return s.isEmpty() ? 0 : s.split("\\s+").length;
    }

    private static String oneLine(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }
}
