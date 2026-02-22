package net.easecation.bedrockmotion.pack.definitions;

/**
 * Visible bounds from geometry description, used for frustum culling.
 * Width/height are in blocks. Offset is the center offset [x, y, z].
 */
public record VisibleBounds(float width, float height, float offsetX, float offsetY, float offsetZ) {
    public static final VisibleBounds DEFAULT = new VisibleBounds(1.0f, 2.0f, 0.0f, 1.0f, 0.0f);
}
