package net.easecation.bedrockmotion.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.Set;

public class JsonUtil {
    public static String[] jsonArrayToStringArray(final JsonArray array) {
        return new String[] {array.get(0).getAsString(), array.get(1).getAsString(), array.get(2).getAsString()};
    }

    public static Set<String> arrayToStringSet(final JsonArray array) {
        if (array == null) {
            return Set.of();
        }

        final Set<String> strings = new HashSet<>();
        for (final JsonElement element : array) {
            strings.add(element.getAsString());
        }

        return strings;
    }

    public static Set<String> vertexFields(final JsonArray array) {
        if (array == null) {
            return Set.of();
        }

        final Set<String> strings = new HashSet<>();
        for (final JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }

            final JsonObject object = element.getAsJsonObject();
            if (!object.has("field")) {
                continue;
            }

            strings.add(object.get("field").getAsString());
        }

        return strings;
    }
}
