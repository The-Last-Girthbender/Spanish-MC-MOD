package com.espanolquest.vocab;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * English "sounds like" respellings for Latin American Spanish, with the stressed syllable in
 * capitals: manzana -> "mahn-SAH-nah", jamón -> "hah-MOHN", tierra -> "TYEH-rrah".
 *
 * Follows the standard rules: syllables split between vowels (diphthongs stay together),
 * consonant pairs like "br"/"pl" stay together, and stress falls on the accent mark, otherwise
 * on the second-to-last syllable for words ending in a vowel, n or s, else on the last.
 * Plain Java, no Minecraft.
 */
public final class Pronunciation {
    private Pronunciation() {}

    private static final Locale ES = Locale.forLanguageTag("es");
    /** Little words that are never stressed in speech (articles, "de", "y"...). */
    private static final Set<String> UNSTRESSED = Set.of(
            "el", "la", "los", "las", "lo", "de", "del", "al", "un", "en", "y", "o", "con", "a");
    private static final Set<String> INSEPARABLE = Set.of(
            "bl", "br", "cl", "cr", "dr", "fl", "fr", "gl", "gr", "kl", "kr", "pl", "pr", "tr", "tl");

    /** One letter or digraph of a word. */
    private record Unit(String text, boolean vowel) {}

    /** The guide for a word or phrase (each word gets its own stress), or null if it isn't Spanish letters. */
    public static String guide(String phrase) {
        if (phrase == null || phrase.isBlank()) return null;
        List<String> words = new ArrayList<>();
        for (String w : phrase.trim().toLowerCase(ES).split("\\s+")) {
            String g = word(w.replaceAll("[¿?¡!.,]", ""));
            if (g == null) return null;
            if (!g.isEmpty()) words.add(g);
        }
        return words.isEmpty() ? null : String.join(" ", words);
    }

    private static boolean isVowel(char c) {
        return "aeiouáéíóúü".indexOf(c) >= 0;
    }

    private static boolean isStrong(String v) {
        // a, e, o (with or without accent) and accented í/ú are strong; they don't merge with each other.
        return "aeoáéóíú".contains(v);
    }

