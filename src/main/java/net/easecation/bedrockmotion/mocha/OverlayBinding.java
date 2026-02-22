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
    private final ObjectValue parent;

    public OverlayBinding(ObjectValue parent) {
        this.parent = parent;
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
