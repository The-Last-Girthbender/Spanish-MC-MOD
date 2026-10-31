package com.espanolquest.vocab;

import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;

/** Spanish text helpers: normalising for answer matching, "sounds-alike" form, encoding repair. */
public final class SpanishText {
    private SpanishText() {}

    private static final Locale ES = Locale.forLanguageTag("es");

    /**
     * For comparing spellings: lower case, accents removed (but ñ kept), punctuation
     * (including ¿ ¡) and spaces removed. "¡La Manzana!" -> "lamanzana", "jamón" -> "jamon".
     */
    public static String normalize(String s) {
        if (s == null) return "";
        String lower = Normalizer.normalize(s, Normalizer.Form.NFC).toLowerCase(ES).replace('ñ', '\u0001');
        String stripped = Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        StringBuilder sb = new StringBuilder(stripped.length());
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (c == '\u0001') sb.append('ñ');
            else if (Character.isLetterOrDigit(c)) sb.append(c);
        }
        return sb.toString();
    }

    /** Removes accents but keeps spaces (for display/word splitting). */
    public static String plain(String s) {
        String lower = s.toLowerCase(ES).replace('ñ', '\u0001');
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}", "").replace('\u0001', 'ñ');
    }

    /**
     * How the word sounds in Latin American Spanish, as a simple letter code, so spellings
     * that sound the same compare as equal: b/v, s/z/c(e,i), ll/y, silent h, qu/c/k, g(e,i)/j.
     * Input should already be {@link #normalize normalized}.
     */
    public static String sound(String n) {
        StringBuilder out = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            char next = i + 1 < n.length() ? n.charAt(i + 1) : 0;
            char after = i + 2 < n.length() ? n.charAt(i + 2) : 0;
            switch (c) {
                case 'h' -> { /* silent */ }
                case 'v' -> out.append('b');
                case 'z' -> out.append('s');
                case 'k' -> out.append('k');
                case 'w' -> out.append('u');
                case 'ñ' -> out.append("ny");
                case 'x' -> out.append("ks");
                case 'c' -> {
                    if (next == 'h') { out.append('C'); i++; }
                    else if (next == 'e' || next == 'i') out.append('s');
                    else out.append('k');
                }
                case 'q' -> {
                    out.append('k');
                    if (next == 'u') i++;
                }
                case 'g' -> {
                    if (next == 'e' || next == 'i') out.append('j');
                    else if (next == 'u' && (after == 'e' || after == 'i')) { out.append('g'); i++; }
                    else out.append('g');
                }
                case 'l' -> {
                    if (next == 'l') { out.append('y'); i++; }
                    else out.append('l');
                }
                case 'r' -> {
                    if (next == 'r') { out.append('R'); i++; }
                    else if (i == 0) out.append('R');
                    else out.append('r');
                }
                case 'y' -> {
                    boolean vowelNext = next != 0 && "aeiou".indexOf(next) >= 0;
                    out.append(vowelNext ? 'y' : 'i');
                }
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ matching

    public static double similarity(String a, String b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        return 1.0 - (double) levenshtein(a, b) / Math.max(a.length(), b.length());
    }

    public static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    /** 0-1: 1.0 if the target appears in what was heard, else the best similarity of a stretch about as long. */
    public static double matchScore(String heard, String target) {
        if (heard.isEmpty() || target.isEmpty()) return 0.0;
        if (heard.contains(target)) return 1.0;
        int len = target.length();
        if (heard.length() <= len + 1) return similarity(heard, target);
        double best = 0.0;
        for (int w = Math.max(1, len - 1); w <= len + 1; w++) {
            for (int start = 0; start + w <= heard.length(); start++) {
                best = Math.max(best, similarity(heard.substring(start, start + w), target));
            }
        }
        return best;
    }

    public static boolean matches(String heard, String target, double strictness) {
        double score = matchScore(heard, target);
        if (strictness >= 1.0) return score >= 1.0;
        return score > 0 && score >= Math.max(strictness, 0.01);
    }

    // ------------------------------------------------------------------ encoding repair

    /**
     * Undoes "UTF-8 read as Windows-1252" garbling (jamón -> jamÃ³n, niño -> niÃ±o).
     * Real Spanish also uses characters above 0x7F, so this only changes text that decodes
     * cleanly as UTF-8 and gets shorter (a sure sign it was garbled).
     */
    public static String repairMojibake(String s) {
        if (s == null || s.isEmpty()) return s;
        boolean high = false;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) >= 0x80) { high = true; break; }
        if (!high) return s;
        for (String cs : new String[]{"windows-1252", "ISO-8859-1"}) {
            try {
                Charset charset = Charset.forName(cs);
                if (!charset.newEncoder().canEncode(s)) continue;
                byte[] bytes = s.getBytes(charset);
                CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
                String fixed = dec.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                if (fixed.length() < s.length()) return fixed;
            } catch (Exception ignored) {}
        }
        return s;
    }
}
