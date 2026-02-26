package net.easecation.bedrockmotion.mocha;

import team.unnamed.mocha.runtime.value.MutableObjectBinding;
import team.unnamed.mocha.runtime.value.ObjectProperty;
import team.unnamed.mocha.runtime.value.ObjectValue;

/**
 * Thin overlay on a parent ObjectValue. Reads check local overrides first,
 * then delegate to the parent. Avoids the expensive setAllFrom() copy
 * that triggers CaseInsensitiveStringHashMap.putAll() + lowercaseMap().
 */
@SuppressWarnings("UnstableApiUsage")
public class OverlayBinding extends MutableObjectBinding {
    private ObjectValue parent;

    public OverlayBinding(ObjectValue parent) {
        this.parent = parent;
    }

    /**
     * Resets the parent binding for reuse, avoiding per-frame allocation.
     * Local overrides (set via {@link #set}) are retained and overwritten
     * on next set() — since the same keys are always used (e.g. anim_time,
     * life_time), this is safe and avoids needing a clear() method.
     */
    public void reset(ObjectValue newParent) {
        this.parent = newParent;
    }

    @Override
    public ObjectProperty getProperty(String name) {
        ObjectProperty prop = super.getProperty(name);
        if (prop != null) {
            return prop;
        }
        return parent.getProperty(name);
    }
}
