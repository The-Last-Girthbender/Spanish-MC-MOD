package com.espanolquest;

import com.espanolquest.config.ModConfig;
import com.espanolquest.network.Payloads;
import com.espanolquest.server.ChallengeManager;
import com.espanolquest.vocab.Vocabulary;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/** Common entrypoint: runs on both the client and dedicated servers. */
public class EspanolQuest implements ModInitializer {
    public static final String MOD_ID = "espanolquest";
    public static final Logger LOGGER = LoggerFactory.getLogger("Español Quest");

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    /** .minecraft/config/espanolquest/ */
    public static Path configDir() {
        return FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
    }

    @Override
    public void onInitialize() {
        ModConfig.load();
        Vocabulary.load();
        Payloads.register();
        ChallengeManager.init();
        LOGGER.info("Español Quest loaded — ¡buena suerte! (Good luck!)");
    }
}
