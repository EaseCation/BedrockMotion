package net.easecation.bedrockmotion.util.mojangweirdformat;

import net.easecation.bedrockmotion.animation.element.timestamp.ComplexTimeStamp;
import net.easecation.bedrockmotion.animation.element.timestamp.SimpleTimeStamp;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class ValueOrValue<T> {
    private T value;
    private final boolean immutable;

    public ValueOrValue(final T value) {
        this(value, false);
    }

    private ValueOrValue(final T value, final boolean immutable) {
        this.value = value;
        this.immutable = immutable;
    }

    @SuppressWarnings("unchecked")
    public T getValue() {
        if (this.immutable && this.value instanceof String[] strings) {
            return (T) strings.clone();
        }
        if (this.immutable && this.value instanceof SimpleTimeStamp timestamp) {
            return (T) new SimpleTimeStamp(
                    timestamp.timestamp(), timestamp.value() == null ? null : timestamp.value().clone());
        }
        if (this.immutable && this.value instanceof ComplexTimeStamp timestamp) {
            return (T) new ComplexTimeStamp(
                    timestamp.timestamp(), timestamp.lerpMode(),
                    timestamp.pre() == null ? null : timestamp.pre().clone(),
                    timestamp.post() == null ? null : timestamp.post().clone());
        }
        return this.value;
    }

    public void setValue(final T value) {
        if (this.immutable) {
            throw new UnsupportedOperationException("Shared animation values are immutable");
        }
        this.value = value;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> ValueOrValue<T> immutableCopy(final ValueOrValue<T> source) {
        if (source == null || source.immutable) return source;
        final Object value = source.value;
        final Object immutableValue;
        if (value instanceof String[] strings) {
            immutableValue = strings.clone();
        } else if (value instanceof SimpleTimeStamp timestamp) {
            immutableValue = new SimpleTimeStamp(
                    timestamp.timestamp(), timestamp.value() == null ? null : timestamp.value().clone());
        } else if (value instanceof ComplexTimeStamp timestamp) {
            immutableValue = new ComplexTimeStamp(
                    timestamp.timestamp(), timestamp.lerpMode(),
                    timestamp.pre() == null ? null : timestamp.pre().clone(),
                    timestamp.post() == null ? null : timestamp.post().clone());
        } else if (value instanceof Map<?, ?> values) {
            final TreeMap<Float, ValueOrValue<?>> copy = new TreeMap<>();
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                if (entry.getKey() instanceof Float timestamp && entry.getValue() instanceof ValueOrValue<?> nested) {
                    copy.put(timestamp, immutableCopy((ValueOrValue) nested));
                }
            }
            immutableValue = Collections.unmodifiableNavigableMap(copy);
        } else if (value instanceof List<?> values) {
            immutableValue = List.copyOf(values);
        } else {
            immutableValue = value;
        }
        return new ValueOrValue<>((T) immutableValue, true);
    }

    @Override
    public String toString() {
        return String.valueOf(this.value);
    }
}
