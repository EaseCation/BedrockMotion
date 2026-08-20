package net.easecation.bedrockmotion.model;

/** Typed animation sound event; effect resolution stays with the host runtime. */
public record AnimationSoundEvent(String effect, String locator, String preEffectExpression, long tick) {
    public AnimationSoundEvent {
        effect = effect == null ? "" : effect;
        locator = locator == null ? "" : locator;
        preEffectExpression = preEffectExpression == null ? "" : preEffectExpression;
    }
}
