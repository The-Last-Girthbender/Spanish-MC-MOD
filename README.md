# Español Quest

A Fabric mod for **Minecraft Java Edition 26.3** that turns your inventory into Spanish practice
(Latin American Spanish).

Every **10th time** you pick up the same kind of item, a panel pops up with the item's Spanish
word, for example **la manzana** (feminine). You have **30 seconds** to say it into your microphone.
Say it right and you hear a chime (¡Correcto!). Run out of time and the items you just got are
deleted (¡Se acabó el tiempo!), and the chat shows you the correct word.

- Works offline: speech recognition runs on your computer with [Vosk](https://alphacephei.com/vosk/)
  (a one-time ~39 MB Spanish model download).
- ~190 hand-picked everyday words with their article (el / la / los / las), color words that
  agree with the noun (**la lana roja**, **el concreto negro**), and Minecraft's own Spanish
  (Mexico) names for everything else.
- A "sounds like" guide with the stressed syllable in capitals: **lah mahn-SAH-nah**.
- Saying the word with or without the article counts (*la manzana*, *manzana*, *una manzana*).
- Difficulty presets from *beginner* to *expert*; every setting can be changed in game.
- Can be installed together with Nihongo Quest (the Japanese version). They use separate settings,
  commands (`/espanol` vs `/nihongo`) and keys (**J** vs **H**). With both installed, each pickup
  can trigger a quiz in each language.

## Install

1. Install **Fabric Loader** for Minecraft 26.3: <https://fabricmc.net/use/installer/>
2. Download **Fabric API** for 26.3: <https://modrinth.com/mod/fabric-api>
3. Put `fabric-api-….jar` and `espanol-quest-1.0.0.jar` in your mods folder.
4. Optional: [Mod Menu](https://modrinth.com/mod/modmenu) adds a **Config** button in the Mods list.

The jar is built automatically on GitHub: **Actions** tab → latest run → download
**espanol-quest-jar**.

## Playing

- Speak clearly, in one breath, then pause briefly; each finished phrase is judged.
- **Press J** during a quiz to hear the word (needs a Spanish voice on your computer; see below).
- The microphone icon shows your volume: 2-3 green waves is ideal, red means too loud.
- After each attempt a bar shows your accuracy; the white tick is the "Match needed" level.

## Settings

Type **`/espanol`** in chat, or use **Mods → Español Quest → Config** with Mod Menu.
Three tabs: *Quiz*, *Display & Mic* (including **Move & resize panel**), *Pronunciation*.

| Difficulty | Time | Quiz every | Shows | Match needed |
|---|---|---|---|---|
| beginner | 45 s | 10 pickups | word, el/la, English, guide; reads it aloud | 60% |
| **normal** | 30 s | 10 pickups | word, el/la, English, guide | 75% |
| advanced | 20 s | 5 pickups | word, el/la, English (no guide) | 90% |
| expert | 15 s | 3 pickups | English only: remember the word | 95% |

Files live in `.minecraft/config/espanolquest/`: `config.json` (all settings, with comments) and
`vocabulary.json` (the words: add or fix any of them, including items from other mods).

## Spanish voice (for "hear it")

- **Windows:** Settings → Time & language → Language & region → Add a language →
  **Español (México)**, tick **Text-to-speech**, then restart Minecraft.
- **macOS:** System Settings → Accessibility → Spoken Content → System voice → Manage Voices →
  Spanish (e.g. Paulina).

## Troubleshooting

| Problem | Fix |
|---|---|
| Speech model missing | Download `vosk-model-small-es-0.42.zip` from <https://alphacephei.com/vosk/models> and unzip it into `config/espanolquest/`. |
| Microphone not heard | Windows Settings → Privacy & security → Microphone → allow desktop apps. Use **Test microphone** in `/espanol`. |
| Correct words fail | Lower **Match needed** (50-70%), and check the log: each quiz writes a "Quiz …" line saying what was heard. |

## For developers

Fabric Loom 1.18, Mojang names, Java 25. `src/main` runs on both sides (config, words, pickup
tracking, timer, item removal, networking); `src/client` has the microphone, Vosk, voice and screens.
