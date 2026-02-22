package net.easecation.bedrockmotion.model;

import org.joml.Vector3f;

/**
 * Immutable snapshot of a bone's transform state.
 * Used for two-pass blending in animation controller cross-fades.
 */
public record BoneTransform(
        float rx, float ry, float rz,
        float ox, float oy, float oz,
        float sx, float sy, float sz
) {
    public static BoneTransform capture(IBoneTarget bone) {
        final Vector3f rot = bone.getRotation();
        final Vector3f off = bone.getOffset();
        return new BoneTransform(
                rot.x, rot.y, rot.z,
                off.x, off.y, off.z,
                bone.getScaleX(), bone.getScaleY(), bone.getScaleZ()
        );
    }

    public void restore(IBoneTarget bone) {
        bone.getRotation().set(rx, ry, rz);
        bone.getOffset().set(ox, oy, oz);
        bone.setScale(sx, sy, sz);
    }
}
