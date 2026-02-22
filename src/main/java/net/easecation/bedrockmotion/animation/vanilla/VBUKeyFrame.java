package net.easecation.bedrockmotion.animation.vanilla;

public record VBUKeyFrame(
        float timestamp,
        String[] preTarget,
        String[] postTarget,
        boolean hasSeparatePrePost,
        AnimateTransformation.Interpolation interpolation
) {
    public VBUKeyFrame(float timestamp, String[] value, AnimateTransformation.Interpolation interpolation) {
        this(timestamp, value, value, false, interpolation);
    }
}
