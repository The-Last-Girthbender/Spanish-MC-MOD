package com.espanolquest.client;

import com.espanolquest.client.speech.SpeechService;
import com.espanolquest.client.speech.VoiceService;
import com.espanolquest.config.ModConfig;
import com.espanolquest.network.Payloads;
import com.espanolquest.network.Payloads.ChallengeAnswer;
import com.espanolquest.network.Payloads.ChallengeResolved;
import com.espanolquest.network.Payloads.ChallengeStart;
import com.espanolquest.vocab.SpanishText;
import com.espanolquest.vocab.Pronunciation;
import com.espanolquest.vocab.Vocabulary.Word;
import com.espanolquest.vocab.Vocabulary;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The quiz currently on screen (client side). All methods run on the game thread. */
public final class ClientChallenge {
    public enum State { LISTENING, WAITING, PASSED, FAILED }

    private static ClientChallenge current;
    private static Word lastWord;       // for the "hear it" key after a quiz ends
    private static long lastVoiceNotice;
    private static long lastUnavailableNotice;

    final int id;
    final ItemStack icon; // null in the layout editor's preview (items can't be made outside a world)
    final Component itemName;
    final Word word;
    final int totalTicks;
    final List<String> targets;
    /** The same forms as they sound (b/v, s/z/c, ll/y, silent h all alike), for near-miss credit. */
    final List<String> soundTargets;
    int ticksLeft;
    int amount;
    int removed;
    int afterTicks; // countdown for how long the result stays on screen / how long to wait for the server
    State state = State.LISTENING;
    /** When the panel appeared (for its slide-in animation). */
    final long shownAt = System.currentTimeMillis();
    volatile String heard = "";
    final String guide;       // "sounds like" for the whole phrase: "lah mahn-SAH-nah"
    final String guideNoArticle; // without the article, for when el/la is hidden: "mahn-SAH-nah"

    // ---- what happened during this quiz, for the log (written from the microphone thread) ----
    private final List<String> heardLog = java.util.Collections.synchronizedList(new ArrayList<>());
    private volatile String passReason;
    private volatile double bestScore;
    private volatile String bestHeard = "";
    private double strictnessUsed;
    /** Set when a finished utterance was much shorter than the word: the player probably paused mid-word. */
    volatile boolean splitWarning;

    // ---- accuracy shown to the player: one % per attempt (finished utterance) ----
    /** Score of the latest attempt, 0-100, or -1 before the first attempt. */
    volatile int lastPct = -1;
    /** Best attempt so far in this quiz, 0-100, or -1. */
    volatile int bestPct = -1;
    volatile long lastPctTime;
    int neededPct;

    /** The word list must be at least this sure of its pick to pass you. */
    private static final double LIST_MIN_CONFIDENCE = 0.5;

    /** Records one attempt's score. Both recognizers report the same utterance, so keep the higher one. */
    private void recordAttempt(int pct) {
        long now = System.currentTimeMillis();
        if (now - lastPctTime < 400 && pct < lastPct) return; // same utterance, already have a better score
        lastPct = pct;
        lastPctTime = now;
        if (pct > bestPct) bestPct = pct;
    }
    final String tip;

    private ClientChallenge(int id, ItemStack icon, Word word, int totalTicks, int amount) {
        this(id, icon, icon.getHoverName(), word, totalTicks, amount);
    }

    private ClientChallenge(int id, ItemStack icon, Component itemName, Word word, int totalTicks, int amount) {
        this.id = id;
        this.icon = icon;
        this.itemName = itemName;
        this.word = word;
        this.totalTicks = Math.max(1, totalTicks);
        this.ticksLeft = this.totalTicks;
        this.amount = amount;
        Set<String> t = new LinkedHashSet<>();
        for (String form : word.accepted()) {
            String n = SpanishText.normalize(form);
            if (!n.isEmpty()) t.add(n);
        }
        this.targets = new ArrayList<>(t);
        Set<String> snd = new LinkedHashSet<>();
        for (String target : this.targets) snd.add(SpanishText.sound(target));
        snd.remove("");
        this.soundTargets = new ArrayList<>(snd);
        this.guide = Pronunciation.guide(word.display());
        this.guideNoArticle = Pronunciation.guide(word.word());
        this.tip = Pronunciation.tip(word.display());
    }

    /** What to give the voice: the word with its article ("la manzana"). */
    static String spoken(Word w) {
        return w.display();
    }

    /** "Hear it" key: reads the current (or last) quiz word aloud. */
    static void hear() {
        ClientChallenge c = current;
        Word w = c != null ? c.word : lastWord;
        if (w == null) return;
        if (!VoiceService.speak(spoken(w))) {
            long now = System.currentTimeMillis();
            if (now - lastVoiceNotice > 20_000) {
                lastVoiceNotice = now;
                chat(Component.literal("[Español Quest] ").withStyle(ChatFormatting.GOLD)
                        .append(Component.literal(VoiceService.unavailableReason()).withStyle(ChatFormatting.GRAY)));
            }
        }
    }

    public static ClientChallenge current() {
        return current;
    }

