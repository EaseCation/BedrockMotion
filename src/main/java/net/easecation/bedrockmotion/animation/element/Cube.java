package net.easecation.bedrockmotion.animation.element;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.apache.commons.lang3.math.NumberUtils;
import net.easecation.bedrockmotion.animation.element.timestamp.ComplexTimeStamp;
import net.easecation.bedrockmotion.animation.element.timestamp.SimpleTimeStamp;
import net.easecation.bedrockmotion.util.JsonUtil;
import net.easecation.bedrockmotion.util.mojangweirdformat.ValueOrValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@RequiredArgsConstructor
@ToString
@Setter
@Getter
public final class Cube {
    private final String identifier;
    private String relativeTo = "";
    private ValueOrValue<?> position;
    private ValueOrValue<?> rotation;
    private ValueOrValue<?> scale;

    public static List<Cube> parse(final JsonObject object) {
        final List<Cube> cubes = new ArrayList<>();
        for (String identifier : object.keySet()) {
            if (!object.get(identifier).isJsonObject()) {
                continue;
            }
            JsonObject cubeObject = object.getAsJsonObject(identifier);
            final Cube cube = new Cube(identifier);

            if (cubeObject.has("relative_to")) {
                final JsonObject object1 = cubeObject.getAsJsonObject("relative_to");
                if (object1.has("rotation")) {
                    cube.setRelativeTo(object1.get("rotation").getAsString());
                }
            }

            if (cubeObject.has("position")) {
                cube.setPosition(parseValueOrValue3(cubeObject.get("position")));
            }

            if (cubeObject.has("rotation")) {
                cube.setRotation(parseValueOrValue3(cubeObject.get("rotation")));
            }

            if (cubeObject.has("scale")) {
                cube.setScale(parseValueOrValue3(cubeObject.get("scale")));
            }

            cubes.add(cube);
        }

        return cubes;
    }

    private static ValueOrValue<?> parseValueOrValue3(final JsonElement element) {
        if (element.isJsonPrimitive()) {
            final JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isNumber()) {
                return new ValueOrValue<>(primitive.getAsFloat());
            } else if (primitive.isString()) {
                return new ValueOrValue<>(primitive.getAsString());
            } else {
                return null;
            }
        } else if (element.isJsonArray()) {
            return new ValueOrValue<>(JsonUtil.jsonArrayToStringArray(element.getAsJsonArray()));
        } else if (element.isJsonObject()) {
            final JsonObject object = element.getAsJsonObject();
            if (object.isEmpty()) {
                return null;
            }

            Map<Float, ValueOrValue<?>> timestamps = new TreeMap<>();
            for (String string : object.keySet()) {
                if (!NumberUtils.isCreatable(string)) {
                    continue;
                }

                float timestamp = Float.parseFloat(string);
                if (object.get(string).isJsonArray()) {
                    timestamps.put(timestamp, new ValueOrValue<>(new SimpleTimeStamp(timestamp, JsonUtil.jsonArrayToStringArray(object.getAsJsonArray(string)))));
                } else if (object.get(string).isJsonObject()) {
                    timestamps.put(timestamp, new ValueOrValue<>(ComplexTimeStamp.parse(timestamp, object.getAsJsonObject(string))));
                } else {
                    return null;
                }
            }

            return new ValueOrValue<>(timestamps);
        }

        return null;
    }
}
