package com.espanolquest.vocab;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Item ID -> Spanish word lookup.
 *
 * The built-in list (inside the jar) is loaded first, then config/espanolquest/vocabulary.json
 * on top so players can fix or add words. Deliberately free of Minecraft classes.
 */
public final class Vocabulary {
    private Vocabulary() {}

    /**
     * A word the player has to say.
     *
     * @param word     the Spanish noun phrase, e.g. "manzana", "caña de azúcar"
     * @param article  "el", "la", "los", "las", or null if unknown
     * @param accepted every spelling that counts (with and without the article)
     */
    public record Word(String word, String article, String gender, List<String> accepted, boolean curated) {
        /** What's shown big on screen: "la manzana". */
        public String display() {
            return article == null || article.isBlank() ? word : article + " " + word;
        }

        /** "masculine" / "feminine" (+ " plural"), or null if unknown. */
        public String genderLabel() {
            // Some feminine words take "el" (el agua, el hacha); "gender" says so explicitly.
            if ("f".equals(gender)) return "feminine";
            if ("m".equals(gender)) return "masculine";
            if (article == null) return null;
            return switch (article) {
                case "el" -> "masculine";
                case "la" -> "feminine";
                case "los" -> "masculine plural";
                case "las" -> "feminine plural";
                default -> null;
            };
        }
    }

    static final class Entry {
        String word;
        String article;
        String gender; // "m" or "f" when the article doesn't show it (el agua)
        List<String> accept;
    }

    /** A color adjective: masculine and feminine forms (plural is worked out). */
    static final class Color {
        String m;
        String f;
        Boolean invariable; // e.g. "naranja", "verde lima": never changes
        List<String> accept;
    }

    private static final Gson GSON = new Gson();
    private static volatile Data data = new Data();

    private static final class Data {
        final Map<String, Entry> exact = new HashMap<>();
        final Map<String, Entry> suffix = new HashMap<>(); // "*_log"   -> key "_log"
        final Map<String, Entry> prefix = new HashMap<>(); // "music_*" -> key "music_"
        final Map<String, Color> colors = new HashMap<>();
        List<String> colorKeysLongestFirst = List.of();
    }

    // ------------------------------------------------------------------ loading

    public static void load() {
        load(com.espanolquest.EspanolQuest.configDir().resolve("vocabulary.json"),
                msg -> com.espanolquest.EspanolQuest.LOGGER.warn(msg));
    }

