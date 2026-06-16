package net.easecation.bedrockmotion.animation.vanilla;

import net.easecation.bedrockmotion.animation.vanilla.AnimateTransformation.ResolvedComponent;

public record VBUKeyFrame(
        float timestamp,
        String[] preTarget,
        String[] postTarget,
        boolean hasSeparatePrePost,
        AnimateTransformation.Interpolation interpolation,
        // Pre-resolved xyz components (constant double or cached AST), computed once at build time so the
        // per-frame interpolation path never re-parses the MoLang source strings. See ResolvedComponent.
        ResolvedComponent[] pre,
        ResolvedComponent[] post
) {
    public VBUKeyFrame(float timestamp, String[] preTarget, String[] postTarget, boolean hasSeparatePrePost,
                       AnimateTransformation.Interpolation interpolation) {
        this(timestamp, preTarget, postTarget, hasSeparatePrePost, interpolation,
                AnimateTransformation.resolve(preTarget), AnimateTransformation.resolve(postTarget));
    }

    public VBUKeyFrame(float timestamp, String[] value, AnimateTransformation.Interpolation interpolation) {
        this(timestamp, value, value, false, interpolation);
    }
}
