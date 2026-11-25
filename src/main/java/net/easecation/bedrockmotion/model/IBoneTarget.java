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
     * Mutable offset/translation in backend storage coordinates. Pose capture/blending writes
     * directly; expression evaluation must use {@link #readAnimationOffset(Vector3f)} instead.
     */
    Vector3f getOffset();

    /** Read the accumulated translation in Bedrock animation coordinates for Molang `this`.
     * Storage adapters may use another coordinate system; that conversion belongs to the adapter.
     * The destination is caller-owned scratch and must not be retained.
     */
    default Vector3f readAnimationOffset(Vector3f destination) {
        return destination.set(getOffset());
    }

    float getScaleX();
    float getScaleY();
    float getScaleZ();
    void setScale(float x, float y, float z);

    /** Pose metadata: relative_to.rotation=entity excludes ancestor linear transforms. */
    default boolean isEntityRelativeRotation() { return false; }

    default void setEntityRelativeRotation(boolean relative) {
        if (relative) throw new UnsupportedOperationException("Host does not support entity-relative bone rotation");
    }

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

    /** Reset this bone's lower animation contributions before an overriding animation layer.
     * Hosts with an additional native animation channel must also restore that channel's bind pose.
     * This is distinct from a frame reset that may deliberately retain host animation.
     */
    default void resetForAnimationOverride() {
        resetToDefaultPose();
    }

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
