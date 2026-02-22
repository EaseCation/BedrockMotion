package net.easecation.bedrockmotion.util.mojangweirdformat;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@AllArgsConstructor
@ToString(includeFieldNames = false)
@Getter
@Setter
public class ValueOrValue<T> {
    private T value;
}
