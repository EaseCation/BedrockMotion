package net.easecation.bedrockmotion.model;

/** Typed animation particle event; the legacy listener overload remains source compatible. */
public record AnimationParticleEvent(String effect, String locator, String preEffectExpression, long tick) {
    public AnimationParticleEvent {
        effect = effect == null ? "" : effect;
        locator = locator == null ? "" : locator;
        preEffectExpression = preEffectExpression == null ? "" : preEffectExpression;
    }
}
