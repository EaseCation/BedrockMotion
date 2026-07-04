package net.easecation.bedrockmotion.mocha;

import team.unnamed.mocha.runtime.Scope;
import team.unnamed.mocha.runtime.value.ObjectProperty;
import team.unnamed.mocha.runtime.value.Value;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * A lightweight Scope implementation that layers local bindings on top of a parent scope.
 * Avoids the expensive deep-copy of CaseInsensitiveStringHashMap that ScopeImpl.copy() performs.
 * Reads fall through to the parent; writes go to a tiny flat array (typically 2-3 entries).
 *
 * <p>The local store is a hand-rolled {@code String[]}/{@code ObjectProperty[]} pair with linear
 * scan, deliberately not a {@link HashMap}: at 2-3 entries linear scan beats hashing on every axis
 * — no {@code hashCode()} call per probe, no {@code Node} allocation, no iterator allocation on
 * traversal, and cache-friendly contiguous arrays. MoLang resolves every identifier through
 * {@link #getProperty}, so this is a render-thread hot path; the previous {@code HashMap}(4) showed
 * up in JFR (getNode + putVal + String.hashCode) despite holding only 2-3 constant keys.
 *
 * <p>BedrockMotion is an environment-agnostic library (consumed by both the NeoForge client and a
 * server-side proxy), so it cannot pull in fastutil's {@code Object2ObjectArrayMap}; the few lines
 * below give the same benefit with zero extra dependencies.
 */
@SuppressWarnings("UnstableApiUsage")
public class LayeredScope implements Scope {
    private Scope parent;
    // Flat local store: keys[0..size) / values[0..size). Grows on demand but in practice stays at 2-3
    // entries (query/q, or temp/t), so the initial capacity of 4 never triggers a resize.
    private String[] keys = new String[4];
    private ObjectProperty[] values = new ObjectProperty[4];
    private int size;
    private boolean readOnly;

    public LayeredScope(Scope parent) {
        this.parent = parent;
    }

    /**
     * Resets this scope for reuse with a new parent, avoiding new allocation.
     */
    public void reset(Scope newParent) {
        this.parent = newParent;
        // Drop references so they can be GC'd, but keep the backing arrays for reuse.
        for (int i = 0; i < this.size; i++) {
            this.keys[i] = null;
            this.values[i] = null;
        }
        this.size = 0;
        this.readOnly = false;
    }

    @Override
    public ObjectProperty getProperty(String name) {
        for (int i = 0; i < this.size; i++) {
            if (this.keys[i].equals(name)) {
                return this.values[i];
            }
        }
        return this.parent.getProperty(name);
    }

    @Override
    public boolean set(String name, Value value) {
        if (this.readOnly) {
            return false;
        }
        // Replace an existing binding first (linear scan; keys are few).
        for (int i = 0; i < this.size; i++) {
            if (this.keys[i].equals(name)) {
                if (value == null) {
                    removeAt(i);
                } else {
                    this.values[i] = ObjectProperty.property(value, false);
                }
                return true;
            }
        }
        if (value == null) {
            // Removing a binding that isn't present is a no-op (matches HashMap.remove semantics).
            return true;
        }
        // Append a new binding, growing the backing arrays if necessary.
        if (this.size == this.keys.length) {
            int newCap = this.keys.length << 1;
            this.keys = Arrays.copyOf(this.keys, newCap);
            this.values = Arrays.copyOf(this.values, newCap);
        }
        this.keys[this.size] = name;
        this.values[this.size] = ObjectProperty.property(value, false);
        this.size++;
        return true;
    }

    private void removeAt(int i) {
        final int moveCount = this.size - i - 1;
        if (moveCount > 0) {
            System.arraycopy(this.keys, i + 1, this.keys, i, moveCount);
            System.arraycopy(this.values, i + 1, this.values, i, moveCount);
        }
        this.size--;
        this.keys[this.size] = null;
        this.values[this.size] = null;
    }

    @Override
    public Scope copy() {
        Scope flat = Scope.create();
        for (Map.Entry<String, ObjectProperty> entry : parent.entries().entrySet()) {
            flat.set(entry.getKey(), entry.getValue().value());
        }
        for (int i = 0; i < this.size; i++) {
            flat.set(this.keys[i], this.values[i].value());
        }
        return flat;
    }

    @Override
    public Map<String, ObjectProperty> entries() {
        Map<String, ObjectProperty> merged = new HashMap<>(parent.entries());
        for (int i = 0; i < this.size; i++) {
            merged.put(this.keys[i], this.values[i]);
        }
        return merged;
    }

    @Override
    public void readOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    @Override
    public boolean readOnly() {
        return this.readOnly;
    }
}
