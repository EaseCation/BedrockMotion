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

    /** Snapshot for this exact model instance. Hosts with additional pose channels must override.
     * Called only when another display context is about to overwrite a same-frame sampled model.
     */
    default Runnable capturePose() {
        var bones = new java.util.ArrayList<IBoneTarget>();
        var poses = new java.util.ArrayList<BoneTransform>();
        for (IBoneTarget bone : getAllBones()) { bones.add(bone); poses.add(BoneTransform.capture(bone)); }
        return () -> { for (int i = 0; i < bones.size(); i++) poses.get(i).restore(bones.get(i)); };
    }
}
