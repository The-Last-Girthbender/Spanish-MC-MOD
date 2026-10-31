package com.espanolquest.client;

import com.espanolquest.EspanolQuest;
import com.espanolquest.client.speech.SpeechService;
import com.espanolquest.config.ModConfig;
import com.espanolquest.network.Payloads.ChallengeResolved;
import com.espanolquest.network.Payloads.ChallengeStart;
import com.espanolquest.vocab.Vocabulary;
import com.mojang.blaze3d.platform.InputConstants;
import com.espanolquest.client.speech.VoiceService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

public class EspanolQuestClient implements ClientModInitializer {
    private static KeyMapping hearKey;

    /** Display name of the "hear it" key (e.g. "H"), or null if unbound. */
    static String hearKeyName() {
        if (hearKey == null || hearKey.isUnbound()) return null;
        return hearKey.getTranslatedKeyMessage().getString();
    }

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(EspanolQuest.id("main"));
        hearKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.espanolquest.hear", InputConstants.KEY_J, category));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (hearKey.consumeClick()) ClientChallenge.hear();
        });
        VoiceService.check();

        ClientPlayNetworking.registerGlobalReceiver(ChallengeStart.TYPE,
                (payload, context) -> ClientChallenge.onStart(payload));
        ClientPlayNetworking.registerGlobalReceiver(ChallengeResolved.TYPE,
                (payload, context) -> ClientChallenge.onResolved(payload));

        ClientTickEvents.END_CLIENT_TICK.register(ClientChallenge::tick);
        ClientTickEvents.END_CLIENT_TICK.register(ConfigScreen::tick);

        // /espanol (or /espanol config) opens the settings screen.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
            dispatcher.register(ClientCommands.literal("espanol")
                    .executes(context -> {
                        ConfigScreen.openNextTick = true;
                        return 1;
                    })
                    .then(ClientCommands.literal("config").executes(context -> {
                        ConfigScreen.openNextTick = true;
                        return 1;
                    })));
        });

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, EspanolQuest.id("challenge"),
                ChallengeHud::extract);

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            // Pick up config/vocabulary edits each time you join a world.
            ModConfig.load();
            Vocabulary.load();
            GameSpanish.invalidate();
            SpeechService.retryIfFailed();
            client.execute(() -> {
                String msg = switch (SpeechService.status()) {
                    case READY -> null;
                    case DOWNLOADING -> "Downloading the Spanish speech model in the background — quizzes start once it's done.";
                    default -> SpeechService.unavailableReason();
                };
                if (msg != null && !msg.isBlank() && client.player != null) {
                    client.player.sendSystemMessage(Component.literal("[Español Quest] ").withStyle(ChatFormatting.GOLD)
                            .append(Component.literal(msg).withStyle(ChatFormatting.GRAY)));
                }
            });
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(ClientChallenge::clear));

        SpeechService.logMicrophones();
        SpeechService.prepare(); // download (first launch) + load the model in the background
    }
}
