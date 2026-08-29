package net.easecation.bedrockmotion.controller;

import lombok.Setter;
import net.easecation.bedrockmotion.animator.AnimationClock;
import net.easecation.bedrockmotion.animator.Animator;
import net.easecation.bedrockmotion.model.AnimationEventListener;
import net.easecation.bedrockmotion.model.AnimationParticleEvent;
import net.easecation.bedrockmotion.model.AnimationSoundEvent;
import net.easecation.bedrockmotion.model.BoneTransform;
import net.easecation.bedrockmotion.model.IBoneModel;
import net.easecation.bedrockmotion.model.IBoneTarget;
import net.easecation.bedrockmotion.mocha.LayeredScope;
import net.easecation.bedrockmotion.mocha.MoLangEngine;
import net.easecation.bedrockmotion.mocha.MoLangEvaluationContext;
import net.easecation.bedrockmotion.mocha.OverlayBinding;
import net.easecation.bedrockmotion.pack.definitions.AnimationControllerDefinitions;
import net.easecation.bedrockmotion.pack.definitions.AnimationDefinitions;
import net.easecation.bedrockmotion.util.MathUtil;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import team.unnamed.mocha.runtime.Scope;
import team.unnamed.mocha.runtime.value.MutableObjectBinding;
import team.unnamed.mocha.runtime.value.Value;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Runtime instance of a Bedrock animation controller state machine. */
public class AnimationControllerInstance {
    private static final Logger LOGGER = LoggerFactory.getLogger(AnimationControllerInstance.class);

    private final AnimationController definition;
    private final Map<String, String> entityAnimations;
    private final AnimationDefinitions animationDefinitions;
    private final AnimationControllerDefinitions controllerDefinitions;
    private final AnimationEventListener listener;
    private final AnimationClock clock;
    private final Set<String> controllerPath;

    private String currentStateName;
    private AnimationController.State currentState;
    private final List<PlaybackEntry> stateEntries = new ArrayList<>();
    private final Map<String, List<ParsedTransition>> parsedTransitionsByState = new java.util.HashMap<>();
    private final List<FadingState> fadingStates = new ArrayList<>();

    @Setter
    private float controllerBlendWeight = 1.0F;

    private final LayeredScope reusableTransitionScope = new LayeredScope(Scope.create());
    private final OverlayBinding reusableTransitionOverlay = new OverlayBinding(null);
    private float lastIncomingFactor = 1.0F;
    private int debugTickCounter;
    private long stateEnteredMS;

    public AnimationControllerInstance(
            AnimationController definition,
            Map<String, String> entityAnimations,
            AnimationDefinitions animationDefinitions,
            AnimationControllerDefinitions controllerDefinitions,
            AnimationEventListener listener) {
        this(definition, entityAnimations, animationDefinitions, controllerDefinitions,
                listener, AnimationClock.SYSTEM);
    }

    public AnimationControllerInstance(
            AnimationController definition,
            Map<String, String> entityAnimations,
            AnimationDefinitions animationDefinitions,
            AnimationControllerDefinitions controllerDefinitions,
            AnimationEventListener listener,
            AnimationClock clock) {
        this(definition, entityAnimations, animationDefinitions, controllerDefinitions,
                listener, clock, new LinkedHashSet<>());
    }

    private AnimationControllerInstance(
            AnimationController definition,
            Map<String, String> entityAnimations,
            AnimationDefinitions animationDefinitions,
            AnimationControllerDefinitions controllerDefinitions,
            AnimationEventListener listener,
            AnimationClock clock,
            Set<String> parentPath) {
        this.definition = definition;
        this.entityAnimations = Map.copyOf(entityAnimations);
        this.animationDefinitions = animationDefinitions;
        this.controllerDefinitions = controllerDefinitions;
        this.listener = listener;
        this.clock = clock;

        final LinkedHashSet<String> path = new LinkedHashSet<>(parentPath);
        if (!path.add(definition.getIdentifier())) {
            throw new IllegalArgumentException("Animation controller cycle: "
                    + String.join(" -> ", path) + " -> " + definition.getIdentifier());
        }
        this.controllerPath = Collections.unmodifiableSet(path);

        preParseAllTransitions();
        enterState(definition.getInitialState(), listener.getEntityScope(), MoLangEvaluationContext.EMPTY);
    }