    /** A pretend quiz for the "Move & resize panel" editor. Never sent to the server. */
    static ClientChallenge preview() {
        Word w = Vocabulary.lookup("minecraft:apple", false);
        if (w == null) w = new Word("manzana", "la", null, List.of("manzana", "la manzana"), true);
        ClientChallenge c = new ClientChallenge(-1, null, Component.literal("Apple"), w, 600, 1);
        c.ticksLeft = 380;
        c.heard = "りんご";
        c.neededPct = (int) Math.round(ModConfig.get().matchStrictness() * 100);
        c.lastPct = 72;
        c.bestPct = 72;
        return c;
    }

    // ------------------------------------------------------------------ events

    static void onStart(ChallengeStart p) {
        Minecraft mc = Minecraft.getInstance();
        ModConfig cfg = ModConfig.get();
        clear();

        Identifier itemKey = Identifier.tryParse(p.itemId());
        Item item = itemKey == null ? null : BuiltInRegistries.ITEM.getValue(itemKey);
        if (item == null) {
            answer(p.challengeId(), Payloads.ANSWER_UNAVAILABLE);
            return;
        }
        ItemStack icon = new ItemStack(item);

        Word word = Vocabulary.lookup(p.itemId(), cfg.includeColorWords);
        if (word == null && !cfg.onlyItemsWithBuiltInWords) word = GameSpanish.wordFor(item);
        if (word == null) {
            answer(p.challengeId(), Payloads.ANSWER_UNAVAILABLE);
            return;
        }

        if (!SpeechService.isReady()) {
            answer(p.challengeId(), Payloads.ANSWER_UNAVAILABLE);
            SpeechService.retryIfFailed();
            long now = System.currentTimeMillis();
            if (now - lastUnavailableNotice > 60_000) {
                lastUnavailableNotice = now;
                chat(Component.literal("[Español Quest] ").withStyle(ChatFormatting.GOLD)
                        .append(Component.literal(SpeechService.unavailableReason()).withStyle(ChatFormatting.GRAY)));
            }
            return;
        }

        ClientChallenge c = new ClientChallenge(p.challengeId(), icon, word, p.timeLimitTicks(), p.amountAtStake());
        current = c;
        lastWord = word;
        sound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.6f);

