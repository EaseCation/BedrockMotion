package net.easecation.bedrockmotion.pack.definitions;

import lombok.Getter;
import net.easecation.bedrockmotion.controller.AnimationController;
import net.easecation.bedrockmotion.pack.PackManager;
import net.easecation.bedrockmotion.pack.content.Content;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

@Getter
public class AnimationControllerDefinitions {
    private static final Logger LOGGER = LoggerFactory.getLogger(AnimationControllerDefinitions.class);

    private final Map<String, AnimationController> controllers;

    public AnimationControllerDefinitions(final PackManager packManager) {
        final Map<String, AnimationController> controllers = new HashMap<>();
        for (final Content content : packManager.getPacks()) {
            for (final String path : content.getFilesDeep("animation_controllers/", ".json")) {
                try {
                    for (final AnimationController controller : AnimationController.parse(content.getJson(path))) {
                        controllers.put(controller.getIdentifier(), controller);
                    }
                } catch (Throwable e) {
                    LOGGER.warn("Failed to parse animation controller definition {}", path, e);
                }
            }
        }

        this.controllers = controllers;
        if (!this.controllers.isEmpty()) {
            LOGGER.debug("[PackManager] Loaded {} animation controllers", this.controllers.size());
        }
    }

    public AnimationControllerDefinitions(final Map<String, AnimationController> controllers) {
        this.controllers = Map.copyOf(controllers);
    }
}
