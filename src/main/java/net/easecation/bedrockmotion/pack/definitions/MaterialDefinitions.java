package net.easecation.bedrockmotion.pack.definitions;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.easecation.bedrockmotion.pack.PackManager;
import net.easecation.bedrockmotion.pack.content.Content;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Simplified material definitions for BedrockMotion.
 * Stores material name -> material identifier mappings.
 * Full material rendering properties are resolved by the consumer (VBU/ViaBedrock).
 */
public class MaterialDefinitions {
    private static final Logger LOGGER = LoggerFactory.getLogger(MaterialDefinitions.class);

    private final Map<String, String> materialNames = new HashMap<>();

    public MaterialDefinitions(final PackManager packManager) {
        for (final Content content : packManager.getPacks()) {
            if (!content.contains("materials/entity.material")) {
                continue;
            }

            try {
                final JsonObject root = JsonParser.parseString(content.getString("materials/entity.material")).getAsJsonObject();
                if (root.has("materials")) {
                    final JsonObject materials = root.getAsJsonObject("materials");
                    for (Map.Entry<String, JsonElement> entry : materials.entrySet()) {
                        String name = entry.getKey();
                        // Strip parent reference (e.g., "entity_emissive_alpha:entity" -> "entity_emissive_alpha")
                        if (name.contains(":")) {
                            name = name.substring(0, name.indexOf(':'));
                        }
                        materialNames.put(name, name);
                    }
                }
            } catch (Throwable e) {
                LOGGER.warn("Failed to parse entity material!");
            }
        }
    }

    public boolean hasMaterial(final String name) {
        return materialNames.containsKey(name);
    }

    public String getMaterialName(final String name) {
        return materialNames.get(name);
    }
}
