package net.easecation.bedrockmotion.model;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Entity-relative linear transform with the inherited pivot position. Never inverts a parent. */
public final class EntityRotationTransform {
    private EntityRotationTransform() {}

    /** Matrices and pivot use the host's common model coordinate convention. Scratch must be distinct. */
    public static Matrix4f apply(Matrix4f inherited, Matrix4fc entity, Matrix4fc local,
                                 float px, float py, float pz, Matrix4f scratch) {
        float x = inherited.m00() * px + inherited.m10() * py + inherited.m20() * pz + inherited.m30();
        float y = inherited.m01() * px + inherited.m11() * py + inherited.m21() * pz + inherited.m31();
        float z = inherited.m02() * px + inherited.m12() * py + inherited.m22() * pz + inherited.m32();
        scratch.set(entity).mul(local);
        inherited.set3x3(scratch);
        return inherited.setTranslation(
                x - (scratch.m00() * px + scratch.m10() * py + scratch.m20() * pz),
                y - (scratch.m01() * px + scratch.m11() * py + scratch.m21() * pz),
                z - (scratch.m02() * px + scratch.m12() * py + scratch.m22() * pz));
    }
}
