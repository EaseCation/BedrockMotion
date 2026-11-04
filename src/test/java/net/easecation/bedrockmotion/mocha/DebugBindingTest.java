package net.easecation.bedrockmotion.mocha;

import org.junit.jupiter.api.Test;
import team.unnamed.mocha.runtime.value.Value;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DebugBindingTest {
    @Test
    void snapshotsLastScalarValuesWithoutInvokingFunctions() {
        final DebugBinding binding = new DebugBinding();
        binding.set("pitch", Value.of(12.5D));
        binding.set("mode", Value.of("first_person"));

        assertEquals("12.5", binding.debugSnapshot().get("pitch"));
        assertEquals("first_person", binding.debugSnapshot().get("mode"));
    }
}
