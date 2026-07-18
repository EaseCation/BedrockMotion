package net.easecation.bedrockmotion.controller;

import net.easecation.bedrockmotion.model.AnimationEventListener;
import net.easecation.bedrockmotion.mocha.MoLangEngine;
import net.easecation.bedrockmotion.pack.definitions.AnimationDefinitions;
import org.junit.jupiter.api.Test;
import team.unnamed.mocha.parser.ast.BinaryExpression;
import team.unnamed.mocha.parser.ast.DoubleExpression;
import team.unnamed.mocha.runtime.Scope;
import team.unnamed.mocha.runtime.value.MutableObjectBinding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationControllerIsolationTest {

    @Test
    void sharedDefinitionCollectionsAreImmutableSnapshotsWithoutChangingLegacyConstructors() {
        final List<AnimationController.StateAnimation> animations = new ArrayList<>(List.of(
                new AnimationController.StateAnimation("idle", "1")));
        final List<AnimationController.Transition> transitions = new ArrayList<>(List.of(
                new AnimationController.Transition("next", "1")));
        final List<String> onEntry = new ArrayList<>(List.of("variable.entered = 1"));
        final List<String> onExit = new ArrayList<>(List.of("variable.exited = 1"));
        final List<AnimationController.ParticleEffect> particleEffects = new ArrayList<>(List.of(
                new AnimationController.ParticleEffect("spark", "head", "")));
        final AnimationController.State state = new AnimationController.State(
                animations, transitions, onEntry, onExit, particleEffects,
                BlendTransitionCurve.NONE, false);
        final Map<String, AnimationController.State> states = new LinkedHashMap<>();
        states.put("default", state);

        final AnimationController legacyController = new AnimationController("controller.test", "default", states);
        final AnimationController controller = legacyController.immutableCopy();
        final AnimationController.State sharedState = controller.getStates().get("default");

        assertSame(states, legacyController.getStates());
        assertSame(animations, state.getAnimations());

        animations.clear();
        transitions.clear();
        onEntry.clear();
        onExit.clear();
        particleEffects.clear();
        states.clear();

        assertTrue(state.getAnimations().isEmpty());
        assertEquals(List.of(new AnimationController.StateAnimation("idle", "1")), sharedState.getAnimations());
        assertEquals(List.of(new AnimationController.Transition("next", "1")), sharedState.getTransitions());
        assertEquals(List.of("variable.entered = 1"), sharedState.getOnEntry());
        assertEquals(List.of("variable.exited = 1"), sharedState.getOnExit());
        assertEquals(1, sharedState.getParticleEffects().size());
        assertTrue(controller.getStates().containsKey("default"));

        assertThrows(UnsupportedOperationException.class, () -> controller.getStates().clear());
        assertThrows(UnsupportedOperationException.class, () -> sharedState.getAnimations().clear());
        assertThrows(UnsupportedOperationException.class, () -> sharedState.getTransitions().clear());
        assertThrows(UnsupportedOperationException.class, () -> sharedState.getOnEntry().clear());
        assertThrows(UnsupportedOperationException.class, () -> sharedState.getOnExit().clear());
        assertThrows(UnsupportedOperationException.class, () -> sharedState.getParticleEffects().clear());
    }

    @Test
    void sharedControllerCopiesCallerOwnedBlendKeyframes() {
        final TreeMap<Float, Float> keyframes = new TreeMap<>();
        keyframes.put(0F, 1F);
        keyframes.put(1F, 0F);
        final AnimationController.State state = new AnimationController.State(
                List.of(), List.of(), List.of(), List.of(), List.of(),
                BlendTransitionCurve.ofKeyframes(keyframes), false);
        final AnimationController controller = new AnimationController(
                "controller.test", "default", Map.of("default", state)).immutableCopy();

        keyframes.clear();

        assertEquals(0.5F, controller.getStates().get("default")
                .getBlendTransitionCurve().getOldStateWeight(0.5F));
    }

    @Test
    void controllerInstancesKeepIndependentRuntimeStateForSharedDefinition() throws Exception {
        final AnimationController.State initial = new AnimationController.State(
                List.of(), List.of(new AnimationController.Transition("next", "1 + 0")),
                List.of(), List.of(), List.of(), BlendTransitionCurve.NONE, false);
        final AnimationController.State next = new AnimationController.State(
                List.of(), List.of(), List.of(), List.of(),
                List.of(new AnimationController.ParticleEffect("entered_next", "", "")),
                BlendTransitionCurve.NONE, false);
        final AnimationController sharedDefinition = new AnimationController(
                "controller.test", "default", Map.of("default", initial, "next", next));
        final AnimationDefinitions sharedAnimations = new AnimationDefinitions(Map.of());
        final RecordingListener firstListener = new RecordingListener();
        final RecordingListener secondListener = new RecordingListener();
        final AnimationControllerInstance first = new AnimationControllerInstance(
                sharedDefinition, Map.of(), sharedAnimations, firstListener);
        final AnimationControllerInstance second = new AnimationControllerInstance(
                sharedDefinition, Map.of(), sharedAnimations, secondListener);

        final BinaryExpression detached = (BinaryExpression) MoLangEngine.parse("1 + 0").getFirst();
        detached.left(new DoubleExpression(0D));

        first.tick(firstListener.scope);

        assertEquals(List.of("entered_next"), firstListener.particleEffects);
        assertTrue(secondListener.particleEffects.isEmpty());

        second.tick(secondListener.scope);

        assertEquals(List.of("entered_next"), secondListener.particleEffects);
    }

    private static final class RecordingListener implements AnimationEventListener {
        private final Scope scope = Scope.create();
        private final List<String> particleEffects = new ArrayList<>();

        private RecordingListener() {
            final MutableObjectBinding query = new MutableObjectBinding();
            this.scope.set("query", query);
            this.scope.set("q", query);
        }

        @Override
        public void onTimelineEvent(final List<String> expressions) {
        }

        @Override
        public void onParticleEvent(final String effectShortName, final String locator) {
            this.particleEffects.add(effectShortName);
        }

        @Override
        public Scope getEntityScope() {
            return this.scope;
        }
    }
}
