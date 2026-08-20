package net.easecation.bedrockmotion.animator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnimationClockTest {
    @Test
    void partialTickOnlyChangesSamplingTime() {
        AnimationClock.Client clock = new AnimationClock.Client();
        clock.advanceTick(40L);
        clock.sample(0.5F);

        assertEquals(40L, clock.tick());
        assertEquals(2.025D, clock.timeSeconds(), 1.0E-6D);

        clock.sample(0.75F);
        assertEquals(40L, clock.tick());
        assertThrows(IllegalArgumentException.class, () -> clock.advanceTick(39L));
    }
}
