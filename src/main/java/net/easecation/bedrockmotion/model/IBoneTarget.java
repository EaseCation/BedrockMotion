package net.easecation.bedrockmotion.model;

import org.joml.Vector3f;

import java.util.Map;

/**
 * Abstract interface for a bone in the skeleton hierarchy.
 * Implementations adapt to specific rendering backends:
 * - VBU: wraps MC ModelPart via Mixin (IModelPart)
 * - ViaBedrock server: SimpleBone (pure data storage)
 */
public interface IBoneTarget {
    String getName();

    /**
     * Mutable rotation vector (degrees). Additive blending writes directly.
     */
    Vector3f getRotation();

    /**
     * Mutable offset/translation vector. Additive blending writes directly.
     */
    Vector3f getOffset();

    float getScaleX();
    float getScaleY();
    float getScaleZ();
    void setScale(float x, float y, float z);

    /**
     * Additive offset: offset += vec
     */
    void addOffset(Vector3f offset);

    /**
     * Additive rotation: rotation += vec
     */
    void addRotation(Vector3f rotation);

    /**
     * Additive scale: scale += (dx, dy, dz)
     */
    void addScale(float dx, float dy, float dz);

    /**
     * Reset bone to its bind/default pose (rotation, offset, scale).
     */
    void resetToDefaultPose();

    /**
     * Reset everything including accumulated state from previous frames.
     */
    default void resetEverything() {
        resetToDefaultPose();
    }

    /**
     * Named children of this bone.
     */
    Map<String, IBoneTarget> getChildren();
}