    public String currentStateName() {
        return currentStateName;
    }

    public float controllerBlendWeight() {
        return controllerBlendWeight;
    }

    public boolean isDonePlaying() {
        return currentState == null || allStateEntriesFinished();
    }

    /** Immutable read-only state used by client diagnostics; it does not advance or sample playback. */
    public ControllerDebugSnapshot debugSnapshot() {
        final List<PlaybackDebugSnapshot> entries = stateEntries.stream()
                .map(entry -> new PlaybackDebugSnapshot(
                        entry.identifier,
                        entry.baseWeight,
                        entry.playback.isDonePlaying(),
                        entry.playback instanceof ControllerPlayback nested
                                ? nested.controller().debugSnapshot() : null))
                .toList();
        return new ControllerDebugSnapshot(
                definition.getIdentifier(),
                currentStateName,
                controllerBlendWeight,
                lastIncomingFactor,
                (clock.timeMillis() - stateEnteredMS) / 1000.0F,
                entries,
                fadingStates.size());
    }

    private void preParseAllTransitions() {
        for (Map.Entry<String, AnimationController.State> entry : definition.getStates().entrySet()) {
            final List<ParsedTransition> parsed = new ArrayList<>();
            for (AnimationController.Transition transition : entry.getValue().getTransitions()) {
                try {
                    parsed.add(new ParsedTransition(
                            transition.targetState(), MoLangEngine.compile(transition.condition())));
                } catch (IOException e) {
                    throw new IllegalArgumentException(
                            "Invalid transition condition in controller '" + definition.getIdentifier()
                                    + "', state '" + entry.getKey() + "': " + transition.condition(), e);
                }
            }
            parsedTransitionsByState.put(entry.getKey(), parsed);
        }
    }

    public void setBaseScope(Scope frameScope) {
        stateEntries.forEach(entry -> entry.playback.setBaseScope(frameScope));
        fadingStates.forEach(state -> state.entries.forEach(
                entry -> entry.playback.setBaseScope(frameScope)));
    }

    public void setEvaluationContext(MoLangEvaluationContext context) {
        stateEntries.forEach(entry -> entry.playback.setEvaluationContext(context));
        fadingStates.forEach(state -> state.entries.forEach(
                entry -> entry.playback.setEvaluationContext(context)));
    }

    public void tick(Scope frameScope) {
        tick(frameScope, MoLangEvaluationContext.EMPTY);
    }

    public void tick(Scope frameScope, MoLangEvaluationContext context) {
        if (controllerBlendWeight <= 0.0F || currentState == null) {
            return;
        }

        final Scope transitionScope = buildTransitionScope(frameScope);
        if (debugTickCounter++ % 60 == 0 && LOGGER.isDebugEnabled()) {
            LOGGER.debug("[AnimController] {} | state='{}' | entries={}",
                    definition.getIdentifier(), currentStateName, stateEntries.size());
        }

        final List<ParsedTransition> transitions = parsedTransitionsByState.get(currentStateName);
        if (transitions != null) {
            for (ParsedTransition transition : transitions) {
                try {
                    if (MoLangEngine.eval(transitionScope, context,
                            transition.parsedCondition()).getAsBoolean()) {
                        LOGGER.debug("[AnimController] {} transition: {} -> {}",
                                definition.getIdentifier(), currentStateName, transition.targetState());
                        enterState(transition.targetState(), transitionScope, context);
                        break;
                    }
                } catch (Throwable e) {
                    LOGGER.warn("[AnimController] {} transition eval error in state '{}' -> '{}': {}",
                            definition.getIdentifier(), currentStateName,
                            transition.targetState(), e.getMessage());
                }
            }
        }

        final float totalFadingWeight = tickFadingStates(frameScope, context);
        final float incomingFactor = Math.max(0.0F, 1.0F - totalFadingWeight);
        this.lastIncomingFactor = incomingFactor;

        for (PlaybackEntry entry : stateEntries) {
            entry.baseWeight = evalBlendWeight(entry.blendWeight, frameScope, context);
            entry.playback.setBlendWeight(
                    entry.baseWeight * incomingFactor * controllerBlendWeight);
            entry.playback.advance(frameScope, context);
        }
    }

