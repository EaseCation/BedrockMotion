package net.easecation.bedrockmotion.entity;

import net.easecation.bedrockmotion.animator.AnimationClock;
import net.easecation.bedrockmotion.model.AnimationEventListener;
import net.easecation.bedrockmotion.model.IBoneModel;
import net.easecation.bedrockmotion.model.IBoneTarget;
import net.easecation.bedrockmotion.mocha.MoLangEvaluationContext;
import net.easecation.bedrockmotion.pack.PackManager;
import net.easecation.bedrockmotion.pack.content.Content;
import org.cube.converter.data.bedrock.BedrockEntityData;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import team.unnamed.mocha.runtime.Scope;
import team.unnamed.mocha.runtime.value.MutableObjectBinding;
import team.unnamed.mocha.runtime.value.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientEntityAnimationRuntimeTest {
    @Test
    void aliasOverrideLifecycleAndRepeatedSamplingUseOneRuntimePose() throws Exception {
        final BedrockEntityData entity = entity(
                List.of("v.initialized = (v.initialized ?? 0) + 1; v.host_input = 0;"),
                List.of("v.pre_count = (v.pre_count ?? 0) + 1; v.observed_input = v.host_input;"),
                List.of(new BedrockEntityData.Scripts.Animate("root", "")),
                Map.of("root", "animation.test.base"));
        final Scope scope = scope();
        final MutableObjectBinding variables = (MutableObjectBinding) scope.get("variable");
        final ClientEntityAnimationRuntime runtime = runtime(entity,
                Map.of("root", "animation.test.override"), animationsPack());

        assertTrue(runtime.tick(10L, 0.0F, scope, MoLangEvaluationContext.EMPTY,
                ignored -> variables.set("host_input", team.unnamed.mocha.runtime.value.Value.of(5.0D))));
        assertFalse(runtime.tick(10L, 0.0F, scope, MoLangEvaluationContext.EMPTY, ignored -> {
        }));
        assertEquals(1.0D, variables.get("initialized").getAsNumber());
        assertEquals(1.0D, variables.get("pre_count").getAsNumber());
        assertEquals(5.0D, variables.get("observed_input").getAsNumber());
        assertEquals("animation.test.override", runtime.animationAliases().get("root"));

        final TestModel model = new TestModel("root");
        runtime.sample(model, 0.5F, scope, MoLangEvaluationContext.EMPTY);
        final float first = model.bone("root").getRotation().x;
        runtime.sample(model, 0.5F, scope, MoLangEvaluationContext.EMPTY);
        assertEquals(60.0F, first, 1.0e-4F);
        assertEquals(first, model.bone("root").getRotation().x, 1.0e-4F);

        assertTrue(runtime.tick(11L, 0.0F, scope, MoLangEvaluationContext.EMPTY,
                ignored -> variables.set("host_input", team.unnamed.mocha.runtime.value.Value.of(6.0D))));
        assertEquals(1.0D, variables.get("initialized").getAsNumber());
        assertEquals(3.0D, variables.get("pre_count").getAsNumber());
        assertEquals(6.0D, variables.get("observed_input").getAsNumber());
    }

    @Test
    void rootAndNestedControllerDriveBareAnimation() throws Exception {
        final Content pack = animationsPack();
        pack.putString("animation_controllers/player.json", """
                {"format_version":"1.10.0","animation_controllers":{
                  "controller.test.root":{"initial_state":"default","states":{
                    "default":{"animations":[{"child":"0.5"}]}}},
                  "controller.test.child":{"initial_state":"default","states":{
                    "default":{"animations":["override"]}}}
                }}
                """);
        final BedrockEntityData entity = entity(List.of(), List.of(),
                List.of(new BedrockEntityData.Scripts.Animate("root", "")),
                Map.of(
                        "root", "controller.test.root",
                        "child", "controller.test.child",
                        "override", "animation.test.override"));
        final Scope scope = scope();
        final ClientEntityAnimationRuntime runtime = runtime(entity, Map.of(), pack);

        runtime.tick(0L, 0.0F, scope, MoLangEvaluationContext.EMPTY, ignored -> {
        });
        final TestModel model = new TestModel("root");
        runtime.sample(model, 0.0F, scope, MoLangEvaluationContext.EMPTY);

        assertEquals(30.0F, model.bone("root").getRotation().x, 1.0e-4F);
        final ClientEntityAnimationRuntime.DebugSnapshot debug = runtime.debugSnapshot();
        assertEquals(1, debug.playbacks().size());
        assertEquals("root", debug.playbacks().get(0).shortName());
        assertEquals("CONTROLLER", debug.playbacks().get(0).type());
        assertEquals("controller.test.root", debug.playbacks().get(0).resolvedIdentifier());
        assertEquals("controller.test.root", debug.controllers().get(0).identifier());
        assertEquals("default", debug.controllers().get(0).stateName());
        assertEquals("controller.test.child",
                debug.controllers().get(0).entries().get(0).childController().identifier());
    }

    @Test
    void controllerStateSkipsOptionalAliasMissingFromEntity() throws Exception {
        final Content pack = animationsPack();
        pack.putString("animation_controllers/player.json", """
                {"format_version":"1.10.0","animation_controllers":{
                  "controller.test.root":{"initial_state":"default","states":{
                    "default":{"animations":[
                      {"optional":"v.enable_optional"},
                      "override"
                    ]}}}
                }}
                """);
        final BedrockEntityData entity = entity(List.of(), List.of(),
                List.of(new BedrockEntityData.Scripts.Animate("root", "")),
                Map.of(
                        "root", "controller.test.root",
                        "override", "animation.test.override"));
        final Scope scope = scope();
        final ClientEntityAnimationRuntime runtime = runtime(entity, Map.of(), pack);

        runtime.tick(0L, 0.0F, scope, MoLangEvaluationContext.EMPTY, ignored -> {
        });
        final TestModel model = new TestModel("root");
        runtime.sample(model, 0.0F, scope, MoLangEvaluationContext.EMPTY);

        assertEquals(60.0F, model.bone("root").getRotation().x, 1.0e-4F);
    }

    @Test
    void missingAliasAndMissingDefinitionFailAtConstruction() {
        final BedrockEntityData missingAlias = entity(List.of(), List.of(),
                List.of(new BedrockEntityData.Scripts.Animate("root", "")), Map.of());
        final IllegalArgumentException aliasError = assertThrows(IllegalArgumentException.class,
                () -> runtime(missingAlias, Map.of(), animationsPack()));
        assertTrue(aliasError.getMessage().contains("Missing animation alias 'root'"));

        final BedrockEntityData missingDefinition = entity(List.of(), List.of(),
                List.of(new BedrockEntityData.Scripts.Animate("root", "")),
                Map.of("root", "controller.test.missing"));
        final IllegalArgumentException definitionError = assertThrows(IllegalArgumentException.class,
                () -> runtime(missingDefinition, Map.of(), animationsPack()));
        assertTrue(definitionError.getMessage().contains("controller.test.missing"));
    }

    @Test
    void cycleReachedThroughLaterStateFailsAtConstruction() {
        final Content pack = animationsPack();
        pack.putString("animation_controllers/cycle.json", """
                {"format_version":"1.10.0","animation_controllers":{
                  "controller.test.first":{"initial_state":"default","states":{
                    "default":{"transitions":[{"later":"1"}]},
                    "later":{"animations":["second"]}}},
                  "controller.test.second":{"initial_state":"default","states":{
                    "default":{"animations":["first"]}}}
                }}
                """);
        final BedrockEntityData entity = entity(List.of(), List.of(),
                List.of(new BedrockEntityData.Scripts.Animate("first", "")),
                Map.of(
                        "first", "controller.test.first",
                        "second", "controller.test.second"));

        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> runtime(entity, Map.of(), pack));
        assertTrue(error.getMessage().contains("Animation controller cycle"));
    }

    @Test
    void controllerTransitionRunsScriptsAndBlendsTheOutgoingPose() throws Exception {
        final Content pack = animationsPack();
        pack.putString("animation_controllers/transition.json", """
                {"format_version":"1.10.0","animation_controllers":{
                  "controller.test.transition":{"initial_state":"default","states":{
                    "default":{"animations":["base"],"blend_transition":0.1,
                      "on_entry":["v.entered_default = 1"],
                      "on_exit":["v.exited_default = 1"],
                      "transitions":[{"active":"v.switch"}]},
                    "active":{"animations":["override"],
                      "on_entry":["v.entered_active = 1"]}}
                }}}
                """);
        final BedrockEntityData entity = entity(List.of(), List.of(),
                List.of(new BedrockEntityData.Scripts.Animate("root", "")),
                Map.of(
                        "root", "controller.test.transition",
                        "base", "animation.test.base",
                        "override", "animation.test.override"));
        final Scope scope = scope();
        final MutableObjectBinding variables = (MutableObjectBinding) scope.get("variable");
        final ClientEntityAnimationRuntime runtime = runtime(entity, Map.of(), pack,
                new AnimationClock.Client(), listener(scope));
        final TestModel model = new TestModel("root");

        runtime.tick(0L, 0.0F, scope, MoLangEvaluationContext.EMPTY, ignored -> {
        });
        runtime.sample(model, 0.0F, scope, MoLangEvaluationContext.EMPTY);
        assertEquals(10.0F, model.bone("root").getRotation().x, 1.0e-4F);
        assertEquals(1.0D, variables.get("entered_default").getAsNumber());

        variables.set("switch", team.unnamed.mocha.runtime.value.Value.of(true));
        runtime.tick(1L, 0.0F, scope, MoLangEvaluationContext.EMPTY, ignored -> {
        });
        runtime.sample(model, 0.0F, scope, MoLangEvaluationContext.EMPTY);
        assertEquals(10.0F, model.bone("root").getRotation().x, 1.0e-4F);
        assertEquals(1.0D, variables.get("exited_default").getAsNumber());
        assertEquals(1.0D, variables.get("entered_active").getAsNumber());

        runtime.tick(2L, 0.0F, scope, MoLangEvaluationContext.EMPTY, ignored -> {
        });
        runtime.sample(model, 0.0F, scope, MoLangEvaluationContext.EMPTY);
        assertEquals(35.0F, model.bone("root").getRotation().x, 1.0e-4F);

        runtime.tick(3L, 0.0F, scope, MoLangEvaluationContext.EMPTY, ignored -> {
        });
        runtime.sample(model, 0.0F, scope, MoLangEvaluationContext.EMPTY);
        assertEquals(60.0F, model.bone("root").getRotation().x, 1.0e-4F);
    }

    @Test
    void tickDispatchesTimelineEventsAndRepeatedSamplesRemainPure() throws Exception {
        final Content pack = new Content();
        pack.putString("animations/events.json", """
                {"format_version":"1.10.0","animations":{
                  "animation.test.events":{"loop":false,"animation_length":0.2,
                    "timeline":{"0.05":"v.event = 1"},
                    "bones":{"root":{"rotation":[15,0,0]}}}
                }}
                """);
        final BedrockEntityData entity = entity(List.of(), List.of(),
                List.of(new BedrockEntityData.Scripts.Animate("events", "")),
                Map.of("events", "animation.test.events"));
        final Scope scope = scope();
        final RecordingListener listener = new RecordingListener(scope);
        final ClientEntityAnimationRuntime runtime = runtime(entity, Map.of(), pack,
                new AnimationClock.Client(), listener);
        final TestModel model = new TestModel("root");

        runtime.tick(0L, 0.0F, scope, MoLangEvaluationContext.EMPTY, ignored -> {
        });
        runtime.sample(model, 0.0F, scope, MoLangEvaluationContext.EMPTY);
        runtime.sample(model, 0.0F, scope, MoLangEvaluationContext.EMPTY);
        assertTrue(listener.timeline.isEmpty());

        runtime.tick(2L, 0.0F, scope, MoLangEvaluationContext.EMPTY, ignored -> {
        });
        assertEquals(List.of("v.event = 1"), listener.timeline);
        runtime.sample(model, 0.5F, scope, MoLangEvaluationContext.EMPTY);
        runtime.sample(model, 0.5F, scope, MoLangEvaluationContext.EMPTY);
        assertEquals(List.of("v.event = 1"), listener.timeline);
        assertEquals(15.0F, model.bone("root").getRotation().x, 1.0e-4F);
    }

    @Test
    void serverCommandsReplaceOnlyTheirControllerAndUseTheCurrentTick() throws Exception {
        final Scope scope = scope();
        final ClientEntityAnimationRuntime runtime = runtime(entity(List.of(), List.of(), List.of(),
                Map.of("alias", "animation.test.override")), Map.of(), animationsPack());
        final TestModel model = new TestModel("root");
        runtime.playAnimation("animation.test.base", "", "", "", 0.0F);
        assertPose(runtime, model, scope, 20_000L, 10.0F);
        runtime.playAnimation("alias", "", "", "", 0.0F);
        assertPose(runtime, model, scope, 20_001L, 60.0F);
        runtime.playAnimation("animation.test.base", "", "", "separate", 0.0F);
        assertPose(runtime, model, scope, 20_002L, 70.0F);
        assertPose(runtime, model, scope, 21_000L, 70.0F);
        assertEquals(2, runtime.debugSnapshot().controllers().size());
        assertEquals("", runtime.debugSnapshot().controllers().getFirst().identifier());
    }

    @Test
    void serverCommandsRestoreResourceStatesAndDoNotMutateOtherEntities() throws Exception {
        final Content pack = animationsPack();
        pack.putString("animation_controllers/command.json", """
                {"animation_controllers":{"controller.test.host":{"states":{
                  "default":{"animations":["base"]}}}}}
                """);
        final BedrockEntityData entity = entity(List.of(), List.of(),
                List.of(new BedrockEntityData.Scripts.Animate("root", "")),
                Map.of("root", "controller.test.host", "base", "animation.test.base"));
        final PackManager packs = new PackManager(List.of(pack));
        final Scope scope = scope();
        final ClientEntityAnimationRuntime runtime = new ClientEntityAnimationRuntime(entity, Map.of(),
                packs, new AnimationClock.Client(), listener(scope));
        final ClientEntityAnimationRuntime other = new ClientEntityAnimationRuntime(entity, Map.of(),
                packs, new AnimationClock.Client(), listener(scope));
        final TestModel model = new TestModel("root");
        runtime.playAnimation("animation.test.override", "default", "q.anim_time >= 0.1",
                "controller.test.host", 0.1F);
        assertPose(runtime, model, scope, 4_000L, 60.0F);
        assertPose(other, model, scope, 4_000L, 10.0F);
        assertPose(runtime, model, scope, 4_002L, 60.0F);
        assertPose(runtime, model, scope, 4_003L, 35.0F);
        assertPose(runtime, model, scope, 4_004L, 10.0F);
        assertEquals("default", runtime.debugSnapshot().controllers().getFirst().stateName());
        assertEquals(1, packs.getAnimationControllerDefinitions().getControllers()
                .get("controller.test.host").getStates().size());
        runtime.playAnimation("animation.test.override", "default", "q.anim_time >= 0.1",
                "root", 0.0F);
        assertPose(runtime, model, scope, 4_005L, 60.0F);
        assertEquals(1, runtime.debugSnapshot().controllers().size());
    }

    @Test
    void serverCommandsResolveInactiveControllerAliasesToTheSameResourceInstance() throws Exception {
        final Content pack = animationsPack();
        pack.putString("animation_controllers/inactive.json", """
                {"animation_controllers":{"controller.test.inactive":{"states":{
                  "default":{"animations":["base"]}}}}}
                """);
        final Scope scope = scope();
        final ClientEntityAnimationRuntime runtime = runtime(
                entity(List.of(), List.of(), List.of(),
                        Map.of("controller_alias", "controller.test.inactive", "base", "animation.test.base")),
                Map.of(), pack);
        final TestModel model = new TestModel("root");
        runtime.playAnimation("animation.test.override", "default", "q.anim_time >= 0.1",
                "controller_alias", 0.0F);
        assertPose(runtime, model, scope, 100L, 60.0F);
        assertPose(runtime, model, scope, 102L, 10.0F);
        runtime.playAnimation("animation.test.override", "", "", "controller.test.inactive", 0.0F);
        assertPose(runtime, model, scope, 103L, 60.0F);
        assertEquals(1, runtime.debugSnapshot().controllers().size());
    }

    @Test
    void serverCommandsKeepHoldAndLoopUntilTheirTransitionAndSamplePurely() throws Exception {
        final Content pack = animationsPack();
        pack.putString("animations/lifecycle.json", """
                {"animations":{
                  "animation.test.hold":{"loop":"hold_on_last_frame","animation_length":0.1,
                    "timeline":{"0.05":"v.event = 1"},
                    "bones":{"root":{"rotation":[30,0,0]}}},
                  "animation.test.loop":{"loop":true,"animation_length":0.1,
                    "bones":{"root":{"rotation":[40,0,0]}}}
                }}
                """);
        final Scope scope = scope();
        final RecordingListener listener = new RecordingListener(scope);
        final ClientEntityAnimationRuntime runtime = runtime(
                entity(List.of(), List.of(), List.of(), Map.of()), Map.of(), pack,
                new AnimationClock.Client(), listener);
        final TestModel model = new TestModel("root");
        runtime.playAnimation("animation.test.hold", "", "", "story", 0.0F);
        assertPose(runtime, model, scope, 500L, 30.0F);
        assertPose(runtime, model, scope, 502L, 30.0F);
        assertPose(runtime, model, scope, 510L, 30.0F);
        runtime.sample(model, 0.0F, scope, MoLangEvaluationContext.EMPTY);
        runtime.sample(model, 0.0F, scope, MoLangEvaluationContext.EMPTY);
        assertEquals(List.of("v.event = 1"), listener.timeline);
        runtime.playAnimation("animation.test.loop", "", "", "story", 0.0F);
        for (long tick = 511; tick <= 520; tick++) {
            assertPose(runtime, model, scope, tick, 40.0F);
        }
        runtime.playAnimation("animation.test.hold", "finished", "q.all_animations_finished",
                "story", 0.0F);
        assertPose(runtime, model, scope, 521L, 30.0F);
        assertPose(runtime, model, scope, 523L, 30.0F);
        assertPose(runtime, model, scope, 524L, 0.0F);
        assertEquals("finished", runtime.debugSnapshot().controllers().getFirst().stateName());
    }

    @Test
    void serverCommandTransitionsRemainOrderedAndInvalidRequestsPreserveCurrentPlayback() throws Exception {
        final Scope scope = scope();
        final ClientEntityAnimationRuntime runtime = runtime(
                entity(List.of(), List.of(), List.of(), Map.of()), Map.of(), animationsPack());
        final TestModel model = new TestModel("root");
        final MutableObjectBinding variables = (MutableObjectBinding) scope.get("variable");
        variables.set("stop", Value.of(false));
        runtime.playAnimation("animation.test.override", "first", "v.stop", "story", 0.0F);
        assertPose(runtime, model, scope, 0L, 60.0F);
        runtime.playAnimation("animation.test.override", "second", "v.stop", "story", 0.0F);
        assertPose(runtime, model, scope, 1L, 60.0F);
        assertThrows(IllegalArgumentException.class, () -> runtime.playAnimation(
                "animation.test.missing", "", "", "story", 0.0F));
        assertThrows(IllegalArgumentException.class, () -> runtime.playAnimation(
                "animation.test.base", "default", "q.unknown(", "story", 0.0F));
        assertThrows(IllegalArgumentException.class, () -> runtime.playAnimation(
                "animation.test.base", "", "", "story", Float.NaN));
        assertPose(runtime, model, scope, 2L, 60.0F);
        variables.set("stop", Value.of(true));
        assertPose(runtime, model, scope, 3L, 0.0F);
        assertEquals("first", runtime.debugSnapshot().controllers().getFirst().stateName());
    }

    private static void assertPose(ClientEntityAnimationRuntime runtime, TestModel model, Scope scope,
                                   long tick, float expectedRotation) throws Exception {
        runtime.tick(tick, 0.0F, scope, MoLangEvaluationContext.EMPTY, ignored -> {});
        runtime.sample(model, 0.0F, scope, MoLangEvaluationContext.EMPTY);
        assertEquals(expectedRotation, model.bone("root").getRotation().x, 1.0e-4F);
    }

    @Test
    void preAnimationIsPreparedForEachDistinctRenderFrame() throws Exception {
        final Content pack = new Content();
        pack.putString("animations/frame.json", """
                {"format_version":"1.10.0","animations":{
                  "animation.test.frame":{"loop":true,
                    "bones":{"root":{"rotation":["v.frame_alpha * 100",0,0]}}}
                }}
                """);
        final BedrockEntityData entity = entity(List.of(),
                List.of("v.pre_animation_count = v.pre_animation_count + 1;",
                        "v.frame_alpha = q.frame_alpha;"),
                List.of(new BedrockEntityData.Scripts.Animate("frame", "")),
                Map.of("frame", "animation.test.frame"));
        final Scope scope = scope();
        final MutableObjectBinding query = (MutableObjectBinding) scope.get("query");
        final MutableObjectBinding variables = (MutableObjectBinding) scope.get("variable");
        final ClientEntityAnimationRuntime runtime = runtime(entity, Map.of(), pack);
        final TestModel model = new TestModel("root");

        query.set("frame_alpha", Value.of(0.25F));
        runtime.tick(12L, 0.25F, scope, MoLangEvaluationContext.EMPTY, ignored -> {
        });
        runtime.sample(model, 0.25F, scope, MoLangEvaluationContext.EMPTY);
        assertEquals(25.0F, model.bone("root").getRotation().x, 1.0e-4F);
        assertEquals(1.0D, variables.get("pre_animation_count").getAsNumber());

        query.set("frame_alpha", Value.of(0.5F));
        runtime.sample(model, 0.5F, scope, MoLangEvaluationContext.EMPTY);
        assertEquals(50.0F, model.bone("root").getRotation().x, 1.0e-4F);
        assertEquals(2.0D, variables.get("pre_animation_count").getAsNumber());

        runtime.sample(model, 0.5F, scope, MoLangEvaluationContext.EMPTY);
        assertEquals(50.0F, model.bone("root").getRotation().x, 1.0e-4F);
        assertEquals(2.0D, variables.get("pre_animation_count").getAsNumber());
        assertEquals(12L, runtime.lastTick());
    }

    private static ClientEntityAnimationRuntime runtime(BedrockEntityData entity,
                                                        Map<String, String> overrides,
                                                        Content pack) {
        final Scope scope = scope();
        return runtime(entity, overrides, pack, new AnimationClock.Client(), listener(scope));
    }

    private static ClientEntityAnimationRuntime runtime(BedrockEntityData entity,
                                                        Map<String, String> overrides,
                                                        Content pack,
                                                        AnimationClock.Client clock,
                                                        AnimationEventListener listener) {
        return new ClientEntityAnimationRuntime(entity, overrides, new PackManager(List.of(pack)),
                clock, listener);
    }

    private static BedrockEntityData entity(List<String> initialize, List<String> preAnimation,
                                            List<BedrockEntityData.Scripts.Animate> animates,
                                            Map<String, String> aliases) {
        final BedrockEntityData.Scripts scripts = new BedrockEntityData.Scripts(
                initialize, preAnimation,
                new BedrockEntityData.Scripts.Scale("1", "1", "1", "1"), animates);
        return new BedrockEntityData("minecraft:player", scripts, List.of(), Map.of(), aliases,
                Map.of(), Map.of(), Map.of());
    }

    private static Content animationsPack() {
        final Content pack = new Content();
        pack.putString("animations/test.json", """
                {"format_version":"1.10.0","animations":{
                  "animation.test.base":{"loop":true,"bones":{"root":{"rotation":[10,0,0]}}},
                  "animation.test.override":{"loop":true,"bones":{"root":{"rotation":[60,0,0]}}}
                }}
                """);
        return pack;
    }

    private static Scope scope() {
        final Scope scope = Scope.create();
        final MutableObjectBinding query = new MutableObjectBinding();
        scope.set("query", query);
        scope.set("q", query);
        final MutableObjectBinding variables = new MutableObjectBinding();
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

    private static final class RecordingListener implements AnimationEventListener {
        private final Scope scope;
        private final List<String> timeline = new ArrayList<>();

        private RecordingListener(Scope scope) {
            this.scope = scope;
        }

        @Override
        public void onTimelineEvent(List<String> expressions) {
            timeline.addAll(expressions);
        }

        @Override
        public Scope getEntityScope() {
            return scope;
        }
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
        private float scaleX = 1.0F;
        private float scaleY = 1.0F;
        private float scaleZ = 1.0F;

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
        public void addOffset(Vector3f value) {
            offset.add(value);
        }

        @Override
        public void addRotation(Vector3f value) {
            rotation.add(value);
        }

        @Override
        public void addScale(float x, float y, float z) {
            scaleX += x;
            scaleY += y;
            scaleZ += z;
        }

        @Override
        public void resetToDefaultPose() {
            rotation.set(0.0F);
            offset.set(0.0F);
            scaleX = scaleY = scaleZ = 1.0F;
        }

        @Override
        public Map<String, IBoneTarget> getChildren() {
            return Map.of();
        }
    }
}
