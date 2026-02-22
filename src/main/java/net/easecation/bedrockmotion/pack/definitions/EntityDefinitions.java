package net.easecation.bedrockmotion.pack.definitions;

import lombok.Getter;
import org.cube.converter.data.bedrock.BedrockEntityData;
import org.cube.converter.parser.bedrock.data.impl.BedrockEntityParser;
import net.easecation.bedrockmotion.pack.PackManager;
import net.easecation.bedrockmotion.pack.content.Content;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

// https://wiki.bedrock.dev/entities/entity-intro-rp.html
@Getter
public class EntityDefinitions {
    private static final Logger LOGGER = LoggerFactory.getLogger(EntityDefinitions.class);

    private final Map<String, EntityDefinition> entities = new HashMap<>();

    public EntityDefinitions(final PackManager packManager) {
        for (final Content content : packManager.getPacks()) {
            for (final String entityPath : content.getFilesDeep("entity/", ".json")) {
                try {
                    final BedrockEntityData entityData = BedrockEntityParser.parse(content.getString(entityPath));
                    final String identifier = entityData.getIdentifier();
                    this.entities.put(identifier, new EntityDefinition(identifier, entityData));
                } catch (Throwable e) {
                    LOGGER.warn("Failed to parse entity definition {}", entityPath);
                }
            }
        }
    }

    public record EntityDefinition(String identifier, BedrockEntityData entityData) {
    }
}
