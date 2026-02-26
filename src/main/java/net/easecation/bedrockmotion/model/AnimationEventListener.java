package net.easecation.bedrockmotion.model;

import team.unnamed.mocha.runtime.Scope;

import java.util.List;

/**
 * Callback interface for animation events, replacing the direct CustomEntityTicker reference.
 * Implementations:
 * - VBU: CustomEntityTicker implements this
 * - ViaBedrock server: ServerEntityTicker implements this
 */
public interface AnimationEventListener {
    /**
     * Called when an animation timeline event fires (e.g., sound/particle triggers).
     */
    void onTimelineEvent(List<String> expressions);

    /**
     * Called when an animation particle_effects keyframe fires.
     * @param effectShortName the short name alias defined in entity description.particle_effects
     * @param locator the bone/locator name where the particle should spawn
     */
    default void onParticleEvent(String effectShortName, String locator) {}

    /**
     * Returns the entity-level MoLang scope containing variable bindings.
     * Used by AnimationControllerInstance to initialize state on construction.
     */
    Scope getEntityScope();
}