    private Scope buildTransitionScope(Scope frameScope) {
        boolean anyFinished = stateEntries.isEmpty();
        boolean allFinished = true;
        for (PlaybackEntry entry : stateEntries) {
            if (entry.playback.isDonePlaying()) {
                anyFinished = true;
            } else {
                allFinished = false;
            }
        }

        reusableTransitionScope.reset(frameScope);
        reusableTransitionOverlay.reset((MutableObjectBinding) frameScope.get("query"));
        reusableTransitionOverlay.set("any_animation_finished", Value.of(anyFinished ? 1.0 : 0.0));
        reusableTransitionOverlay.set("all_animations_finished", Value.of(allFinished ? 1.0 : 0.0));
        reusableTransitionOverlay.set("anim_time",
                Value.of((clock.timeMillis() - stateEnteredMS) / 1000.0F));
        reusableTransitionScope.set("query", reusableTransitionOverlay);
        reusableTransitionScope.set("q", reusableTransitionOverlay);
        return reusableTransitionScope;
    }

    public void animate(IBoneModel model) {
        animate(model, true);
    }

    public void animate(IBoneModel model, boolean fireEvents) {
        if (controllerBlendWeight <= 0.0F) {
            return;
        }

        FadingState shortestPathState = null;
        for (int i = fadingStates.size() - 1; i >= 0; i--) {
            if (fadingStates.get(i).blendViaShortestPath) {
                shortestPathState = fadingStates.get(i);
                break;
            }
        }

        for (FadingState state : fadingStates) {
            if (state != shortestPathState) {
                applyEntries(state.entries, model, fireEvents);
            }
        }

        if (shortestPathState != null) {
            animateWithShortestPath(model, shortestPathState, fireEvents);
        } else {
            applyEntries(stateEntries, model, fireEvents);
        }
    }

