package com.espanolquest.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.espanolquest.EspanolQuest;
import com.espanolquest.vocab.Vocabulary;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Item;

import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

/**
 * Fallback words from Minecraft's own Spanish (Mexico) translation, es_mx, for items without
 * an entry in the word list. These have no article, since the game files don't say the gender.
 */
final class GameSpanish {
    private GameSpanish() {}

    private static Map<String, String> names;

    static void invalidate() {
        names = null;
    }

    static Vocabulary.Word wordFor(Item item) {
        if (names == null) load();
        String name = names.get(item.getDescriptionId());
        if (name == null || name.isBlank()) return null;
        return Vocabulary.fromGameTranslation(name);
    }

    private static void load() {
        Map<String, String> map = new HashMap<>();
        try {
            ResourceManager rm = Minecraft.getInstance().getResourceManager();
            for (String namespace : rm.getNamespaces()) {
                Identifier file = Identifier.fromNamespaceAndPath(namespace, "lang/es_mx.json");
                for (Resource res : rm.getResourceStack(file)) {
                    try (Reader r = res.openAsReader()) {
                        for (Map.Entry<String, JsonElement> e : JsonParser.parseReader(r).getAsJsonObject().entrySet()) {
                            if (e.getValue().isJsonPrimitive()) map.put(e.getKey(), e.getValue().getAsString());
                        }
                    } catch (Exception e) {
                        EspanolQuest.LOGGER.debug("Skipping {}: {}", file, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            EspanolQuest.LOGGER.warn("Could not load Minecraft's Spanish translation: {}", e.getMessage());
        }
        names = map;
    }
}
