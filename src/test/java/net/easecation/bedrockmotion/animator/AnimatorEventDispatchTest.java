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
import org.joml.Vector3f;
import team.unnamed.mocha.runtime.Scope;
import team.unnamed.mocha.runtime.value.MutableObjectBinding;
import team.unnamed.mocha.runtime.value.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnimatorEventDispatchTest {
    @Test
    void animTimeUpdateAdvancesOncePerDistinctRenderFrame() throws Exception {
        final Animation animation = Animation.parse(JsonParser.parseString("""
                {"animations":{"animation.test.clock":{
                  "loop":true,"animation_length":1.0,
                  "anim_time_update":"q.anim_time + q.delta_time",
                  "bones":{"rightarm":{"rotation":["q.anim_time * 100",0,0]}}
                }}}
                """).getAsJsonObject()).getFirst();
        final AnimationClock.Client clock = new AnimationClock.Client();
        final RecordingListener listener = new RecordingListener();
        final Animator animator = new Animator(listener,
                new AnimationDefinitions.AnimationData(animation, AnimateBuilder.build(animation)), clock);
        animator.setBaseScope(listener.scope);
        final TestBoneModel model = new TestBoneModel();

        animator.advance();
        model.resetAllBones();
        animator.animate(model, false);
        assertEquals(0.0F, model.rotation.x, 1.0e-4F);

        clock.advanceTick(1L);
        animator.advance();
        model.resetAllBones();
        animator.animate(model, false);
        assertEquals(5.0F, model.rotation.x, 1.0e-3F);

        clock.sample(0.5F);
        model.resetAllBones();
        animator.animate(model, false);
        assertEquals(7.5F, model.rotation.x, 1.0e-3F);
        model.resetAllBones();
        animator.animate(model, false);
        assertEquals(7.5F, model.rotation.x, 1.0e-3F);
    }

    @Test
    void animTimeUpdateUsesBedrockAttackPhaseInsteadOfWallClock() throws Exception {
        final Animation animation = Animation.parse(JsonParser.parseString("""
                {"animations":{"animation.test.attack":{
                  "loop":true,"animation_length":0.4,
                  "anim_time_update":"v.attack_time * 0.32",
                  "bones":{"rightArm":{"rotation":{
                    "0.0":[0,0,0],"0.16":[-80,0,0],"0.32":[0,0,0]
                  }}}
                }}}
                """).getAsJsonObject()).getFirst();
        final AnimationClock.Client clock = new AnimationClock.Client();
        final RecordingListener listener = new RecordingListener();
        final MutableObjectBinding variables = new MutableObjectBinding();
        listener.scope.set("variable", variables);
        listener.scope.set("v", variables);
        final Animator animator = new Animator(listener,
                new AnimationDefinitions.AnimationData(animation, AnimateBuilder.build(animation)), clock);
        animator.setBaseScope(listener.scope);
        final TestBoneModel model = new TestBoneModel();

        variables.set("attack_time", Value.of(0.0F));
        animator.advance();
        model.resetAllBones();
        animator.animate(model, false);
        assertEquals(0.0F, model.rotation.x, 1.0e-4F);

        clock.advanceTick(20L);
        animator.advance();
        model.resetAllBones();
        animator.animate(model, false);
        assertEquals(0.0F, model.rotation.x, 1.0e-4F);

        variables.set("attack_time", Value.of(0.5F));
        clock.advanceTick(21L);
        animator.advance();
        model.resetAllBones();
        animator.animate(model, false);
        assertEquals(-80.0F, model.rotation.x, 1.0e-3F);

        variables.set("attack_time", Value.of(0.0F));
        clock.advanceTick(22L);
        animator.advance();
        model.resetAllBones();
        animator.animate(model, false);
        assertEquals(0.0F, model.rotation.x, 1.0e-4F);
    }

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

    private static final class TestBoneModel implements IBoneModel, IBoneTarget {
        private final Vector3f rotation = new Vector3f();
        private final Vector3f offset = new Vector3f();

        @Override public Map<String, IBoneTarget> getBoneIndex() { return Map.of("rightarm", this); }
        @Override public Iterable<IBoneTarget> getAllBones() { return List.of(this); }
        @Override public void resetAllBones() { resetToDefaultPose(); }
        @Override public String getName() { return "rightarm"; }
        @Override public Vector3f getRotation() { return rotation; }
        @Override public Vector3f getOffset() { return offset; }
        @Override public float getScaleX() { return 1.0F; }
        @Override public float getScaleY() { return 1.0F; }
        @Override public float getScaleZ() { return 1.0F; }
        @Override public void setScale(float x, float y, float z) {}
        @Override public void addOffset(Vector3f value) { offset.add(value); }
        @Override public void addRotation(Vector3f value) { rotation.add(value); }
        @Override public void addScale(float x, float y, float z) {}
        @Override public void resetToDefaultPose() { rotation.zero(); offset.zero(); }
        @Override public Map<String, IBoneTarget> getChildren() { return Map.of(); }
    }

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
