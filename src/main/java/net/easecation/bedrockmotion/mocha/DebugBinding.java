package net.easecation.bedrockmotion.mocha;

import team.unnamed.mocha.runtime.value.Function;
import team.unnamed.mocha.runtime.value.MutableObjectBinding;
import team.unnamed.mocha.runtime.value.Value;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

/** Records the last values assigned to a MoLang binding for read-only diagnostics. */
public final class DebugBinding extends MutableObjectBinding {
    private final Map<String, Value> values = new LinkedHashMap<>();

    @Override
    public boolean set(String name, Value value) {
        final boolean accepted = super.set(name, value);
        if (accepted) {
            if (value == null) {
                values.remove(name);
            } else {
                values.put(name, value);
            }
        }
        return accepted;
    }

    /** Returns a stable text snapshot; function bindings are intentionally not invoked. */
    public Map<String, String> debugSnapshot() {
        final Map<String, String> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, Value> entry : values.entrySet()) {
            final Value value = entry.getValue();
            snapshot.put(entry.getKey(), value instanceof Function<?> ? "<function>" : value.getAsString());
        }
        return Collections.unmodifiableMap(snapshot);
    }
}
