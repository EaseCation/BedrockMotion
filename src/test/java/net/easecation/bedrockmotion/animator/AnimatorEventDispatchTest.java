package net.easecation.bedrockmotion.animator;

import com.google.gson.JsonParser;
import net.easecation.bedrockmotion.animation.Animation;
import net.easecation.bedrockmotion.animation.vanilla.AnimateBuilder;
import net.easecation.bedrockmotion.model.AnimationEventListener;
import net.easecation.bedrockmotion.model.AnimationParticleEvent;
import net.easecation.bedrockmotion.model.AnimationSoundEvent;
import net.easecation.bedrockmotion.model.IBoneModel;
import net.easecation.bedrockmotion.model.IBoneTarget;
import net.easecation.bedrockmotion.pack.definitions.AnimationDefinitions;
import org.junit.jupiter.api.Test;
import team.unnamed.mocha.runtime.Scope;
import team.unnamed.mocha.runtime.value.MutableObjectBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnimatorEventDispatchTest {
    @Test
    void dispatchesEveryCrossedTypedEventOnceWithPreEffectAndTick() throws Exception {
        final Animation animation = Animation.parse(JsonParser.parseString("""
                {"animations":{"animation.test":{
                  "loop":false,"animation_length":0.2,"start_delay":"0",
                  "particle_effects":{"0.075":{"effect":"trail","locator":"muzzle","pre_effect_script":"v.x=1"}},
                  "sound_effects":{"0.08":{"effect":"shot","locator":"muzzle"}}
                }}}
                """).getAsJsonObject()).getFirst();
        final AnimationClock.Client clock = new AnimationClock.Client();
        final RecordingListener listener = new RecordingListener();
        final Animator animator = new Animator(listener,
                new AnimationDefinitions.AnimationData(animation, AnimateBuilder.build(animation)), clock);
        animator.setBaseScope(listener.scope);

        animator.advance();
        animator.animate(EMPTY_MODEL, false);
        clock.advanceTick(2L); // 0.1 s crosses both non-tick-aligned timestamps.
        animator.advance();
        animator.animate(EMPTY_MODEL, false);
        animator.animate(EMPTY_MODEL, false); // repeated samples must not re-fire.

        assertEquals(List.of(new AnimationParticleEvent("trail", "muzzle", "v.x=1", 2L)), listener.particles);
        assertEquals(List.of(new AnimationSoundEvent("shot", "muzzle", "", 2L)), listener.sounds);
    }

    private static final IBoneModel EMPTY_MODEL = new IBoneModel() {
        @Override public Map<String, IBoneTarget> getBoneIndex() { return Map.of(); }
        @Override public Iterable<IBoneTarget> getAllBones() { return List.of(); }
        @Override public void resetAllBones() {}
    };

    private static final class RecordingListener implements AnimationEventListener {
        private final Scope scope = Scope.create();
        private final List<AnimationParticleEvent> particles = new ArrayList<>();
        private final List<AnimationSoundEvent> sounds = new ArrayList<>();

        private RecordingListener() {
            final MutableObjectBinding query = new MutableObjectBinding();
            scope.set("query", query);
            scope.set("q", query);
        }

        @Override public void onTimelineEvent(List<String> expressions) {}
        @Override public void onParticleEvent(AnimationParticleEvent event) { particles.add(event); }
        @Override public void onSoundEvent(AnimationSoundEvent event) { sounds.add(event); }
        @Override public Scope getEntityScope() { return scope; }
    }
}
