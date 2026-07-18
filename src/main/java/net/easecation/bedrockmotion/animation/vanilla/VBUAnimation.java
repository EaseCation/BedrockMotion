package net.easecation.bedrockmotion.animation.vanilla;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class VBUAnimation {
    private final float lengthInSeconds;
    private final boolean looping;
    private final Map<String, List<AnimateTransformation>> boneAnimations;
    private final boolean immutable;

    public VBUAnimation(final float lengthInSeconds, final boolean looping,
                        final Map<String, List<AnimateTransformation>> boneAnimations) {
        this(lengthInSeconds, looping, boneAnimations, false);
    }

    private VBUAnimation(final float lengthInSeconds, final boolean looping,
                         final Map<String, List<AnimateTransformation>> boneAnimations,
                         final boolean immutable) {
        this.lengthInSeconds = lengthInSeconds;
        this.looping = looping;
        this.boneAnimations = boneAnimations;
        this.immutable = immutable;
    }

    public float lengthInSeconds() {
        return this.lengthInSeconds;
    }

    public boolean looping() {
        return this.looping;
    }

    public Map<String, List<AnimateTransformation>> boneAnimations() {
        return this.boneAnimations;
    }

    public VBUAnimation immutableCopy() {
        if (this.immutable) return this;
        final Map<String, List<AnimateTransformation>> copy = new LinkedHashMap<>();
        this.boneAnimations.forEach((bone, transformations) -> copy.put(bone,
                transformations.stream().map(AnimateTransformation::immutableCopy).toList()));
        return new VBUAnimation(this.lengthInSeconds, this.looping, Map.copyOf(copy), true);
    }

    @Override
    public boolean equals(final Object object) {
        if (this == object) return true;
        if (!(object instanceof VBUAnimation that)) return false;
        return Float.compare(this.lengthInSeconds, that.lengthInSeconds) == 0
                && this.looping == that.looping
                && Objects.equals(this.boneAnimations, that.boneAnimations);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.lengthInSeconds, this.looping, this.boneAnimations);
    }

    @Override
    public String toString() {
        return "VBUAnimation[lengthInSeconds=" + this.lengthInSeconds
                + ", looping=" + this.looping + ", boneAnimations=" + this.boneAnimations + ']';
    }

    public static class Builder {
        private final float lengthInSeconds;
        private final Map<String, List<AnimateTransformation>> transformations = new HashMap<>();
        private boolean looping;

        public static Builder create(final float lengthInSeconds) {
            return new Builder(lengthInSeconds);
        }

        private Builder(final float lengthInSeconds) {
            this.lengthInSeconds = lengthInSeconds;
        }

        public Builder looping() {
            this.looping = true;
            return this;
        }

        public Builder addBoneAnimation(String name, AnimateTransformation transformation) {
            transformations.computeIfAbsent(name, key -> new ArrayList<>()).add(transformation);
            return this;
        }

        public VBUAnimation build() {
            return new VBUAnimation(this.lengthInSeconds, this.looping, this.transformations);
        }
    }
}
