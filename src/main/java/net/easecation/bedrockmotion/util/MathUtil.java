package net.easecation.bedrockmotion.util;

import org.joml.Vector3f;

import java.util.function.IntPredicate;

public class MathUtil {
    public static final float DEGREES_TO_RADIANS = 0.017453292519943295f;

    /**
     * Normalize an angle in degrees to the range [-180, 180).
     */
    public static float normalizeAngleDeg(float angle) {
        angle = angle % 360;
        if (angle >= 180) angle -= 360;
        if (angle < -180) angle += 360;
        return angle;
    }

    /**
     * Linearly interpolate between two euler rotation vectors (in degrees),
     * taking the shortest path on each axis.
     */
    public static void shortestPathLerp(Vector3f from, Vector3f to, float t, Vector3f dest) {
        dest.x = from.x + normalizeAngleDeg(to.x - from.x) * t;
        dest.y = from.y + normalizeAngleDeg(to.y - from.y) * t;
        dest.z = from.z + normalizeAngleDeg(to.z - from.z) * t;
    }

    /**
     * Catmull-Rom spline interpolation (replaces MathHelper.catmullRom).
     */
    public static float catmullRom(float delta, float p0, float p1, float p2, float p3) {
        return 0.5F * (2.0F * p1
                + (p2 - p0) * delta
                + (2.0F * p0 - 5.0F * p1 + 4.0F * p2 - p3) * delta * delta
                + (3.0F * p1 - p0 - 3.0F * p2 + p3) * delta * delta * delta);
    }

    /**
     * Binary search (replaces MathHelper.binarySearch).
     * Returns the first index in [start, end) where predicate returns true.
     */
    public static int binarySearch(int start, int end, IntPredicate predicate) {
        int length = end - start;
        while (length > 0) {
            int half = length / 2;
            int middle = start + half;
            if (predicate.test(middle)) {
                length = half;
            } else {
                start = middle + 1;
                length -= half + 1;
            }
        }
        return start;
    }

    /**
     * Clamp a float value between min and max (replaces MathHelper.clamp).
     */
    public static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
