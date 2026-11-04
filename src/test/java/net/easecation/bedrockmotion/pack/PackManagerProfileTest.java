package net.easecation.bedrockmotion.pack;

import net.easecation.bedrockmotion.animation.Animation;
import net.easecation.bedrockmotion.animation.element.timestamp.SimpleTimeStamp;
import net.easecation.bedrockmotion.animation.vanilla.AnimateTransformation;
import net.easecation.bedrockmotion.animation.vanilla.VBUAnimation;
import net.easecation.bedrockmotion.animation.vanilla.VBUKeyFrame;
import net.easecation.bedrockmotion.animator.Animator;
import net.easecation.bedrockmotion.model.AnimationEventListener;
import net.easecation.bedrockmotion.mocha.MoLangEngine;
import net.easecation.bedrockmotion.pack.content.Content;
import net.easecation.bedrockmotion.pack.definitions.AnimationDefinitions;
import net.easecation.bedrockmotion.util.mojangweirdformat.ValueOrValue;
import org.junit.jupiter.api.Test;
import team.unnamed.mocha.parser.ast.BinaryExpression;
import team.unnamed.mocha.parser.ast.DoubleExpression;
import team.unnamed.mocha.runtime.Scope;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackManagerProfileTest {

    @Test
    void legacyConstructorBuildsFullProfileAndRetainsPacks() {
        final Content customPack = animationPack();

        final PackManager packManager = new PackManager(List.of(customPack));

        assertEquals(PackManager.Profile.FULL, packManager.getProfile());
        assertTrue(packManager.getPacks().contains(customPack));
        assertNotNull(packManager.getEntityDefinitions());
        assertNotNull(packManager.getModelDefinitions());
        assertNotNull(packManager.getMaterialDefinitions());
        packManager.getAnimationDefinitions().getAnimations().clear();
        packManager.getAnimationControllerDefinitions().getControllers().clear();
        packManager.getRenderControllerDefinitions().getRenderControllers().clear();
        assertTrue(packManager.getAnimationDefinitions().getAnimations().isEmpty());
        assertTrue(packManager.getAnimationControllerDefinitions().getControllers().isEmpty());
        assertTrue(packManager.getRenderControllerDefinitions().getRenderControllers().isEmpty());

        final PackManager mutableAnimationManager = new PackManager(List.of(animationPack()));
        final Animation legacyAnimation = mutableAnimationManager.getAnimationDefinitions().getAnimations()
                .get("animation.test.idle").animation();
        legacyAnimation.setAnimationLength(2F);
        legacyAnimation.getTimeline().put(0.75F, List.of("variable.legacy = 1"));
        assertEquals(2F, legacyAnimation.getAnimationLength());
        assertTrue(legacyAnimation.getTimeline().containsKey(0.75F));
        final VBUAnimation legacyCompiled = mutableAnimationManager.getAnimationDefinitions().getAnimations()
                .get("animation.test.idle").compiled();
        final AnimateTransformation legacyTransformation = legacyCompiled.boneAnimations()
                .get("head").getFirst();
        legacyTransformation.keyframes()[0] = null;
        assertNull(legacyTransformation.keyframes()[0]);
        legacyCompiled.boneAnimations().clear();
        assertTrue(legacyCompiled.boneAnimations().isEmpty());
    }

    @Test
    void bundledVanillaContainsPlayerHostAnimationClosure() {
        final PackManager packManager = new PackManager(List.of());

        assertTrue(packManager.getAnimationControllerDefinitions().getControllers()
                .containsKey("controller.animation.player.root"));
        assertTrue(packManager.getAnimationControllerDefinitions().getControllers()
                .containsKey("controller.animation.player.first_person_attack"));
        assertTrue(packManager.getAnimationControllerDefinitions().getControllers()
                .containsKey("controller.animation.humanoid.look_at_target"));
        assertTrue(packManager.getAnimationControllerDefinitions().getControllers()
                .containsKey("controller.animation.persona.blink"));
        assertTrue(packManager.getAnimationDefinitions().getAnimations()
                .containsKey("animation.player.first_person.base_pose"));
    }

    @Test
    void serverAnimationProfileBuildsOnlyRequiredDefinitionsAndDropsPacks() {
        final Content customPack = animationPack();

        final PackManager packManager = new PackManager(
                List.of(customPack), PackManager.Profile.SERVER_ANIMATION);

        assertEquals(PackManager.Profile.SERVER_ANIMATION, packManager.getProfile());
        assertTrue(packManager.getPacks().isEmpty());
        assertNull(packManager.getEntityDefinitions());
        assertNull(packManager.getModelDefinitions());
        assertNull(packManager.getMaterialDefinitions());
        assertTrue(packManager.getAnimationDefinitions().getAnimations().containsKey("animation.test.idle"));
        assertTrue(packManager.getAnimationControllerDefinitions().getControllers().containsKey("controller.animation.test"));
        assertTrue(packManager.getRenderControllerDefinitions().getRenderControllers().containsKey("controller.render.test"));

        final PackManager explicitRetain = new PackManager(
                List.of(customPack), PackManager.Profile.SERVER_ANIMATION, true);
        assertTrue(explicitRetain.getPacks().isEmpty(),
                "SERVER_ANIMATION must never retain expanded pack contents");
    }

    @Test
    void sharedLayersFoldWithoutReparsingCommonDefinitions() {
        final ServerAnimationLayer common = ServerAnimationLayer.fromContent(animationPack());
        final Content malformedOverride = new Content();
        malformedOverride.putString("animations/broken.json", "{not json");
        final ServerAnimationLayer high = ServerAnimationLayer.fromContent(malformedOverride);

        final PackManager packManager = PackManager.fromServerAnimationLayers(List.of(common, high));

        assertSame(common.animations().get("animation.test.idle"),
                packManager.getAnimationDefinitions().getAnimations().get("animation.test.idle"));
        assertSame(common.renderControllers().get("controller.render.test"),
                packManager.getRenderControllerDefinitions().getRenderControllers().get("controller.render.test"));
        assertTrue(packManager.getAnimationControllerDefinitions().getControllers()
                .containsKey("controller.animation.test"));
        assertThrows(UnsupportedOperationException.class, () ->
                packManager.getAnimationDefinitions().getAnimations().clear());
        assertTrue(packManager.getPacks().isEmpty());
        assertEquals(64, PackManager.serverAnimationFingerprint().length());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void serverAnimationDefinitionsAreDeeplyImmutable() {
        final PackManager packManager = new PackManager(
                List.of(animationPack()), PackManager.Profile.SERVER_ANIMATION);
        final AnimationDefinitions.AnimationData data = packManager.getAnimationDefinitions()
                .getAnimations().get("animation.test.idle");
        final Animation animation = data.animation();

        assertThrows(UnsupportedOperationException.class,
                () -> packManager.getAnimationDefinitions().getAnimations().clear());
        assertThrows(UnsupportedOperationException.class, () -> animation.setAnimationLength(99F));
        assertThrows(UnsupportedOperationException.class,
                () -> ((ValueOrValue) animation.getLoop()).setValue(false));
        assertThrows(UnsupportedOperationException.class, () -> animation.getCubes().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> animation.getCubes().getFirst().setRotation(new ValueOrValue<>(0F)));
        final Map<Float, ValueOrValue<?>> rotation = (Map<Float, ValueOrValue<?>>)
                animation.getCubes().getFirst().getRotation().getValue();
        assertThrows(UnsupportedOperationException.class, rotation::clear);
        assertThrows(UnsupportedOperationException.class,
                () -> ((ValueOrValue) rotation.get(0F)).setValue("mutated"));
        final SimpleTimeStamp timestamp = (SimpleTimeStamp) rotation.get(0F).getValue();
        final String[] timestampValue = timestamp.value();
        timestampValue[0] = "mutated";
        final SimpleTimeStamp storedTimestamp = (SimpleTimeStamp) rotation.get(0F).getValue();
        assertTrue(!"mutated".equals(storedTimestamp.value()[0]));
        assertThrows(UnsupportedOperationException.class, () -> animation.getTimeline().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> animation.getTimeline().get(0.5F).add("mutated"));
        assertThrows(UnsupportedOperationException.class, () -> animation.getParticleEffects().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> animation.getParticleEffects().get(0.25F).clear());

        assertThrows(UnsupportedOperationException.class, () -> data.compiled().boneAnimations().clear());
        final List<AnimateTransformation> transformations = data.compiled().boneAnimations().get("head");
        assertThrows(UnsupportedOperationException.class, transformations::clear);
        final AnimateTransformation transformation = transformations.getFirst();
        final VBUKeyFrame[] keyframes = transformation.keyframes();
        keyframes[0] = null;
        assertNotNull(transformation.keyframes()[0]);
        final VBUKeyFrame keyframe = transformation.keyframes()[0];
        final AnimateTransformation.ResolvedComponent[] resolved = keyframe.post();
        resolved[0] = null;
        assertNotNull(keyframe.post()[0]);
        final AnimateTransformation.ResolvedComponent expressionComponent =
                transformation.keyframes()[1].post()[0];
        final BinaryExpression exposedExpression = (BinaryExpression) expressionComponent.expr().getFirst();
        exposedExpression.left(new DoubleExpression(99D));
        assertEquals(3D, MoLangEngine.eval(Scope.create(), expressionComponent.expr()).getAsNumber());

        final PackManager secondPackManager = new PackManager(
                List.of(animationPack()), PackManager.Profile.SERVER_ANIMATION);
        final AnimateTransformation.ResolvedComponent secondComponent = secondPackManager
                .getAnimationDefinitions().getAnimations().get("animation.test.idle").compiled()
                .boneAnimations().get("head").getFirst().keyframes()[1].post()[0];
        assertEquals(3D, MoLangEngine.eval(Scope.create(), secondComponent.expr()).getAsNumber());

        final var renderController = packManager.getRenderControllerDefinitions()
                .getRenderControllers().get("controller.render.test");
        assertThrows(UnsupportedOperationException.class, () -> renderController.materialsMap().clear());
        assertThrows(UnsupportedOperationException.class, () -> renderController.textureExpressions().clear());
        assertThrows(UnsupportedOperationException.class, () -> renderController.textures().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> renderController.textures().getFirst().values().clear());
        assertThrows(UnsupportedOperationException.class, () -> renderController.partVisibility().clear());
        assertThrows(UnsupportedOperationException.class, () -> packManager
                .getAnimationControllerDefinitions().getControllers().get("controller.animation.test")
                .getStates().get("default").getAnimations().clear());
    }

    @Test
    void sharedDefinitionCreatesIndependentAnimatorState() {
        final PackManager packManager = new PackManager(
                List.of(animationPack()), PackManager.Profile.SERVER_ANIMATION);
        final AnimationDefinitions.AnimationData shared = packManager.getAnimationDefinitions()
                .getAnimations().get("animation.test.idle");
        final AnimationEventListener listener = new NoopListener();
        final Animator first = new Animator(listener, shared);
        final Animator second = new Animator(listener, shared);

        first.setBlendWeight(0.25F);
        first.stop();

        assertEquals(0.25F, first.getBlendWeight());
        assertEquals(1F, second.getBlendWeight());
        assertTrue(first.isDonePlaying());
        assertTrue(!second.isDonePlaying());
        assertSame(shared, packManager.getAnimationDefinitions().getAnimations().get("animation.test.idle"));
    }

    private static Content animationPack() {
        final Content content = new Content();
        content.putString("animations/test.json", """
                {
                  "format_version": "1.8.0",
                  "animations": {
                    "animation.test.idle": {
                      "loop": true,
                      "animation_length": 1.0,
                      "timeline": {"0.5": ["variable.timeline = 1"]},
                      "particle_effects": {
                        "0.25": {"effect": "spark", "locator": "head"}
                      },
                      "bones": {
                        "head": {
                          "rotation": {
                            "0.0": [0, 0, 0],
                            "1.0": ["1 + 2", 30, 0]
                          }
                        }
                      }
                    }
                  }
                }
                """);
        content.putString("animation_controllers/test.json", """
                {
                  "format_version": "1.10.0",
                  "animation_controllers": {
                    "controller.animation.test": {
                      "initial_state": "default",
                      "states": {
                        "default": {
                          "animations": ["idle"]
                        }
                      }
                    }
                  }
                }
                """);
        content.putString("render_controllers/test.json", """
                {
                  "format_version": "1.8.0",
                  "render_controllers": {
                    "controller.render.test": {
                      "geometry": "Geometry.default",
                      "textures": ["Texture.default"],
                      "materials": [{"*": "Material.default"}],
                      "part_visibility": [{"head": "1"}],
                      "arrays": {
                        "textures": {"Array.skins": ["Texture.default"]},
                        "geometries": {"Array.geometry": ["Geometry.default"]},
                        "materials": {"Array.materials": ["Material.default"]}
                      }
                    }
                  }
                }
                """);
        return content;
    }

    private static final class NoopListener implements AnimationEventListener {
        private final Scope scope = Scope.create();

        @Override
        public void onTimelineEvent(final List<String> expressions) {
        }

        @Override
        public void onParticleEvent(final String effectShortName, final String locator) {
        }

        @Override
        public Scope getEntityScope() {
            return this.scope;
        }
    }
}
