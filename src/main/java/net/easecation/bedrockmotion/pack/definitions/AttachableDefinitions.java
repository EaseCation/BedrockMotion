package net.easecation.bedrockmotion.pack.definitions;

import lombok.Getter;
import net.easecation.bedrockmotion.pack.PackManager;
import net.easecation.bedrockmotion.pack.content.Content;
import org.cube.converter.data.bedrock.BedrockAttachableData;
import org.cube.converter.parser.bedrock.data.BedrockDataParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resource-pack ordered attachable definitions and their item candidate index. */
@Getter
public final class AttachableDefinitions {
    private static final Logger LOGGER = LoggerFactory.getLogger(AttachableDefinitions.class);

    private final Map<String, AttachableDefinition> attachables;
    private final Map<String, List<AttachableDefinition>> itemCandidates;

    public AttachableDefinitions(final PackManager packManager) {
        final LinkedHashMap<String, AttachableDefinition> definitions = new LinkedHashMap<>();
        final List<Content> packs = packManager.getPacks();
        for (int packPriority = 0; packPriority < packs.size(); packPriority++) {
            final Content content = packs.get(packPriority);
            final List<String> paths = new ArrayList<>(content.getFilesDeep("attachables/", ".json"));
            paths.sort(String::compareTo);
            for (String path : paths) {
                try {
                    final BedrockAttachableData data = BedrockDataParser.parseAttachable(content.getString(path), path);
                    if (data != null) {
                        definitions.put(data.getIdentifier(),
                                new AttachableDefinition(data.getIdentifier(), data, path, packPriority));
                    }
                } catch (Throwable throwable) {
                    LOGGER.warn("Failed to parse attachable definition {}: {}", path, throwable.getMessage());
                }
            }
        }

        this.attachables = Map.copyOf(definitions);
        final LinkedHashMap<String, List<AttachableDefinition>> candidates = new LinkedHashMap<>();
        for (AttachableDefinition definition : definitions.values()) {
            for (String itemIdentifier : definition.data().getItemConditions().keySet()) {
                candidates.computeIfAbsent(itemIdentifier, ignored -> new ArrayList<>()).add(definition);
            }
        }
        final Comparator<AttachableDefinition> order = Comparator
                .comparingInt(AttachableDefinition::packPriority).reversed()
                .thenComparing(AttachableDefinition::identifier);
        candidates.replaceAll((ignored, values) -> values.stream().sorted(order).toList());
        this.itemCandidates = Map.copyOf(candidates);
    }

    public List<AttachableDefinition> candidatesFor(final String itemIdentifier) {
        return itemCandidates.getOrDefault(itemIdentifier, List.of());
    }

    public record AttachableDefinition(String identifier, BedrockAttachableData data,
                                       String sourcePath, int packPriority) {
    }
}
