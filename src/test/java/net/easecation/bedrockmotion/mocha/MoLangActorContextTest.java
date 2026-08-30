package net.easecation.bedrockmotion.mocha;

import com.google.gson.JsonParser;
import net.easecation.bedrockmotion.animation.Animation;
import net.easecation.bedrockmotion.animation.vanilla.AnimateBuilder;
import net.easecation.bedrockmotion.animator.Animator;
import net.easecation.bedrockmotion.controller.AnimationController;
import net.easecation.bedrockmotion.controller.AnimationControllerInstance;
import net.easecation.bedrockmotion.controller.BlendTransitionCurve;
import net.easecation.bedrockmotion.model.AnimationEventListener;
import net.easecation.bedrockmotion.model.IBoneModel;
import net.easecation.bedrockmotion.model.IBoneTarget;
import net.easecation.bedrockmotion.pack.definitions.AnimationDefinitions;
import net.easecation.bedrockmotion.pack.definitions.AnimationControllerDefinitions;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import team.unnamed.mocha.runtime.Scope;
import team.unnamed.mocha.runtime.value.Function;
import team.unnamed.mocha.runtime.value.MutableObjectBinding;
import team.unnamed.mocha.runtime.value.NumberValue;
import team.unnamed.mocha.runtime.value.Value;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoLangActorContextTest {
    @Test
    void owningEntityArrowSwitchesTheMochaExecutionActor() throws Exception {
        Actor attachable = new Actor(2.0D);
        Actor owner = new Actor(9.0D);
        MoLangEvaluationContext context =
                new MoLangEvaluationContext(attachable, owner, null, "mainhand", Map.of(), "first_person");

        MutableObjectBinding contextBinding = new MutableObjectBinding();
        contextBinding.set("owning_entity", context.owningEntityValue());
        Scope scope = Scope.create();
        scope.set("context", contextBinding);
        scope.set("c", contextBinding);
        Function<Object> actorValue = (execution, arguments) ->
                NumberValue.of(((Actor) execution.entity()).value());
        scope.set("actor_value", actorValue);

        assertEquals(2.0D, MoLangEngine.eval(scope, context, "actor_value()").getAsNumber());
        assertEquals(9.0D,
                MoLangEngine.eval(scope, context, "c.owning_entity -> actor_value()").getAsNumber());
        assertEquals(9.0D,
                MoLangEngine.eval(scope, context, "c.owning_entity -> variable.attack_time").getAsNumber());
    }

    @Test
    void transitionConditionReadsOwnerActorVariable() {
        MoLangEvaluationContext context = ownerContext();
        Scope scope = controllerScope(context);
        AnimationController controller = new AnimationController("controller.test", "default",
                Map.of(
                        "default", state(List.of(), List.of(new AnimationController.Transition(
                                "next", "c.owning_entity -> variable.attack_time > 5")), List.of()),
                        "next", state(List.of(), List.of(), List.of())));
        AnimationControllerInstance instance = new AnimationControllerInstance(
                controller, Map.of(), new AnimationDefinitions(Map.of()),
                new AnimationControllerDefinitions(Map.of()), listener(scope));

        instance.tick(scope, context);

        assertEquals("next", instance.currentStateName());
    }

    @Test
    void onEntryScriptReadsOwnerActorVariable() {
        MoLangEvaluationContext context = ownerContext();
        Scope scope = controllerScope(context);
        AnimationController controller = new AnimationController("controller.test", "default",
                Map.of(
                        "default", state(List.of(), List.of(new AnimationController.Transition("next", "1")),
                                List.of()),
                        "next", state(List.of(), List.of(),
                                List.of("v.entry_value = (c.owning_entity -> variable.attack_time);"))));
        AnimationControllerInstance instance = new AnimationControllerInstance(
                controller, Map.of(), new AnimationDefinitions(Map.of()),
                new AnimationControllerDefinitions(Map.of()), listener(scope));

        instance.tick(scope, context);

        assertEquals(9.0D,
                ((MutableObjectBinding) scope.get("variable")).get("entry_value").getAsNumber());
    }

    @Test
    void blendWeightReadsOwnerActorVariable() {
        MoLangEvaluationContext context = ownerContext();
        Scope scope = controllerScope(context);
        AnimationController controller = new AnimationController("controller.test", "default",
                Map.of("default", state(
                        List.of(new AnimationController.StateAnimation(
                                "spin", "(c.owning_entity -> variable.attack_time) / 10")),
                        List.of(), List.of())));
        AnimationControllerInstance instance = new AnimationControllerInstance(controller,
                Map.of("spin", "animation.test.spin"),
                new AnimationDefinitions(Map.of(
                        "animation.test.spin", constantRotationAnimation("animation.test.spin"))),
                new AnimationControllerDefinitions(Map.of()),
                listener(scope));
        instance.setBaseScope(scope);
        instance.setEvaluationContext(context);

        instance.tick(scope, context);
        TestModel model = new TestModel("root");
        instance.animate(model);

        // Owner attack_time 9.0 -> blend weight 0.9 -> constant rotation 50 deg sampled at 45 deg.
        assertEquals(45.0, model.bone("root").getRotation().x, 1.0e-4);
    }

    @Test
    void nestedControllerUsesParentBlendWeight() {
        Scope scope = controllerScope(MoLangEvaluationContext.EMPTY);
        AnimationController child = new AnimationController("controller.test.child", "default",
                Map.of("default", state(
                        List.of(new AnimationController.StateAnimation("spin", "1")),
                        List.of(), List.of())));
        AnimationController parent = new AnimationController("controller.test.parent", "default",
                Map.of("default", state(
                        List.of(new AnimationController.StateAnimation("child", "0.5")),
                        List.of(), List.of())));
        Map<String, String> aliases = Map.of(
                "child", "controller.test.child",
                "spin", "animation.test.spin");
        AnimationControllerInstance instance = new AnimationControllerInstance(
                parent,
                aliases,
                new AnimationDefinitions(Map.of(
                        "animation.test.spin", constantRotationAnimation("animation.test.spin"))),
                new AnimationControllerDefinitions(Map.of(child.getIdentifier(), child)),
                listener(scope));

        instance.tick(scope, MoLangEvaluationContext.EMPTY);
        TestModel model = new TestModel("root");
        instance.animate(model);

        assertEquals(25.0, model.bone("root").getRotation().x, 1.0e-4);
    }

    @Test
    void nestedControllerCycleFailsDuringConstruction() {
        Scope scope = controllerScope(MoLangEvaluationContext.EMPTY);
        AnimationController first = new AnimationController("controller.test.first", "default",
                Map.of("default", state(
                        List.of(new AnimationController.StateAnimation("second", "1")),
                        List.of(), List.of())));
        AnimationController second = new AnimationController("controller.test.second", "default",
                Map.of("default", state(
                        List.of(new AnimationController.StateAnimation("first", "1")),
                        List.of(), List.of())));
        AnimationControllerDefinitions controllers = new AnimationControllerDefinitions(Map.of(
                first.getIdentifier(), first,
                second.getIdentifier(), second));

        assertThrows(IllegalArgumentException.class, () -> new AnimationControllerInstance(
                first,
                Map.of(
                        "first", first.getIdentifier(),
                        "second", second.getIdentifier()),
                new AnimationDefinitions(Map.of()),
                controllers,
                listener(scope)));
    }

    @Test
    void boneKeyframeExpressionReadsOwnerActorVariable() throws Exception {
        MoLangEvaluationContext context = ownerContext();
        Scope scope = controllerScope(context);
        final String json = """
                {"format_version":"1.10.0","animations":{
                  "animation.test.owner_keyframe":{"loop":"hold_on_last_frame","animation_length":1.0,
                    "bones":{"root":{"rotation":{"0.0":[
                      "c.owning_entity -> variable.attack_time",0,0]}}}}
                }}
                """;
        final Animation animation = Animation.parse(JsonParser.parseString(json).getAsJsonObject()).getFirst();
        final Animator animator = new Animator(listener(scope),
                new AnimationDefinitions.AnimationData(animation, AnimateBuilder.build(animation)));
        animator.setBaseScope(scope);
        animator.setEvaluationContext(context);

        TestModel model = new TestModel("root");
        animator.animate(model);

        assertEquals(9.0, model.bone("root").getRotation().x, 1.0e-4);
    }

    private static MoLangEvaluationContext ownerContext() {
        return new MoLangEvaluationContext(new Actor(2.0D), new Actor(9.0D), null, "mainhand",
                Map.of(), "first_person");
    }

    private static Scope controllerScope(MoLangEvaluationContext context) {
        MutableObjectBinding contextBinding = new MutableObjectBinding();
        contextBinding.set("owning_entity", context.owningEntityValue());
        MutableObjectBinding query = new MutableObjectBinding();
        MutableObjectBinding variables = new MutableObjectBinding();
        Scope scope = Scope.create();
        scope.set("context", contextBinding);
        scope.set("c", contextBinding);
        scope.set("query", query);
        scope.set("q", query);
        scope.set("variable", variables);
        scope.set("v", variables);
        return scope;
    }

    private static AnimationController.State state(List<AnimationController.StateAnimation> animations,
                                                   List<AnimationController.Transition> transitions,
                                                   List<String> onEntry) {
        return new AnimationController.State(animations, transitions, onEntry, List.of(), List.of(),
                BlendTransitionCurve.NONE, false);
    }

    private static AnimationDefinitions.AnimationData constantRotationAnimation(String identifier) {
        final String json = """
                {"format_version":"1.10.0","animations":{
                  "%s":{"loop":false,"animation_length":100.0,
                    "bones":{"root":{"rotation":{"0.0":[50,0,0]}}}}
                }}
                """.formatted(identifier);
        final Animation animation = Animation.parse(JsonParser.parseString(json).getAsJsonObject()).getFirst();
        return new AnimationDefinitions.AnimationData(animation, AnimateBuilder.build(animation));
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

    private record Actor(double value) implements MoLangEvaluationContext.Actor {
        @Override
        public Value variable(String normalizedName) {
            return normalizedName.equalsIgnoreCase("attack_time")
                    ? NumberValue.of(value)
                    : NumberValue.zero();
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
