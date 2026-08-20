package net.easecation.bedrockmotion.animator;

/** Explicit animation time source; attachable runtimes use ticks while legacy callers use SYSTEM. */
public interface AnimationClock {
    AnimationClock SYSTEM = new AnimationClock() {
        @Override
        public long tick() {
            return System.currentTimeMillis() / 50L;
        }

        @Override
        public float partialTick() {
            return (System.currentTimeMillis() % 50L) / 50.0F;
        }

        @Override
        public long timeMillis() {
            return System.currentTimeMillis();
        }
    };

    long tick();

    float partialTick();

    default double timeSeconds() {
        return (tick() + partialTick()) / 20.0D;
    }

    default long timeMillis() {
        return Math.round(timeSeconds() * 1000.0D);
    }

    /** Mutable client clock: advancing partial ticks never changes the authoritative tick. */
    final class Client implements AnimationClock {
        private long tick;
        private float partialTick;

        public void advanceTick(final long tick) {
            if (tick < this.tick) {
                throw new IllegalArgumentException("Animation tick cannot move backwards");
            }
            this.tick = tick;
            this.partialTick = 0.0F;
        }

        public void sample(final float partialTick) {
            this.partialTick = Math.max(0.0F, Math.min(1.0F, partialTick));
        }

        @Override
        public long tick() {
            return tick;
        }

        @Override
        public float partialTick() {
            return partialTick;
        }
    }
}
