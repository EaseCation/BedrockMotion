package net.easecation.bedrockmotion.pack.definitions;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.Getter;
import org.cube.converter.model.impl.bedrock.BedrockGeometryModel;
import org.cube.converter.parser.bedrock.geometry.BedrockGeometryParser;
import net.easecation.bedrockmotion.pack.PackManager;
import net.easecation.bedrockmotion.pack.content.Content;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

@Getter
public class ModelDefinitions {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModelDefinitions.class);

    private final Map<String, BedrockGeometryModel> entityModels = new HashMap<>();
    private final Map<String, VisibleBounds> visibleBoundsMap = new HashMap<>();

    public ModelDefinitions(final PackManager packManager) {
        for (final Content content : packManager.getPacks()) {
            for (final String modelPath : content.getFilesDeep("models/", ".json")) {
                try {
                    final String jsonStr = content.getString(modelPath);
                    for (final BedrockGeometryModel bedrockGeometry : BedrockGeometryParser.parse(jsonStr)) {
                        if (modelPath.startsWith("models/entity/")) {
                            this.entityModels.put(bedrockGeometry.getIdentifier(), bedrockGeometry);
                        }
                    }
                    // Extract visible_bounds from raw JSON (CubeConverter doesn't parse these)
                    if (modelPath.startsWith("models/entity/")) {
                        parseVisibleBounds(jsonStr);
                    }
                } catch (Throwable e) {
                    LOGGER.warn("Failed to parse model definition {}", modelPath);
                }
            }
        }
    }

    private void parseVisibleBounds(String jsonStr) {
        try {
            JsonObject root = JsonParser.parseString(jsonStr).getAsJsonObject();
            JsonArray geometries = root.getAsJsonArray("minecraft:geometry");
            if (geometries == null) return;
            for (JsonElement geomElem : geometries) {
                JsonObject geom = geomElem.getAsJsonObject();
                JsonObject desc = geom.getAsJsonObject("description");
                if (desc == null || !desc.has("identifier")) continue;
                String identifier = desc.get("identifier").getAsString();

                float width = desc.has("visible_bounds_width") ? desc.get("visible_bounds_width").getAsFloat() : 1.0f;
                float height = desc.has("visible_bounds_height") ? desc.get("visible_bounds_height").getAsFloat() : 2.0f;
                float ox = 0, oy = 1, oz = 0;
                if (desc.has("visible_bounds_offset")) {
                    JsonArray offset = desc.getAsJsonArray("visible_bounds_offset");
                    if (offset != null && offset.size() >= 3) {
                        ox = offset.get(0).getAsFloat();
                        oy = offset.get(1).getAsFloat();
                        oz = offset.get(2).getAsFloat();
                    }
                }
                this.visibleBoundsMap.put(identifier, new VisibleBounds(width, height, ox, oy, oz));
            }
        } catch (Throwable ignored) {
        }
    }
}
