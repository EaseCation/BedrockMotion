package net.easecation.bedrockmotion.pack.definitions;

import lombok.Getter;
import net.easecation.bedrockmotion.animation.Animation;
import net.easecation.bedrockmotion.animation.vanilla.AnimateBuilder;
import net.easecation.bedrockmotion.animation.vanilla.VBUAnimation;
import net.easecation.bedrockmotion.pack.PackManager;
import net.easecation.bedrockmotion.pack.content.Content;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

@Getter
public class AnimationDefinitions {
    private static final Logger LOGGER = LoggerFactory.getLogger(AnimationDefinitions.class);

    private final Map<String, AnimationData> animations;

    public AnimationDefinitions(final PackManager packManager) {
        final Map<String, AnimationData> animations = new HashMap<>();
        for (final Content content : packManager.getPacks()) {
            for (final String modelPath : content.getFilesDeep("animations/", ".json")) {
                try {
                    for (final Animation animation : Animation.parse(content.getJson(modelPath))) {
                        animations.put(animation.getIdentifier(), new AnimationData(animation, AnimateBuilder.build(animation)));
                    }
                } catch (Throwable e) {
                    LOGGER.warn("Failed to parse animation definition {}", modelPath, e);
                }
            }
        }
        this.animations = animations;
    }

    public AnimationDefinitions(final Map<String, AnimationData> animations) {
        this.animations = Map.copyOf(animations);
    }

    public record AnimationData(Animation animation, VBUAnimation compiled) {}
}
