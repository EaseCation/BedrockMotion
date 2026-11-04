package net.easecation.bedrockmotion.attachable;

import net.easecation.bedrockmotion.animator.AnimationClock;
import net.easecation.bedrockmotion.animator.Animator;
import net.easecation.bedrockmotion.model.AnimationEventListener;
import net.easecation.bedrockmotion.model.IBoneModel;
import net.easecation.bedrockmotion.model.IBoneTarget;
import net.easecation.bedrockmotion.mocha.MoLangEvaluationContext;
import net.easecation.bedrockmotion.pack.PackManager;
import net.easecation.bedrockmotion.pack.content.Content;
import org.cube.converter.data.bedrock.BedrockAttachableData;
import org.cube.converter.data.bedrock.BedrockEntityData;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import team.unnamed.mocha.runtime.Scope;
import team.unnamed.mocha.runtime.value.MutableObjectBinding;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttachableAnimationRuntimeTest {
    @Test
    void initializeRunsOnceAndDuplicateRenderTickDoesNotAdvanceState() throws Exception {
        var scripts = new BedrockEntityData.Scripts(
                List.of("v.initialized = (v.initialized ?? 0) + 1;"),
                List.of("v.pre_count = (v.pre_count ?? 0) + 1;"),
                new BedrockEntityData.Scripts.Scale("2", "1", "0.5", "1"),
                List.of());
        var definition = new BedrockAttachableData("test:item", scripts, List.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of());
        var scope = Scope.create();
        var variables = new MutableObjectBinding();
        scope.set("variable", variables);
        scope.set("v", variables);
        var clock = new AnimationClock.Client();
        var runtime = new AttachableAnimationRuntime(definition, new PackManager(List.of()), clock,
                new AnimationEventListener() {
                    @Override
                    public void onTimelineEvent(List<String> expressions) {
                    }

                    @Override
                    public Scope getEntityScope() {
                        return scope;
                    }
                });

        assertTrue(runtime.tick(20L, scope, MoLangEvaluationContext.EMPTY));
        assertFalse(runtime.tick(20L, scope, MoLangEvaluationContext.EMPTY));
        assertEquals(1.0D, variables.get("initialized").getAsNumber());
        assertEquals(1.0D, variables.get("pre_count").getAsNumber());
        assertEquals(new AttachableAnimationRuntime.Scale(2.0F, 1.0F, 2.0F), runtime.rootScale());

        assertTrue(runtime.tick(21L, scope, MoLangEvaluationContext.EMPTY));
        assertEquals(1.0D, variables.get("initialized").getAsNumber());
        assertEquals(2.0D, variables.get("pre_count").getAsNumber());
    }

    @Test
    void repeatedSampleWithinSameTickProducesIdenticalPose() throws Exception {
        var definition = animatingDefinition("wave", "animation.test.wave");
        var scope = animationScope();
        var clock = new AnimationClock.Client();
        var runtime = new AttachableAnimationRuntime(definition,
                packManagerWithAnimation("animation.test.wave", true), clock, listener(scope));
        var model = new TestModel("root");

        assertTrue(runtime.tick(0L, scope, MoLangEvaluationContext.EMPTY));
        runtime.sample(model, 0.5F, scope);
        final float first = model.bone("root").getRotation().x;
        assertTrue(first > 0.0F);
        runtime.sample(model, 0.5F, scope);
        assertEquals(first, model.bone("root").getRotation().x);

        // Cross the loop boundary: tick stops the finished iteration, and both same-tick samples
        // must agree (previously the first pass mutated state seen by the second pass).
        assertTrue(runtime.tick(25L, scope, MoLangEvaluationContext.EMPTY));
        runtime.sample(model, 0.5F, scope);
        final float afterStop = model.bone("root").getRotation().x;
        runtime.sample(model, 0.5F, scope);
        assertEquals(afterStop, model.bone("root").getRotation().x);
    }

    @Test
    void holdOnLastFrameAnimationIsDoneOnlyAfterTickAndRetainsTerminalPose() throws Exception {
        var definition = animatingDefinition("once", "animation.test.once");
        var scope = animationScope();
        var clock = new AnimationClock.Client();
        var runtime = new AttachableAnimationRuntime(definition,
                packManagerWithLoopValue("animation.test.once", "\"hold_on_last_frame\""),
                clock, listener(scope));
        var model = new TestModel("root");

        assertTrue(runtime.tick(0L, scope, MoLangEvaluationContext.EMPTY));
        final Animator animator = runtime.animators().get("animation.test.once");
        assertFalse(animator.isDonePlaying());

        // Sampling past the animation length must not flip donePlaying on its own.
        runtime.sample(model, 0.5F, scope);
        runtime.sample(model, 0.5F, scope);
        assertFalse(animator.isDonePlaying());

        assertTrue(runtime.tick(25L, scope, MoLangEvaluationContext.EMPTY));
        assertTrue(animator.isDonePlaying());

        runtime.sample(model, 0.0F, scope);
        final float terminal = model.bone("root").getRotation().x;
        assertEquals(90.0F, terminal, 1.0e-4F);

        // A later render must continue to hold the terminal pose instead of returning to default.
        assertTrue(runtime.tick(40L, scope, MoLangEvaluationContext.EMPTY));
        runtime.sample(model, 0.75F, scope);
        assertEquals(terminal, model.bone("root").getRotation().x, 1.0e-4F);
    }

    @Test
    void plainNonLoopingAnimationStopsContributingAfterCompletion() throws Exception {
        var definition = animatingDefinition("once", "animation.test.once");
        var scope = animationScope();
        var clock = new AnimationClock.Client();
        var runtime = new AttachableAnimationRuntime(definition,
                packManagerWithAnimation("animation.test.once", false), clock, listener(scope));
        var model = new TestModel("root");

        assertTrue(runtime.tick(0L, scope, MoLangEvaluationContext.EMPTY));
        runtime.sample(model, 0.5F, scope);
        assertTrue(model.bone("root").getRotation().x > 0.0F);

        assertTrue(runtime.tick(25L, scope, MoLangEvaluationContext.EMPTY));
        assertTrue(runtime.animators().get("animation.test.once").isDonePlaying());
        runtime.sample(model, 0.0F, scope);
        assertEquals(90.0F, model.bone("root").getRotation().x, 1.0e-4F);

        // The completion tick exposes the terminal keyframe once; the next authoritative tick
        // releases a plain loop=false animation back to the default pose.
        assertTrue(runtime.tick(26L, scope, MoLangEvaluationContext.EMPTY));
        runtime.sample(model, 0.0F, scope);
        assertEquals(0.0F, model.bone("root").getRotation().x, 1.0e-4F);
    }

    @Test
    void legacyCombinedSamplingAdvancesOnlyOncePerTickAcrossModels() throws Exception {
        var scope = animationScope();
        var clock = new AnimationClock.Client();
        var packs = packManagerWithAnimation("animation.test.once", false);
        var animator = new Animator(listener(scope), packs.getAnimationDefinitions()
                .getAnimations().get("animation.test.once"), clock);
        animator.setBaseScope(scope);

        clock.advanceTick(0L);
        animator.animate(new TestModel("root"));

        clock.advanceTick(25L);
        var firstPass = new TestModel("root");
        var secondPass = new TestModel("root");
        animator.animate(firstPass);
        animator.animate(secondPass);
        assertEquals(90.0F, firstPass.bone("root").getRotation().x, 1.0e-4F);
        assertEquals(90.0F, secondPass.bone("root").getRotation().x, 1.0e-4F);

        clock.advanceTick(26L);
        var nextTick = new TestModel("root");
        animator.animate(nextTick);
        assertEquals(0.0F, nextTick.bone("root").getRotation().x, 1.0e-4F);
    }

    @Test
    void completionTimelineFiresOnceWhenControllerAdvancedBeforeSampling() throws Exception {
        var scope = animationScope();
        var clock = new AnimationClock.Client();
        var events = new AtomicInteger();
        var listener = new AnimationEventListener() {
            @Override
            public void onTimelineEvent(List<String> expressions) {
                events.incrementAndGet();
            }

            @Override
            public Scope getEntityScope() {
                return scope;
            }
        };
        var packs = packManagerWithAnimation("animation.test.once", false);
        var animator = new Animator(listener, packs.getAnimationDefinitions()
                .getAnimations().get("animation.test.once"), clock);
        animator.setBaseScope(scope);

        clock.advanceTick(0L);
        animator.animate(new TestModel("root"));
        clock.advanceTick(20L);
        animator.advance();
        animator.animate(new TestModel("root"));
        animator.animate(new TestModel("root"));

        assertEquals(1, events.get());
    }

    private static BedrockAttachableData animatingDefinition(String shortName, String animationId) {
        var scripts = new BedrockEntityData.Scripts(List.of(), List.of(),
                new BedrockEntityData.Scripts.Scale("1", "1", "1", "1"),
                List.of(new BedrockEntityData.Scripts.Animate(shortName, "")));
        return new BedrockAttachableData("test:item", scripts, List.of(), Map.of(),
                Map.of(shortName, animationId), Map.of(), Map.of(), Map.of());
    }

    private static PackManager packManagerWithAnimation(String animationId, boolean loop) {
        return packManagerWithLoopValue(animationId, Boolean.toString(loop));
    }

    private static PackManager packManagerWithLoopValue(String animationId, String loopValue) {
        var pack = new Content();
        pack.putString("animations/test.json", """
                {"format_version":"1.10.0","animations":{
                  "%s":{"loop":%s,"animation_length":1.0,
                    "timeline":{"1.0":["done"]},
                    "bones":{"root":{"rotation":{"0.0":[0,0,0],"1.0":[90,0,0]}}}}
                }}
                """.formatted(animationId, loopValue));
        return new PackManager(List.of(pack));
    }

    private static Scope animationScope() {
        var scope = Scope.create();
        var query = new MutableObjectBinding();
        scope.set("query", query);
        scope.set("q", query);
        var variables = new MutableObjectBinding();
        scope.set("variable", variables);
        scope.set("v", variables);
        return scope;
    }

    private static AnimationEventListener listener(Scope scope) {
        return new AnimationEventListener() {
            @Override
            public void onTimelineEvent(List<String> expressions) {
            }

            @Override
            public Scope getEntityScope() {
                return scope;
            }
        };
    }

    private static final class TestModel implements IBoneModel {
        private final Map<String, IBoneTarget> bones = new LinkedHashMap<>();

        private TestModel(String... names) {
            for (String name : names) {
                bones.put(name, new TestBone(name));
            }
        }

        private TestBone bone(String name) {
            return (TestBone) bones.get(name);
        }

        @Override
        public Map<String, IBoneTarget> getBoneIndex() {
            return bones;
        }

        @Override
        public Iterable<IBoneTarget> getAllBones() {
            return bones.values();
        }

        @Override
        public void resetAllBones() {
            bones.values().forEach(IBoneTarget::resetToDefaultPose);
        }
    }

    private static final class TestBone implements IBoneTarget {
        private final String name;
        private final Vector3f rotation = new Vector3f();
        private final Vector3f offset = new Vector3f();
        private float scaleX = 1.0F, scaleY = 1.0F, scaleZ = 1.0F;

        private TestBone(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Vector3f getRotation() {
            return rotation;
        }

        @Override
        public Vector3f getOffset() {
            return offset;
        }

        @Override
        public float getScaleX() {
            return scaleX;
        }

        @Override
        public float getScaleY() {
            return scaleY;
        }

        @Override
        public float getScaleZ() {
            return scaleZ;
        }

        @Override
        public void setScale(float x, float y, float z) {
            scaleX = x;
            scaleY = y;
            scaleZ = z;
        }

        @Override
        public void addOffset(Vector3f offset) {
            this.offset.add(offset);
        }

        @Override
        public void addRotation(Vector3f rotation) {
            this.rotation.add(rotation);
        }

        @Override
        public void addScale(float dx, float dy, float dz) {
            scaleX += dx;
            scaleY += dy;
            scaleZ += dz;
        }

        @Override
        public void resetToDefaultPose() {
            rotation.set(0);
            offset.set(0);
            scaleX = scaleY = scaleZ = 1.0F;
        }

        @Override
        public Map<String, IBoneTarget> getChildren() {
            return Map.of();
        }
    }
}
