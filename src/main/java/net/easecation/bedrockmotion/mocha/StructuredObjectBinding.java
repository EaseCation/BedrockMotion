package net.easecation.bedrockmotion.mocha;

import java.util.Map;

/**
 * Mutable nested MoLang object used at integration boundaries. A flat key such as
 * {@code query.mod.ecgun_fire} is represented as query -> mod -> ecgun_fire, which is the
 * structure Bedrock expressions actually traverse.
 */
@Deprecated(forRemoval = false)
public class StructuredObjectBinding extends team.unnamed.mocha.runtime.value.StructuredObjectBinding {

    public static StructuredObjectBinding from(final Map<String, ?> values) {
        final StructuredObjectBinding binding = new StructuredObjectBinding();
        if (values != null) {
            for (Map.Entry<String, ?> entry : values.entrySet()) {
                final Object value = entry.getValue();
                if (value instanceof Boolean b) binding.setPath(entry.getKey(), b);
                else if (value instanceof Number n) binding.setPath(entry.getKey(), n.doubleValue());
                else if (value != null) binding.setPath(entry.getKey(), String.valueOf(value));
            }
        }
        return binding;
    }
}