    /** Splits a word into letters/digraphs, marking vowels. Returns null for non-Spanish letters. */
    private static List<Unit> units(String w) {
        List<Unit> u = new ArrayList<>();
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            char next = i + 1 < w.length() ? w.charAt(i + 1) : 0;
            char after = i + 2 < w.length() ? w.charAt(i + 2) : 0;
            if (!Character.isLetter(c) || "abcdefghijklmnñopqrstuvwxyzáéíóúü".indexOf(c) < 0) return null;
            if (c == 'c' && next == 'h') { u.add(new Unit("ch", false)); i++; continue; }
            if (c == 'l' && next == 'l') { u.add(new Unit("ll", false)); i++; continue; }
            if (c == 'r' && next == 'r') { u.add(new Unit("rr", false)); i++; continue; }
            if (c == 'q' && next == 'u') { u.add(new Unit("qu", false)); i++; continue; }
            if (c == 'g' && next == 'u' && (after == 'e' || after == 'i' || after == 'é' || after == 'í')) {
                u.add(new Unit("gu", false)); i++; continue;
            }
            if (c == 'y') {
                // "y" at the end after a vowel (hoy, rey, muy) is a vowel sound.
                boolean prevVowel = i > 0 && isVowel(w.charAt(i - 1));
                if (next == 0 && prevVowel) { u.add(new Unit("i", true)); continue; }
                u.add(new Unit("y", false));
                continue;
            }
            u.add(new Unit(String.valueOf(c), isVowel(c)));
        }
        return u;
    }

    private static String word(String w) {
        if (w.isEmpty()) return "";
        List<Unit> u = units(w);
        if (u == null) return null;

        // ---- vowel groups (nuclei): diphthongs stay together, two strong vowels split ----
        List<int[]> nuclei = new ArrayList<>(); // {startUnit, endUnitExclusive}
        int i = 0;
        while (i < u.size()) {
            if (!u.get(i).vowel()) { i++; continue; }
            int start = i;
            i++;
            while (i < u.size() && u.get(i).vowel()) {
                String prev = u.get(i - 1).text(), cur = u.get(i).text();
                if (isStrong(prev) && isStrong(cur)) break; // hiatus: separate syllables
                i++;
            }
            nuclei.add(new int[]{start, i});
        }
        if (nuclei.isEmpty()) return null;

        // ---- where each syllable starts (consonants between vowel groups) ----
        int n = nuclei.size();
        int[] sylStart = new int[n];
        sylStart[0] = 0;
        for (int k = 1; k < n; k++) {
            int prevEnd = nuclei.get(k - 1)[1];
            int nextStart = nuclei.get(k)[0];
            int count = nextStart - prevEnd;
            int split;
            if (count <= 1) split = prevEnd;
            else if (count == 2) {
                String pair = u.get(prevEnd).text() + u.get(prevEnd + 1).text();
                split = INSEPARABLE.contains(pair) ? prevEnd : prevEnd + 1;
            } else if (count == 3) {
                String lastTwo = u.get(nextStart - 2).text() + u.get(nextStart - 1).text();
                split = INSEPARABLE.contains(lastTwo) ? prevEnd + 1 : prevEnd + 2;
            } else {
                split = nextStart - 2;
            }
            sylStart[k] = split;
        }

        // ---- stress ----
        int stressed = -1;
        for (int k = 0; k < n && stressed < 0; k++) {
            int end = k + 1 < n ? sylStart[k + 1] : u.size();
            for (int j = sylStart[k]; j < end; j++) {
                if ("áéíóú".contains(u.get(j).text())) { stressed = k; break; }
            }
        }
        if (stressed < 0) {
            char last = w.charAt(w.length() - 1);
            boolean penult = "aeiouns".indexOf(last) >= 0;
            stressed = penult && n >= 2 ? n - 2 : n - 1;
        }

        // ---- respell each syllable ----
        StringBuilder out = new StringBuilder();
        for (int k = 0; k < n; k++) {
            int end = k + 1 < n ? sylStart[k + 1] : u.size();
            StringBuilder syl = new StringBuilder();
            int j = sylStart[k];
            int nucStart = nuclei.get(k)[0], nucEnd = nuclei.get(k)[1];
            for (; j < nucStart; j++) syl.append(consonant(u, j, true));
            syl.append(vowels(u, nucStart, nucEnd));
            for (j = nucEnd; j < end; j++) syl.append(consonant(u, j, false));
            if (k > 0) out.append('-');
            out.append(k == stressed && n > 1 ? syl.toString().toUpperCase(Locale.ROOT) : syl);
        }
        // A one-syllable word is stressed (capitals), except little words like "la" or "de".
        if (n == 1) return UNSTRESSED.contains(w) ? out.toString() : out.toString().toUpperCase(Locale.ROOT);
        return out.toString();
    }

    private static String consonant(List<Unit> u, int j, boolean onset) {
        String t = u.get(j).text();
        String nextVowel = j + 1 < u.size() && u.get(j + 1).vowel() ? u.get(j + 1).text() : "";
        boolean soft = !nextVowel.isEmpty() && "eiéí".contains(nextVowel.substring(0, 1));
        String prev = j > 0 ? u.get(j - 1).text() : "";
        return switch (t) {
            case "b", "v" -> "b";
            case "c" -> soft ? "s" : "k";
            case "g" -> soft ? "h" : "g";
            case "gu", "qu" -> t.equals("gu") ? "g" : "k";
            case "h" -> "";
            case "j" -> !onset && j == u.size() - 1 ? "" : "h"; // final j (reloj) is nearly silent
            case "ll", "y" -> "y";
            case "ñ" -> "ny";
            case "z" -> "s";
            case "x" -> j == 0 ? "s" : "ks";
            case "r" -> (j == 0 || prev.equals("n") || prev.equals("l") || prev.equals("s")) ? "rr" : "r";
            default -> t;
        };
    }

    /**
     * Respells a vowel group. The main vowel is the first strong one (a, e, o); for two weak
     * vowels (iu, ui) it's the second. Weak vowels before it glide (i -> y, u -> w); a weak
     * vowel after it makes a diphthong (ai -> eye, ei -> ay, oi -> oy, au -> ow).
     */
    private static String vowels(List<Unit> u, int start, int end) {
        StringBuilder v = new StringBuilder();
        for (int j = start; j < end; j++) v.append(u.get(j).text());
        String s = v.toString().replace('á', 'a').replace('é', 'e').replace('ó', 'o');
        if (s.length() == 1) return sound(s.charAt(0));

        int peak = -1;
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if ("aeoíú".indexOf(c) >= 0) { peak = k; break; }
        }
        if (peak < 0) peak = s.length() - 1; // iu -> yoo, ui -> wee

        StringBuilder out = new StringBuilder();
        for (int k = 0; k < peak; k++) out.append(s.charAt(k) == 'i' ? "y" : "w");
        char main = s.charAt(peak);
        String tail = s.substring(peak + 1);
        if (tail.isEmpty()) {
            out.append(sound(main));
        } else {
            boolean i = tail.charAt(0) == 'i';
            out.append(switch (main) {
                case 'a' -> i ? "eye" : "ow";
                case 'e' -> i ? "ay" : "eoo";
                case 'o' -> i ? "oy" : "ow";
                default -> sound(main) + (i ? "ee" : "oo");
            });
        }
        return out.toString();
    }

    private static String sound(char v) {
        return switch (v) {
            case 'a' -> "ah";
            case 'e' -> "eh";
            case 'i', 'í' -> "ee";
            case 'o' -> "oh";
            default -> "oo"; // u, ú, ü
        };
    }

    /** One short tip about the trickiest sound in the word, or null. */
    public static String tip(String phrase) {
        if (phrase == null) return null;
        String s = SpanishText.plain(phrase);
        if (s.contains("rr") || s.matches("(^|.*\\s)r.*") || s.matches(".*[nls]r.*")) {
            return "rr = a rolled r: trill the tip of your tongue";
        }
        if (s.contains("ñ")) return "ñ = \"ny\", like the middle of \"canyon\"";
        if (s.contains("j") || s.matches(".*g[ei].*")) return "j and ge/gi = a soft, breathy h";
        if (s.matches(".*[aeiou]r[aeiou].*") || s.endsWith("r")) return "r = a quick tap, like the \"dd\" in \"ladder\"";
        if (s.contains("ll")) return "ll sounds like the y in \"yes\"";
        if (s.contains("h")) return "h is always silent";
        if (s.contains("v")) return "v sounds like a soft b";
        if (s.contains("qu")) return "qu = k (the u is silent)";
        return "Vowels are short and pure: ah, eh, ee, oh, oo";
    }
}
