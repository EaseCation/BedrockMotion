package net.easecation.bedrockmotion.mocha;

import team.unnamed.mocha.runtime.Scope;
import team.unnamed.mocha.runtime.value.ObjectProperty;
import team.unnamed.mocha.runtime.value.Value;

import java.util.HashMap;
import java.util.Map;

/**
 * A lightweight Scope implementation that layers local bindings on top of a parent scope.
 * Avoids the expensive deep-copy of CaseInsensitiveStringHashMap that ScopeImpl.copy() performs.
 * Reads fall through to the parent; writes go to the small local map (typically 2-3 entries).
 */
@SuppressWarnings("UnstableApiUsage")
public class LayeredScope implements Scope {
    private final Scope parent;
    private final Map<String, ObjectProperty> local;
    private boolean readOnly;

    public LayeredScope(Scope parent) {
        this.parent = parent;
        this.local = new HashMap<>(4);
    }

    @Override
    public ObjectProperty getProperty(String name) {
        ObjectProperty prop = local.get(name);
        if (prop != null) {
            return prop;
        }
        return parent.getProperty(name);
    }

    @Override
    public boolean set(String name, Value value) {
        if (readOnly) {
            return false;
        }
        if (value == null) {
            local.remove(name);
        } else {
            local.put(name, ObjectProperty.property(value, false));
        }
        return true;
    }

    @Override
    public Scope copy() {
        Scope flat = Scope.create();
        for (Map.Entry<String, ObjectProperty> entry : parent.entries().entrySet()) {
            flat.set(entry.getKey(), entry.getValue().value());
        }
        for (Map.Entry<String, ObjectProperty> entry : local.entrySet()) {
            flat.set(entry.getKey(), entry.getValue().value());
        }
        return flat;
    }

    @Override
    public Map<String, ObjectProperty> entries() {
        Map<String, ObjectProperty> merged = new HashMap<>(parent.entries());
        merged.putAll(local);
        return merged;
    }

    @Override
    public void readOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    @Override
    public boolean readOnly() {
        return readOnly;
    }
}
