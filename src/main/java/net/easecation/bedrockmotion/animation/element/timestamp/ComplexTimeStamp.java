package net.easecation.bedrockmotion.animation.element.timestamp;

import com.google.gson.JsonObject;
import net.easecation.bedrockmotion.util.JsonUtil;

import java.util.Arrays;
import java.util.Locale;

public record ComplexTimeStamp(float timestamp, String lerpMode, String[] pre, String[] post) {
    public static ComplexTimeStamp parse(final float timestamp, final JsonObject object) {
        return new ComplexTimeStamp(timestamp, object.has("lerp_mode") ? object.get("lerp_mode").getAsString().toLowerCase(Locale.ROOT)
                : "catmullrom",
                object.has("pre") ? JsonUtil.jsonArrayToStringArray(object.getAsJsonArray("pre")) : null,
                object.has("post") ? JsonUtil.jsonArrayToStringArray(object.getAsJsonArray("post")) : null);
    }

    @Override
    public String toString() {
        return "ComplexTimeStamp{" +
                "timestamp=" + timestamp +
                ", lerpMode='" + lerpMode + '\'' +
                ", pre=" + Arrays.toString(pre) +
                ", post=" + Arrays.toString(post) +
                '}';
    }
}
