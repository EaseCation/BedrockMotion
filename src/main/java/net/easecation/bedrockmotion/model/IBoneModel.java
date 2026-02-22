package net.easecation.bedrockmotion.model;

import java.util.Map;

/**
 * Abstract interface for a skeletal model containing named bones.
 * Implementations adapt to specific rendering backends:
 * - VBU: wraps MC Model with CustomEntityModel
 * - ViaBedrock server: SimpleBoneModel (pure data)
 */
public interface IBoneModel {
    /**
     * Returns a flat index of all bones by lowercase name.
     * Used by the animation engine for bone lookup during keyframe application.
     */
    Map<String, IBoneTarget> getBoneIndex();

    /**
     * Returns all bones in the model for iteration (e.g., state capture/restore).
     */
    Iterable<IBoneTarget> getAllBones();

    /**
     * Reset all bones to their default/bind pose.
     * Called before additive animation blending each frame.
     */
    void resetAllBones();
}
