package net.easecation.bedrockmotion.animation.vanilla;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record VBUAnimation(float lengthInSeconds, boolean looping, Map<String, List<AnimateTransformation>> boneAnimations) {
    public static class Builder {
        private final float lengthInSeconds;
        private final Map<String, List<AnimateTransformation>> transformations = new HashMap<>();
        private boolean looping;

        public static Builder create(float lengthInSeconds) {
            return new Builder(lengthInSeconds);
        }

        private Builder(float lengthInSeconds) {
            this.lengthInSeconds = lengthInSeconds;
        }

        public Builder looping() {
            this.looping = true;
            return this;
        }

        public Builder addBoneAnimation(String name, AnimateTransformation transformation) {
            this.transformations.computeIfAbsent(name, (namex) -> new ArrayList<>()).add(transformation);
            return this;
        }

        public VBUAnimation build() {
            return new VBUAnimation(this.lengthInSeconds, this.looping, this.transformations);
        }
    }
}
