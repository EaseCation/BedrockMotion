package net.easecation.bedrockmotion.animation.vanilla;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import net.easecation.bedrockmotion.animation.Animation;
import net.easecation.bedrockmotion.animator.AnimationClock;
import net.easecation.bedrockmotion.controller.AnimationControllerInstance;
import net.easecation.bedrockmotion.model.AnimationEventListener;
import net.easecation.bedrockmotion.model.IBoneModel;
import net.easecation.bedrockmotion.model.IBoneTarget;
import net.easecation.bedrockmotion.pack.PackManager;
import net.easecation.bedrockmotion.pack.content.Content;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import team.unnamed.mocha.runtime.Scope;
import team.unnamed.mocha.runtime.binding.JavaObjectBinding;
import team.unnamed.mocha.runtime.standard.MochaMath;
import team.unnamed.mocha.runtime.value.MutableObjectBinding;
import team.unnamed.mocha.runtime.value.Value;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockScaleRegressionTest {
    @Test
    void twoHiddenLayersCannotTurnZeroScaleNegative() {
        final TestModel model = new TestModel();
        final VBUAnimation hidden = animation("0");
        sample(model, hidden, 0L, 0.5F);
        assertScale(model, 0.5F, 0.5F, 0.5F);
        sample(model, hidden, 0L, 1.0F);
        assertScale(model, 0.0F, 0.0F, 0.0F);
    }

    @Test
    void weightedScaleMultipliesExistingPoseAndPreservesAuthoredNegativeScale() {
        final TestModel model = new TestModel();
        model.bone.setScale(2.0F, 3.0F, 4.0F);
        sample(model, animation("[2,0.5,-1]"), 0L, 0.5F);
        assertScale(model, 3.0F, 2.25F, 0.0F);
        model.resetAllBones();
        sample(model, animation("[-1,-2,-3]"), 0L, 1.0F);
        assertScale(model, -1.0F, -2.0F, -3.0F);
    }

    @Test
    void omittedInterpolationStaysLinearWithoutInventingVisibleOvershoot() {
        final TestModel model = new TestModel();
        sample(model, animation("{\"0.0\":[0,0,0],\"1.0\":[0,0,0],\"2.0\":[1,1,1]}"),
                500L, 1.0F);
        assertScale(model, 0.0F, 0.0F, 0.0F);
        sample(model, animation("{\"0.0\":{\"post\":[0,0,0]},\"1.0\":{\"post\":[0,0,0]},\"2.0\":[1,1,1]}"),
                500L, 1.0F);
        assertScale(model, 0.0F, 0.0F, 0.0F);
    }

    @Test
    void prePostKeysUsePreBeforeFirstAndPostAtExactKeyAndAfterLast() {
        final VBUAnimation animation = animation("{\"1.0\":{\"pre\":[0,0,0],\"post\":[1,1,1]},"
                + "\"2.0\":{\"pre\":[1,1,1],\"post\":[0,0,0]}}");
        for (long millis : new long[]{0L, 999L, 1000L, 1500L, 1999L, 2000L, 2100L}) {
            final TestModel model = new TestModel();
            sample(model, animation, millis, 1.0F);
            final float expected = millis >= 1000L && millis < 2000L ? 1.0F : 0.0F;
            assertScale(model, expected, expected, expected);
        }
    }

    @Test
    void explicitCatmullRomRemainsCubic() {
        final TestModel model = new TestModel();
        sample(model, animation("{\"0.0\":{\"post\":[0,0,0],\"lerp_mode\":\"catmullrom\"},"
                + "\"1.0\":{\"post\":[0,0,0],\"lerp_mode\":\"catmullrom\"},\"2.0\":[1,1,1]}"),
                500L, 1.0F);
        assertScale(model, -0.0625F, -0.0625F, -0.0625F);
    }

    @Test
    void nextKeyCubicOrStepDoesNotChangePrecedingLinearInterval() {
        for (String mode : List.of("catmullrom", "step")) {
            final TestModel model = new TestModel();
            sample(model, animation("{\"0.0\":[0,0,0],\"1.0\":{\"post\":[1,1,1],\"lerp_mode\":\""
                    + mode + "\"},\"2.0\":[0,0,0]}"), 500L, 1.0F);
            assertScale(model, 0.5F, 0.5F, 0.5F);
        }
    }

    @Test
    void outgoingStepStaysConstantUntilNextKey() {
        final VBUAnimation animation = animation("{\"0.0\":{\"post\":[0,0,0],\"lerp_mode\":\"step\"},"
                + "\"1.0\":[1,1,1]}");
        final TestModel before = new TestModel();
        sample(before, animation, 999L, 1.0F);
        assertScale(before, 0.0F, 0.0F, 0.0F);
        final TestModel exact = new TestModel();
        sample(exact, animation, 1000L, 1.0F);
        assertScale(exact, 1.0F, 1.0F, 1.0F);
    }

    @Test
    void ordinaryCrossfadeUsesNativeProductRatherThanClampingAllHiddenKeys() {
        final TestModel model = new TestModel();
        sample(model, animation("0"), 0L, 0.5F);
        sample(model, animation("0"), 0L, 0.5F);
        assertScale(model, 0.25F, 0.25F, 0.25F);
    }

    @Test
    void actualRuneLegendEffectChannelsMatchLinearPrePostReferenceAroundEveryKey() throws IOException {
        final JsonArray channels = JsonParser.parseString(resource("scale-audit.json")).getAsJsonArray();
        int checkedChannels = 0;
        for (JsonElement element : channels) {
            final JsonObject channel = element.getAsJsonObject();
            final TreeMap<Float, JsonElement> keys = new TreeMap<>();
            boolean cubic = false;
            for (Map.Entry<String, JsonElement> entry : channel.getAsJsonObject("scale").entrySet()) {
                keys.put(Float.parseFloat(entry.getKey()), entry.getValue());
                cubic |= entry.getValue().isJsonObject()
                        && entry.getValue().getAsJsonObject().has("lerp_mode")
                        && entry.getValue().getAsJsonObject().get("lerp_mode").getAsString().equals("catmullrom");
            }
            if (cubic) {
                continue;
            }
            final VBUAnimation animation = animation(channel.get("scale").toString());
            final String source = channel.get("animation").getAsString() + "/" + channel.get("bone").getAsString();
            for (float keyTime : keys.keySet()) {
                final long millis = Math.round(keyTime * 1000.0F);
                for (long sampleTime : new long[]{Math.max(0L, millis - 1L), millis, millis + 1L}) {
                    final float seconds = sampleTime / 1000.0F;
                    final Vector3f expected = reference(keys, seconds);
                    final TestModel model = new TestModel();
                    sample(model, animation, sampleTime, 1.0F);
                    assertEquals(expected.x, model.bone.getScaleX(), 1.0e-4F, source + "@" + sampleTime);
                    assertEquals(expected.y, model.bone.getScaleY(), 1.0e-4F, source + "@" + sampleTime);
                    assertEquals(expected.z, model.bone.getScaleZ(), 1.0e-4F, source + "@" + sampleTime);
                }
            }
            checkedChannels++;
        }
        assertEquals(297, checkedChannels);
    }

    private static Vector3f reference(TreeMap<Float, JsonElement> keys, float seconds) {
        if (seconds < keys.firstKey()) {
            return vector(keys.firstEntry().getValue(), "pre");
        }
        final Map.Entry<Float, JsonElement> before = keys.floorEntry(seconds);
        final Map.Entry<Float, JsonElement> after = keys.higherEntry(seconds);
        final Vector3f first = vector(before.getValue(), "post");
        if (after == null || seconds == before.getKey()) {
            return first;
        }
        final float alpha = (seconds - before.getKey()) / (after.getKey() - before.getKey());
        return first.lerp(vector(after.getValue(), "pre"), alpha);
    }

    private static Vector3f vector(JsonElement key, String side) {
        final JsonElement value;
        if (key.isJsonObject()) {
            final JsonObject object = key.getAsJsonObject();
            value = object.has(side) ? object.get(side) : object.get(side.equals("pre") ? "post" : "pre");
        } else {
            value = key;
        }
        assertTrue(value.isJsonArray());
        final JsonArray xyz = value.getAsJsonArray();
        return new Vector3f(xyz.get(0).getAsFloat(), xyz.get(1).getAsFloat(), xyz.get(2).getAsFloat());
    }

    @Test
    void lockBossOriginalControllersHideShockwaveThroughoutUnrelatedSkillTransitions() throws IOException {
        for (int variant : new int[]{1, 2, 3, 4, 6, 7, 8}) {
            final BossRuntime runtime = new BossRuntime();
            runtime.frame(0L, 0.0F, 0);
            runtime.frame(1L, 0.0F, variant);
            for (float partial : new float[]{0.0F, 0.5F, 1.0F}) {
                runtime.frame(2L, partial, variant);
                assertScale(runtime.model, 0.0F, 0.0F, 0.0F);
            }
            runtime.frame(5L, 0.0F, variant);
            assertScale(runtime.model, 0.0F, 0.0F, 0.0F);
        }
    }

    @Test
    void lockBossStillShowsShockwaveAtImpactAndHidesItAfterRecovery() throws IOException {
        final BossRuntime runtime = new BossRuntime();
        runtime.frame(0L, 0.0F, 0);
        runtime.frame(1L, 0.0F, 5);
        runtime.frame(49L, 0.0F, 5);
        assertEquals("skill_impact_start", runtime.controllers.get(2).currentStateName());
        assertScale(runtime.model, 1.0F, 1.4F, 1.7F);
        runtime.frame(50L, 0.0F, 0);
        runtime.frame(60L, 0.0F, 0);
        assertScale(runtime.model, 0.0F, 0.0F, 0.0F);
    }

    private static VBUAnimation animation(String scale) {
        final JsonObject json = JsonParser.parseString("{\"animations\":{\"animation.test\":{\"animation_length\":3,"
                + "\"bones\":{\"root_effect\":{\"scale\":" + scale + "}}}}}").getAsJsonObject();
        return AnimateBuilder.build(Animation.parse(json).getFirst());
    }

    private static void sample(TestModel model, VBUAnimation animation, long millis, float weight) {
        AnimationHelper.animate(Scope.create(), model, animation, millis, weight, new Vector3f(), null);
    }

    private static void assertScale(TestModel model, float x, float y, float z) {
        assertEquals(x, model.bone.getScaleX(), 1.0e-5F);
        assertEquals(y, model.bone.getScaleY(), 1.0e-5F);
        assertEquals(z, model.bone.getScaleZ(), 1.0e-5F);
    }

    private static String resource(String name) throws IOException {
        try (InputStream input = BedrockScaleRegressionTest.class.getResourceAsStream("/runelegend-lock/" + name)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final class BossRuntime implements AnimationEventListener {
        private final Scope scope = Scope.create();
        private final MutableObjectBinding query = new MutableObjectBinding();
        private final AnimationClock.Client clock = new AnimationClock.Client();
        private final List<AnimationControllerInstance> controllers = new ArrayList<>();
        private final TestModel model = new TestModel();

        private BossRuntime() throws IOException {
            scope.set("query", query);
            scope.set("q", query);
            final MutableObjectBinding variables = new MutableObjectBinding();
            scope.set("variable", variables);
            scope.set("v", variables);
            scope.set("math", JavaObjectBinding.of(MochaMath.class, null, new MochaMath()));
            final Content content = new Content();
            content.putString("animations/lock.json", resource("animations.json"));
            content.putString("animation_controllers/lock.json", resource("controllers.json"));
            final PackManager packs = new PackManager(List.of(content));
            final JsonObject description = JsonParser.parseString(resource("entity.json")).getAsJsonObject()
                    .getAsJsonObject("minecraft:client_entity").getAsJsonObject("description");
            final Map<String, String> aliases = new LinkedHashMap<>();
            description.getAsJsonObject("animations").entrySet()
                    .forEach(entry -> aliases.put(entry.getKey(), entry.getValue().getAsString()));
            for (String alias : List.of("controller_move", "controller_hit", "controller_skill", "controller_unbalanced")) {
                controllers.add(new AnimationControllerInstance(
                        packs.getAnimationControllerDefinitions().getControllers().get(aliases.get(alias)),
                        aliases, packs.getAnimationDefinitions(), packs.getAnimationControllerDefinitions(), this, clock));
            }
        }

        private void frame(long tick, float partial, int variant) {
            clock.advanceTick(tick);
            clock.sample(partial);
            query.set("variant", Value.of(variant));
            query.set("mark_variant", Value.of(0));
            query.set("modified_move_speed", Value.of(0));
            query.set("hurt_time", Value.of(0));
            controllers.forEach(controller -> {
                controller.setBaseScope(scope);
                controller.tick(scope);
            });
            model.resetAllBones();
            controllers.forEach(controller -> controller.animate(model, false));
        }

        @Override
        public void onTimelineEvent(List<String> expressions) {
        }

        @Override
        public Scope getEntityScope() {
            return scope;
        }
    }

    private static final class TestModel implements IBoneModel {
        private final TestBone bone = new TestBone();
        @Override public Map<String, IBoneTarget> getBoneIndex() { return Map.of("root_effect", bone); }
        @Override public Iterable<IBoneTarget> getAllBones() { return List.of(bone); }
        @Override public void resetAllBones() { bone.resetToDefaultPose(); }
    }

    private static final class TestBone implements IBoneTarget {
        private final Vector3f rotation = new Vector3f();
        private final Vector3f offset = new Vector3f();
        private final Vector3f scale = new Vector3f(1.0F);
        @Override public String getName() { return "root_effect"; }
        @Override public Vector3f getRotation() { return rotation; }
        @Override public Vector3f getOffset() { return offset; }
        @Override public float getScaleX() { return scale.x; }
        @Override public float getScaleY() { return scale.y; }
        @Override public float getScaleZ() { return scale.z; }
        @Override public void setScale(float x, float y, float z) { scale.set(x, y, z); }
        @Override public void addOffset(Vector3f value) { offset.add(value); }
        @Override public void addRotation(Vector3f value) { rotation.add(value); }
        @Override public void addScale(float x, float y, float z) { scale.add(x, y, z); }
        @Override public void resetToDefaultPose() { rotation.zero(); offset.zero(); scale.set(1.0F); }
        @Override public Map<String, IBoneTarget> getChildren() { return Map.of(); }
    }
}
