package net.easecation.bedrockmotion.controller;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.TreeMap;

/**
 * Represents a Bedrock animation controller {@code blend_transition} value.
 */
public class BlendTransitionCurve {
    public static final BlendTransitionCurve NONE = new BlendTransitionCurve(0, null);

    private final float duration;
    private final TreeMap<Float, Float> keyframes; // null = linear fade

    private BlendTransitionCurve(float duration, TreeMap<Float, Float> keyframes) {
        this.duration = duration;
        this.keyframes = keyframes;
    }

    public static BlendTransitionCurve ofDuration(float duration) {
        if (duration <= 0) return NONE;
        return new BlendTransitionCurve(duration, null);
    }

    public static BlendTransitionCurve ofKeyframes(TreeMap<Float, Float> keyframes) {
        if (keyframes == null || keyframes.isEmpty()) return NONE;
        return new BlendTransitionCurve(keyframes.lastKey(), keyframes);
    }

    public static BlendTransitionCurve parse(JsonElement element) {
        if (element == null || element.isJsonNull()) return NONE;

        if (element.isJsonPrimitive()) {
            return ofDuration(element.getAsFloat());
        }

        if (element.isJsonObject()) {
            final JsonObject obj = element.getAsJsonObject();
            final TreeMap<Float, Float> kf = new TreeMap<>();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                try {
                    kf.put(Float.parseFloat(e.getKey()), e.getValue().getAsFloat());
                } catch (NumberFormatException ignored) {
                }
            }
            return ofKeyframes(kf);
        }

        return NONE;
    }

    public boolean isNone() {
        return duration <= 0;
    }

    public float getDuration() {
        return duration;
    }

    public float getOldStateWeight(float elapsedSeconds) {
        if (isNone()) return 0f;
        if (elapsedSeconds <= 0) return 1f;
        if (elapsedSeconds >= duration) return 0f;

        if (keyframes == null) {
            return 1f - (elapsedSeconds / duration);
        }

        final Map.Entry<Float, Float> floor = keyframes.floorEntry(elapsedSeconds);
        final Map.Entry<Float, Float> ceil = keyframes.ceilingEntry(elapsedSeconds);

        if (floor == null && ceil == null) return 0f;
        if (floor == null) return ceil.getValue();
        if (ceil == null) return floor.getValue();
        if (floor.getKey().equals(ceil.getKey())) return floor.getValue();

        final float t = (elapsedSeconds - floor.getKey()) / (ceil.getKey() - floor.getKey());
        return floor.getValue() + (ceil.getValue() - floor.getValue()) * t;
    }
}