        double strictness = cfg.matchStrictness();
        c.strictnessUsed = strictness;
        c.neededPct = (int) Math.round(strictness * 100);
        List<String> phrases = new ArrayList<>(word.accepted());
        phrases.addAll(Vocabulary.decoys(word, 20, p.challengeId()));
        SpeechService.listen(
                phrases,
                (heardText, confidence) -> c.isExact(heardText, confidence),
                heardText -> c.isMatch(heardText, strictness),
                heardText -> c.heard = heardText,
                () -> mc.execute(() -> c.onRecognised()),
                error -> mc.execute(() -> {
                    if (current == c && c.state == State.LISTENING) {
                        chat(Component.literal("[Español Quest] ").withStyle(ChatFormatting.GOLD)
                                .append(Component.literal(error).withStyle(ChatFormatting.GRAY)));
                        answer(c.id, Payloads.ANSWER_UNAVAILABLE);
                        c.state = State.WAITING;
                        c.afterTicks = 100;
                    }
                }));
        if (cfg.speakAtQuizStart()) VoiceService.speak(spoken(word));
    }

    /** The word-list recognizer (target + decoys) picked one of the target's own spellings. */
    boolean isExact(String heardText, double confidence) {
        String h = SpanishText.normalize(heardText);
        boolean picked = false;
        for (String t : targets) if (h.equals(t)) picked = true;
        int pct = (int) Math.round(confidence * 100);
        boolean ok = picked && confidence >= LIST_MIN_CONFIDENCE;
        heardLog.add("word list: \"" + heardText + "\" " + pct + "% sure" + (ok ? " ✔" : picked ? " (too unsure)" : ""));
        if (picked) recordAttempt(pct);
        if (ok && passReason == null) {
            passReason = "word list picked \"" + heardText + "\", " + pct + "% sure (out of the word + 20 decoys)";
        }
        return ok;
    }

    /** Compares by spelling and by sound (so "baca" still scores well for "vaca"). */
    boolean isMatch(String heardText, double strictness) {
        String h = SpanishText.normalize(heardText);
        if (h.isEmpty()) return false;
        strictnessUsed = strictness;
        String hs = SpanishText.sound(h);
        double score = 0;
        for (String t : targets) score = Math.max(score, SpanishText.matchScore(h, t));
        for (String t : soundTargets) score = Math.max(score, SpanishText.matchScore(hs, t));
        boolean ok = strictness >= 1.0 ? score >= 1.0 : score > 0 && score >= strictness;

        int pct = (int) Math.round(score * 100);
        heardLog.add("open: \"" + heardText + "\" " + pct + "%" + (ok ? " ✔" : ""));
        recordAttempt(pct);
        if (score >= bestScore) {
            bestScore = score;
            bestHeard = heardText;
        }
        if (ok && passReason == null) passReason = "sound match " + pct + "% on \"" + heardText + "\"";

        // Much shorter than the word and not a match: they most likely stopped partway through.
        int shortest = Integer.MAX_VALUE;
        for (String t : soundTargets) shortest = Math.min(shortest, t.length());
        if (!ok && shortest != Integer.MAX_VALUE && hs.length() < shortest * 0.6) splitWarning = true;
        return ok;
    }

    private void logResult(String outcome) {
        StringBuilder line = new StringBuilder();
        line.append("Quiz ").append(word.display()).append(" (").append(guide).append("): ").append(outcome);
        if (passReason != null && outcome.equals("passed")) {
            line.append(" by ").append(passReason);
        } else if (bestScore > 0) {
            line.append(" — best sound match ").append(Math.round(bestScore * 100)).append("% on \"")
                    .append(bestHeard).append("\", needed ").append(Math.round(strictnessUsed * 100)).append('%');
        }
        List<String> heardCopy;
        synchronized (heardLog) { heardCopy = new ArrayList<>(heardLog); }
        line.append(" | heard: ").append(heardCopy.isEmpty() ? "(nothing)" : String.join(", ", heardCopy));
        com.espanolquest.EspanolQuest.LOGGER.info(line.toString());
    }

    private void onRecognised() {
        if (current != this || (state != State.LISTENING && state != State.WAITING)) return;
        state = State.PASSED;
        afterTicks = 60;
        SpeechService.stop();
        answer(id, Payloads.ANSWER_PASSED);
        sound(SoundEvents.PLAYER_LEVELUP, 1.4f);
    }

    static void onResolved(ChallengeResolved p) {
        ClientChallenge c = current;
        if (c == null || c.id != p.challengeId()) return;
        SpeechService.stop();
        switch (p.outcome()) {
            case Payloads.OUTCOME_PASSED -> {
                c.logResult("passed");
                c.state = State.PASSED;
                c.afterTicks = Math.max(c.afterTicks, 40);
                MutableComponent ok = Component.literal("¡Correcto! ").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD);
                if (c.lastPct >= 0) ok.append(Component.literal(c.lastPct + "% ").withStyle(ChatFormatting.GREEN));
                chat(ok.append(c.itemName.copy().withStyle(ChatFormatting.WHITE))
                        .append(Component.literal(" = ").withStyle(ChatFormatting.GRAY))
                        .append(wordLine(c.word)));
            }
            case Payloads.OUTCOME_FAILED -> {
                c.logResult("failed");
                c.state = State.FAILED;
                c.removed = p.removed();
                c.afterTicks = 100;
                sound(SoundEvents.VILLAGER_NO, 1.0f);
                MutableComponent msg = Component.literal("¡Se acabó el tiempo! ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
                if (p.removed() > 0) {
                    msg.append(Component.literal("Lost " + p.removed() + "× ").withStyle(ChatFormatting.RED))
                            .append(c.itemName.copy().withStyle(ChatFormatting.WHITE))
                            .append(Component.literal(". ").withStyle(ChatFormatting.RED));
                }
                if (c.bestPct >= 0) {
                    msg.append(Component.literal("Best try " + c.bestPct + "% (needed " + c.neededPct + "%). ")
                            .withStyle(ChatFormatting.GOLD));
                }
                msg.append(Component.literal("The word was ").withStyle(ChatFormatting.GRAY)).append(wordLine(c.word));
                chat(msg);
                if (ModConfig.get().speakOnFail) VoiceService.speak(spoken(c.word));
            }
            default -> clear(); // cancelled
        }
    }

    static void tick(Minecraft mc) {
        ClientChallenge c = current;
        if (c == null || mc.isPaused()) return;
        switch (c.state) {
            case LISTENING -> {
                if (--c.ticksLeft <= 0) {
                    // Time's up here; whatever is being said right now still gets judged,
                    // and the server (which waits a moment longer) makes the final call.
                    SpeechService.finish();
                    c.state = State.WAITING;
                    c.afterTicks = 100;
                }
            }
            case WAITING, PASSED, FAILED -> {
                if (--c.afterTicks <= 0) clear();
            }
        }
    }

    static void clear() {
        SpeechService.stop();
        current = null;
    }

    // ------------------------------------------------------------------ helpers

    static MutableComponent wordLine(Word w) {
        MutableComponent line = Component.literal(w.display()).withStyle(ChatFormatting.YELLOW);
        String gender = w.genderLabel();
        if (gender != null) line.append(Component.literal(" (" + gender + ")").withStyle(ChatFormatting.AQUA));
        String guide = Pronunciation.guide(w.display());
        if (guide != null && ModConfig.get().showPronunciation()) {
            line.append(Component.literal(" ≈ " + guide).withStyle(ChatFormatting.GREEN));
        }
        return line;
    }


    private static void answer(int id, int status) {
        if (ClientPlayNetworking.canSend(ChallengeAnswer.TYPE)) {
            ClientPlayNetworking.send(new ChallengeAnswer(id, status));
        }
    }

    private static void chat(Component msg) {
        if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.sendSystemMessage(msg);
    }

    private static void sound(SoundEvent event, float pitch) {
        if (!ModConfig.get().playSounds) return;
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(event, pitch));
    }
}