    private static void applyEntries(List<PlaybackEntry> entries, IBoneModel model,
                                     boolean fireEvents) {
        for (PlaybackEntry entry : entries) {
            try {
                entry.playback.animate(model, fireEvents);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void animateWithShortestPath(IBoneModel model, FadingState outgoing,
                                         boolean fireEvents) {
        final Iterable<IBoneTarget> allBones = model.getAllBones();
        final Map<IBoneTarget, BoneTransform> saved = new IdentityHashMap<>();
        for (IBoneTarget bone : allBones) {
            saved.put(bone, BoneTransform.capture(bone));
        }

        setEntryWeights(outgoing.entries, 1.0F);
        applyEntries(outgoing.entries, model, fireEvents);

        final Map<IBoneTarget, BoneTransform> afterOutgoing = new IdentityHashMap<>();
        for (IBoneTarget bone : allBones) {
            afterOutgoing.put(bone, BoneTransform.capture(bone));
            saved.get(bone).restore(bone);
        }

        setEntryWeights(outgoing.entries,
                outgoing.getCurrentWeight() * controllerBlendWeight);
        setEntryWeights(stateEntries, 1.0F);
        applyEntries(stateEntries, model, fireEvents);

        final float incomingFactor = lastIncomingFactor;
        for (IBoneTarget bone : allBones) {
            final BoneTransform initial = saved.get(bone);
            final BoneTransform outgoingTransform = afterOutgoing.get(bone);
            if (initial == null || outgoingTransform == null) {
                continue;
            }

            final Vector3f rotation = bone.getRotation();
            final Vector3f offset = bone.getOffset();
            final float outgoingRx = outgoingTransform.rx() - initial.rx();
            final float outgoingRy = outgoingTransform.ry() - initial.ry();
            final float outgoingRz = outgoingTransform.rz() - initial.rz();
            final float outgoingOx = outgoingTransform.ox() - initial.ox();
            final float outgoingOy = outgoingTransform.oy() - initial.oy();
            final float outgoingOz = outgoingTransform.oz() - initial.oz();
            final float outgoingSx = outgoingTransform.sx() - initial.sx();
            final float outgoingSy = outgoingTransform.sy() - initial.sy();
            final float outgoingSz = outgoingTransform.sz() - initial.sz();

            final float incomingRx = rotation.x - initial.rx();
            final float incomingRy = rotation.y - initial.ry();
            final float incomingRz = rotation.z - initial.rz();
            final float incomingOx = offset.x - initial.ox();
            final float incomingOy = offset.y - initial.oy();
            final float incomingOz = offset.z - initial.oz();
            final float incomingSx = bone.getScaleX() - initial.sx();
            final float incomingSy = bone.getScaleY() - initial.sy();
            final float incomingSz = bone.getScaleZ() - initial.sz();

            final float blendedRx = outgoingRx
                    + MathUtil.normalizeAngleDeg(incomingRx - outgoingRx) * incomingFactor;
            final float blendedRy = outgoingRy
                    + MathUtil.normalizeAngleDeg(incomingRy - outgoingRy) * incomingFactor;
            final float blendedRz = outgoingRz
                    + MathUtil.normalizeAngleDeg(incomingRz - outgoingRz) * incomingFactor;
            final float blendedOx = outgoingOx + (incomingOx - outgoingOx) * incomingFactor;
            final float blendedOy = outgoingOy + (incomingOy - outgoingOy) * incomingFactor;
            final float blendedOz = outgoingOz + (incomingOz - outgoingOz) * incomingFactor;
            final float blendedSx = outgoingSx + (incomingSx - outgoingSx) * incomingFactor;
            final float blendedSy = outgoingSy + (incomingSy - outgoingSy) * incomingFactor;
            final float blendedSz = outgoingSz + (incomingSz - outgoingSz) * incomingFactor;

            rotation.set(initial.rx() + blendedRx * controllerBlendWeight,
                    initial.ry() + blendedRy * controllerBlendWeight,
                    initial.rz() + blendedRz * controllerBlendWeight);
            offset.set(initial.ox() + blendedOx * controllerBlendWeight,
                    initial.oy() + blendedOy * controllerBlendWeight,
                    initial.oz() + blendedOz * controllerBlendWeight);
            bone.setScale(initial.sx() + blendedSx * controllerBlendWeight,
                    initial.sy() + blendedSy * controllerBlendWeight,
                    initial.sz() + blendedSz * controllerBlendWeight);
        }

        setEntryWeights(stateEntries, lastIncomingFactor * controllerBlendWeight);
    }

    private static void setEntryWeights(List<PlaybackEntry> entries, float factor) {
        entries.forEach(entry -> entry.playback.setBlendWeight(entry.baseWeight * factor));
    }

    private void enterState(String stateName, Scope scope, MoLangEvaluationContext context) {
        final AnimationController.State nextState = definition.getStates().get(stateName);
        if (nextState == null) {
            throw new IllegalStateException("State '" + stateName + "' not found in controller '"
                    + definition.getIdentifier() + "'");
        }

        if (currentState != null) {
            executeScripts(currentState.getOnExit(), scope, context);
            final BlendTransitionCurve curve = currentState.getBlendTransitionCurve();
            if (!curve.isNone() && !stateEntries.isEmpty()) {
                fadingStates.add(new FadingState(
                        new ArrayList<>(stateEntries), curve, clock.timeMillis(),
                        currentState.isBlendViaShortestPath(), clock));
            }
        }

        stateEntries.clear();
        currentStateName = stateName;
        currentState = nextState;
        stateEnteredMS = clock.timeMillis();

        for (AnimationController.StateAnimation stateAnimation : currentState.getAnimations()) {
            final String identifier = entityAnimations.get(stateAnimation.shortName());
            if (identifier == null) {
                LOGGER.debug("[AnimController] Animation short name '{}' not found in entity animations map",
                        stateAnimation.shortName());
                continue;
            }

            final StatePlayback playback = createPlayback(identifier, context);
            if (playback == null) {
                throw new IllegalStateException("Animation or controller '" + identifier
                        + "' for short name '" + stateAnimation.shortName() + "' not found in controller '"
                        + definition.getIdentifier() + "', state '" + currentStateName + "'");
            }

            MoLangEngine.CompiledExpression blendWeight = null;
            if (stateAnimation.blendWeightExpression() != null
                    && !stateAnimation.blendWeightExpression().isBlank()) {
                try {
                    blendWeight = MoLangEngine.compile(stateAnimation.blendWeightExpression());
                } catch (IOException e) {
                    throw new IllegalArgumentException(
                            "Invalid blend weight in controller '" + definition.getIdentifier()
                                    + "', state '" + currentStateName + "', animation '"
                                    + stateAnimation.shortName() + "': "
                                    + stateAnimation.blendWeightExpression(), e);
                }
            }
            stateEntries.add(new PlaybackEntry(identifier, playback, blendWeight));
        }

        executeScripts(currentState.getOnEntry(), scope, context);
        for (AnimationController.ParticleEffect effect : currentState.getParticleEffects()) {
            listener.onParticleEvent(new AnimationParticleEvent(
                    effect.effect(), effect.locator(), effect.preEffectExpression(), clock.tick()));
        }
        for (AnimationController.SoundEffect sound : currentState.getSoundEffects()) {
            listener.onSoundEvent(new AnimationSoundEvent(
                    sound.effect(), sound.locator(), sound.preEffectExpression(), clock.tick()));
        }
    }

    private StatePlayback createPlayback(String identifier, MoLangEvaluationContext context) {
        final AnimationController childDefinition =
                controllerDefinitions.getControllers().get(identifier);
        if (childDefinition != null) {
            final AnimationControllerInstance child = new AnimationControllerInstance(
                    childDefinition, entityAnimations, animationDefinitions, controllerDefinitions,
                    listener, clock, controllerPath);
            child.setEvaluationContext(context);
            return new ControllerPlayback(child);
        }

        final AnimationDefinitions.AnimationData animation =
                animationDefinitions.getAnimations().get(identifier);
        if (animation == null) {
            return null;
        }
        final Animator animator = new Animator(listener, animation, clock);
        animator.setEvaluationContext(context);
        return new AnimatorPlayback(animator);
    }

    private void executeScripts(List<String> scripts, Scope scope, MoLangEvaluationContext context) {
        for (String expression : scripts) {
            try {
                MoLangEngine.eval(scope, context, expression);
            } catch (Throwable e) {
                throw new IllegalStateException("Failed to execute controller script in '"
                        + definition.getIdentifier() + "', state '" + currentStateName + "': " + expression, e);
            }
        }
    }

    private float evalBlendWeight(MoLangEngine.CompiledExpression expression,
                                  Scope scope, MoLangEvaluationContext context) {
        if (expression == null) {
            return 1.0F;
        }
        try {
            return (float) MoLangEngine.eval(scope, context, expression).getAsNumber();
        } catch (Throwable e) {
            throw new IllegalStateException("Failed to evaluate blend weight in controller '"
                    + definition.getIdentifier() + "', state '" + currentStateName + "'", e);
        }
    }

    private float tickFadingStates(Scope scope, MoLangEvaluationContext context) {
        float total = 0.0F;
        final Iterator<FadingState> iterator = fadingStates.iterator();
        while (iterator.hasNext()) {
            final FadingState state = iterator.next();
            if (state.isFinished()) {
                iterator.remove();
                continue;
            }
            final float fadeWeight = state.getCurrentWeight();
            total += fadeWeight;
            for (PlaybackEntry entry : state.entries) {
                entry.baseWeight = evalBlendWeight(entry.blendWeight, scope, context);
                entry.playback.setBlendWeight(
                        entry.baseWeight * fadeWeight * controllerBlendWeight);
                entry.playback.advance(scope, context);
            }
        }
        return total;
    }

    private boolean allStateEntriesFinished() {
        if (stateEntries.isEmpty()) {
            return true;
        }
        return stateEntries.stream().allMatch(entry -> entry.playback.isDonePlaying());
    }

    private record ParsedTransition(
            String targetState, MoLangEngine.CompiledExpression parsedCondition) {
    }

    private static final class PlaybackEntry {
        private final String identifier;
        private final StatePlayback playback;
        private final MoLangEngine.CompiledExpression blendWeight;
        private float baseWeight = 1.0F;

        private PlaybackEntry(String identifier, StatePlayback playback,
                              MoLangEngine.CompiledExpression blendWeight) {
            this.identifier = identifier;
            this.playback = playback;
            this.blendWeight = blendWeight;
        }
    }

    private interface StatePlayback {
        void setBaseScope(Scope scope);

        void setEvaluationContext(MoLangEvaluationContext context);

        void setBlendWeight(float weight);

        void advance(Scope scope, MoLangEvaluationContext context);

        void animate(IBoneModel model, boolean fireEvents) throws IOException;

        boolean isDonePlaying();
    }

    public record ControllerDebugSnapshot(String identifier, String stateName,
                                          float blendWeight, float incomingFactor,
                                          float stateTimeSeconds,
                                          List<PlaybackDebugSnapshot> entries,
                                          int fadingStateCount) {
        public ControllerDebugSnapshot {
            entries = List.copyOf(entries);
        }
    }

    public record PlaybackDebugSnapshot(String identifier, float baseWeight,
                                        boolean done, ControllerDebugSnapshot childController) {
    }

    private record AnimatorPlayback(Animator animator) implements StatePlayback {
        @Override
        public void setBaseScope(Scope scope) {
            animator.setBaseScope(scope);
        }

        @Override
        public void setEvaluationContext(MoLangEvaluationContext context) {
            animator.setEvaluationContext(context);
        }

        @Override
        public void setBlendWeight(float weight) {
            animator.setBlendWeight(weight);
        }

        @Override
        public void advance(Scope scope, MoLangEvaluationContext context) {
            setBaseScope(scope);
            setEvaluationContext(context);
            animator.advance();
        }

        @Override
        public void animate(IBoneModel model, boolean fireEvents) throws IOException {
            animator.animate(model, fireEvents);
        }

        @Override
        public boolean isDonePlaying() {
            return animator.isDonePlaying();
        }
    }

    private record ControllerPlayback(AnimationControllerInstance controller)
            implements StatePlayback {
        @Override
        public void setBaseScope(Scope scope) {
            controller.setBaseScope(scope);
        }

        @Override
        public void setEvaluationContext(MoLangEvaluationContext context) {
            controller.setEvaluationContext(context);
        }

        @Override
        public void setBlendWeight(float weight) {
            controller.setControllerBlendWeight(weight);
        }

        @Override
        public void advance(Scope scope, MoLangEvaluationContext context) {
            controller.tick(scope, context);
        }

        @Override
        public void animate(IBoneModel model, boolean fireEvents) {
            controller.animate(model, fireEvents);
        }

        @Override
        public boolean isDonePlaying() {
            return controller.allStateEntriesFinished();
        }
    }

    private static final class FadingState {
        private final List<PlaybackEntry> entries;
        private final BlendTransitionCurve curve;
        private final long fadeStartMS;
        private final boolean blendViaShortestPath;
        private final AnimationClock clock;

        private FadingState(List<PlaybackEntry> entries, BlendTransitionCurve curve,
                            long fadeStartMS, boolean blendViaShortestPath,
                            AnimationClock clock) {
            this.entries = entries;
            this.curve = curve;
            this.fadeStartMS = fadeStartMS;
            this.blendViaShortestPath = blendViaShortestPath;
            this.clock = clock;
        }

        private float getCurrentWeight() {
            return curve.getOldStateWeight((clock.timeMillis() - fadeStartMS) / 1000.0F);
        }

        private boolean isFinished() {
            return getCurrentWeight() <= 0.0F;
        }
    }
}