    public static synchronized void load(Path userFile, Consumer<String> warn) {
        Data d = new Data();
        try (InputStream in = Vocabulary.class.getResourceAsStream("/assets/espanolquest/default_vocabulary.json")) {
            if (in != null) {
                try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    merge(d, JsonParser.parseReader(r), warn);
                }
            }
        } catch (Exception e) {
            warn.accept("Built-in vocabulary failed to load: " + e.getMessage());
        }
        try {
            if (Files.notExists(userFile)) {
                Files.createDirectories(userFile.getParent());
                try (InputStream in = Vocabulary.class.getResourceAsStream("/assets/espanolquest/default_vocabulary.json")) {
                    if (in != null) Files.copy(in, userFile);
                }
            } else {
                try (Reader r = Files.newBufferedReader(userFile, StandardCharsets.UTF_8)) {
                    merge(d, JsonParser.parseReader(r), warn);
                }
            }
        } catch (Exception e) {
            warn.accept("Could not read " + userFile + " (built-in words are still used): " + e.getMessage());
        }
        List<String> keys = new ArrayList<>(d.colors.keySet());
        keys.sort((a, b) -> b.length() - a.length());
        d.colorKeysLongestFirst = Collections.unmodifiableList(keys);
        data = d;
    }

    private static void merge(Data d, JsonElement root, Consumer<String> warn) {
        if (root == null || !root.isJsonObject()) return;
        JsonObject obj = root.getAsJsonObject();
        if (obj.has("colors") && obj.get("colors").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject("colors").entrySet()) {
                try {
                    Color c = GSON.fromJson(e.getValue(), Color.class);
                    if (c == null || c.m == null || c.m.isBlank()) {
                        warn.accept("Color '" + e.getKey() + "' has no \"m\" form — skipped.");
                        continue;
                    }
                    if (c.f == null || c.f.isBlank()) c.f = c.m;
                    d.colors.put(e.getKey().trim(), c);
                } catch (Exception ex) {
                    warn.accept("Color '" + e.getKey() + "' is malformed — skipped.");
                }
            }
        }
        if (obj.has("items") && obj.get("items").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject("items").entrySet()) {
                Entry entry;
                try {
                    entry = GSON.fromJson(e.getValue(), Entry.class);
                } catch (Exception ex) {
                    warn.accept("Vocabulary entry '" + e.getKey() + "' is malformed — skipped.");
                    continue;
                }
                if (entry == null || entry.word == null || entry.word.isBlank()) {
                    warn.accept("Vocabulary entry '" + e.getKey() + "' has no \"word\" — skipped.");
                    continue;
                }
                if (entry.article != null && entry.article.isBlank()) entry.article = null;
                String key = stripMinecraft(e.getKey().trim());
                if (key.startsWith("*")) d.suffix.put(key.substring(1), entry);
                else if (key.endsWith("*")) d.prefix.put(key.substring(0, key.length() - 1), entry);
                else d.exact.put(key, entry);
            }
        }
    }

    private static String stripMinecraft(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    // ------------------------------------------------------------------ lookup

    /**
     * @param itemId     e.g. "minecraft:red_wool"
     * @param colorWords add the color to colored items ("la lana roja")
     * @return the word, or null if there's no hand-written entry
     */
    public static Word lookup(String itemId, boolean colorWords) {
        Data d = data;
        String key = stripMinecraft(itemId);
        Entry exact = d.exact.get(key);
        if (exact != null) return toWord(exact);

        String path = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
        if (colorWords) {
            for (String color : d.colorKeysLongestFirst) {
                if (path.startsWith(color + "_")) {
                    String rest = path.substring(color.length() + 1);
                    Entry noun = d.exact.get(rest);
                    if (noun == null) noun = wildcard(d, "_" + rest);
                    if (noun != null) return withColor(noun, d.colors.get(color));
                }
            }
        }
        Entry wild = wildcard(d, path);
        return wild != null ? toWord(wild) : null;
    }

    private static Entry wildcard(Data d, String path) {
        Entry best = null;
        int bestLen = -1;
        for (Map.Entry<String, Entry> e : d.suffix.entrySet()) {
            if (path.endsWith(e.getKey()) && e.getKey().length() > bestLen) { best = e.getValue(); bestLen = e.getKey().length(); }
        }
        for (Map.Entry<String, Entry> e : d.prefix.entrySet()) {
            if (path.startsWith(e.getKey()) && e.getKey().length() > bestLen) { best = e.getValue(); bestLen = e.getKey().length(); }
        }
        return best;
    }

    /** Builds all accepted forms: with the article, without it, and with "un/una". */
    private static Word toWord(Entry e) {
        Set<String> accepted = new LinkedHashSet<>();
        addWithArticles(accepted, e.word, e.article);
        if (e.accept != null) for (String a : e.accept) if (a != null && !a.isBlank()) addWithArticles(accepted, a, e.article);
        return new Word(e.word, e.article, e.gender, List.copyOf(accepted), true);
    }

    private static void addWithArticles(Set<String> out, String word, String article) {
        out.add(word);
        if (article == null) return;
        out.add(article + " " + word);
        String indefinite = switch (article) {
            case "el" -> "un";
            case "la" -> "una";
            case "los" -> "unos";
            case "las" -> "unas";
            default -> null;
        };
        if (indefinite != null) out.add(indefinite + " " + word);
    }

    /** "lana" + red -> "lana roja": the color comes after the noun and agrees with it. */
    private static Word withColor(Entry noun, Color color) {
        boolean feminine = "f".equals(noun.gender) || (!"m".equals(noun.gender)
                && ("la".equals(noun.article) || "las".equals(noun.article)));
        boolean plural = "los".equals(noun.article) || "las".equals(noun.article);
        String adj = colorForm(color, feminine, plural);
        Set<String> adjForms = new LinkedHashSet<>();
        adjForms.add(adj);
        if (color.accept != null) for (String a : color.accept) if (a != null && !a.isBlank()) adjForms.add(a);

        Set<String> nouns = new LinkedHashSet<>();
        nouns.add(noun.word);
        if (noun.accept != null) for (String a : noun.accept) if (a != null && !a.isBlank()) nouns.add(a);

        Set<String> accepted = new LinkedHashSet<>();
        for (String n : nouns) for (String a : adjForms) addWithArticles(accepted, n + " " + a, noun.article);
        return new Word(noun.word + " " + adj, noun.article, noun.gender, List.copyOf(accepted), true);
    }

    static String colorForm(Color c, boolean feminine, boolean plural) {
        String base = feminine ? c.f : c.m;
        if (!plural || Boolean.TRUE.equals(c.invariable)) return base;
        return plural(base);
    }

    /** Spanish plural for an adjective: vowel -> +s, consonant -> +es (marrón -> marrones, gris -> grises). */
    static String plural(String w) {
        if (w.isEmpty()) return w;
        char last = w.charAt(w.length() - 1);
        if ("aeiouáéó".indexOf(last) >= 0) return w + "s";
        String noAccent = w.replace("ó", "o").replace("á", "a").replace("é", "e").replace("í", "i").replace("ú", "u");
        if (last == 'z') return noAccent.substring(0, noAccent.length() - 1) + "ces";
        return noAccent + "es";
    }

    /** A word from Minecraft's own Spanish item name (used when there's no entry). */
    public static Word fromGameTranslation(String spanishName) {
        String name = spanishName.trim();
        if (!name.isEmpty()) name = Character.toLowerCase(name.charAt(0)) + name.substring(1);
        return new Word(name, null, null, List.of(name), false);
    }

    /**
     * Decoy words for the word-list recognizer: other words that don't sound like the target,
     * so "anything vaguely similar" can't be forced into the right answer.
     */
    public static List<String> decoys(Word target, int count, long seed) {
        Data d = data;
        String targetSound = SpanishText.sound(SpanishText.normalize(target.word()));
        Set<String> targetForms = new HashSet<>();
        for (String a : target.accepted()) targetForms.add(SpanishText.normalize(a));
        List<Entry> pool = new ArrayList<>(d.exact.values());
        pool.addAll(d.suffix.values());
        pool.addAll(d.prefix.values());
        Collections.shuffle(pool, new Random(seed));
        Set<String> out = new LinkedHashSet<>();
        for (Entry e : pool) {
            if (out.size() >= count) break;
            String n = SpanishText.normalize(e.word);
            if (n.isEmpty() || targetForms.contains(n)) continue;
            String snd = SpanishText.sound(n);
            if (SpanishText.similarity(snd, targetSound) >= 0.5 || snd.contains(targetSound) || targetSound.contains(snd)) continue;
            out.add(e.word);
        }
        return new ArrayList<>(out);
    }

    public static boolean has(String itemId, boolean colorWords) {
        return lookup(itemId, colorWords) != null;
    }
}
