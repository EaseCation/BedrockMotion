package net.easecation.bedrockmotion.pack;

import lombok.Getter;
import net.easecation.bedrockmotion.pack.content.Content;
import net.easecation.bedrockmotion.pack.definitions.*;
import net.easecation.bedrockmotion.pack.definitions.controller.RenderControllerDefinitions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Getter
public class PackManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(PackManager.class);

    private final List<Content> packs;
    private final RenderControllerDefinitions renderControllerDefinitions;
    private final EntityDefinitions entityDefinitions;
    private final ModelDefinitions modelDefinitions;
    private final MaterialDefinitions materialDefinitions;
    private final AnimationDefinitions animationDefinitions;
    private final AnimationControllerDefinitions animationControllerDefinitions;

    public PackManager(final List<Content> customPacks) {
        final List<Content> allPacks = new ArrayList<>();

        // Load vanilla resource pack as base layer
        try (InputStream is = PackManager.class.getResourceAsStream("/libs/vanilla_packs/vanilla.mcpack")) {
            if (is != null) {
                allPacks.add(new Content(is.readAllBytes()));
                LOGGER.info("[PackManager] Loaded vanilla resource pack");
            } else {
                LOGGER.warn("[PackManager] Vanilla resource pack not found in library resources");
            }
        } catch (IOException e) {
            LOGGER.warn("[PackManager] Failed to load vanilla resource pack", e);
        }

        // Custom packs on top (can override vanilla definitions)
        allPacks.addAll(customPacks);
        this.packs = allPacks;

        this.renderControllerDefinitions = new RenderControllerDefinitions(this);
        this.entityDefinitions = new EntityDefinitions(this);
        this.modelDefinitions = new ModelDefinitions(this);
        this.materialDefinitions = new MaterialDefinitions(this);
        this.animationDefinitions = new AnimationDefinitions(this);
        this.animationControllerDefinitions = new AnimationControllerDefinitions(this);
    }
}
