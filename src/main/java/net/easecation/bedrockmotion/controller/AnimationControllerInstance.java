package net.easecation.bedrockmotion.controller;

import lombok.Setter;
import net.easecation.bedrockmotion.animator.Animator;
import net.easecation.bedrockmotion.model.AnimationEventListener;
import net.easecation.bedrockmotion.model.BoneTransform;
import net.easecation.bedrockmotion.model.IBoneModel;
import net.easecation.bedrockmotion.model.IBoneTarget;
import net.easecation.bedrockmotion.mocha.LayeredScope;
import net.easecation.bedrockmotion.mocha.MoLangEngine;
import net.easecation.bedrockmotion.mocha.OverlayBinding;
import net.easecation.bedrockmotion.pack.definitions.AnimationDefinitions;
import net.easecation.bedrockmotion.util.MathUtil;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import team.unnamed.mocha.parser.ast.Expression;
import team.unnamed.mocha.runtime.Scope;
import team.unnamed.mocha.runtime.value.MutableObjectBinding;
import team.unnamed.mocha.runtime.value.Value;

import java.io.IOException;
import java.util.*;

/**
 * Runtime instance of a Bedrock Animation Controller (state machine).
 * Adapted from VBU to use IBoneModel/IBoneTarget abstractions.
 */
public class AnimationControllerInstance {
    private static final Logger LOGGER = LoggerFactory.getLogger(AnimationControllerInstance.class);

    private final AnimationController definition;
    private final Map<String, String> entityAnimations; // entity def shortName -> full anim identifier
    private final AnimationDefinitions animationDefinitions;
    private final AnimationEventListener listener;

    private String currentStateName;
    private AnimationController.State currentState;
    private final Map<String, Animator> stateAnimators = new LinkedHashMap<>();

    // Pre-parsed MoLang for all states' transitions (parsed once at construction)
    private final Map<String, List<ParsedTransition>> parsedTransitionsByState = new HashMap<>();

    // Pre-parsed blend weight expressions for current state's animators (rebuilt on state change)
    private final Map<String, List<Expression>> parsedBlendWeights = new HashMap<>();

    // States that are fading out during a blend_transition cross-fade
    private final List<FadingState> fadingStates = new ArrayList<>();

    @Setter
    private float controllerBlendWeight = 1.0f;

    private final LayeredScope reusableTransitionScope = new LayeredScope(Scope.create());
    private final OverlayBinding reusableTransitionOverlay = new OverlayBinding(null);

    // Per-tick cached base weights and incoming factor (used by shortest-path two-pass in animate())
    private final Map<String, Float> currentBaseWeights = new HashMap<>();
    private float lastIncomingFactor = 1.0f;

    private int debugTickCounter = 0;
    private long stateEnteredMS;

    public AnimationControllerInstance(
            AnimationController definition,
            Map<String, String> entityAnimations,
            AnimationDefinitions animationDefinitions,
            AnimationEventListener listener) {
        this.definition = definition;
        this.entityAnimations = entityAnimations;
        this.animationDefinitions = animationDefinitions;
        this.listener = listener;

        preParseAllTransitions();
        enterState(definition.getInitialState(), listener.getEntityScope());
    }

    private void preParseAllTransitions() {
        for (Map.Entry<String, AnimationController.State> entry : definition.getStates().entrySet()) {
            final List<ParsedTransition> parsed = new ArrayList<>();
            for (AnimationController.Transition trans : entry.getValue().getTransitions()) {
                try {
                    parsed.add(new ParsedTransition(trans.targetState(), MoLangEngine.parse(trans.condition())));
                } catch (IOException e) {
                    LOGGER.warn("[AnimController] Failed to parse transition condition '{}' in state '{}'",
                            trans.condition(), entry.getKey(), e);
                }
            }
            parsedTransitionsByState.put(entry.getKey(), parsed);
        }
    }

    public void setBaseScope(Scope frameScope) {
        stateAnimators.values().forEach(a -> a.setBaseScope(frameScope));
        for (FadingState fs : fadingStates) {
            fs.animators.values().forEach(a -> a.setBaseScope(frameScope));
        }
    }

    public void tick(Scope frameScope) {
        if (controllerBlendWeight <= 0 || currentState == null) {
            return;
        }

        final Scope transitionScope = buildTransitionScope(frameScope);

        if (debugTickCounter++ % 60 == 0) {
            try {
                final Value variantVal = ((MutableObjectBinding) frameScope.get("query")).get("variant");
                LOGGER.debug("[AnimController] {} | state='{}' | variant={} | animators={} | donePlaying={}",
                        definition.getIdentifier(), currentStateName,
                        variantVal != null ? variantVal.getAsNumber() : "null",
                        stateAnimators.size(),
                        stateAnimators.values().stream().map(Animator::isDonePlaying).toList());
            } catch (Throwable e) {
                LOGGER.debug("[AnimController] {} | state='{}' | query error: {}",
                        definition.getIdentifier(), currentStateName, e.getMessage());
            }
        }

        final List<ParsedTransition> transitions = parsedTransitionsByState.get(currentStateName);
        if (transitions != null) {
            for (ParsedTransition trans : transitions) {
                try {
                    final Value result = MoLangEngine.eval(transitionScope, trans.parsedCondition());
                    if (result.getAsBoolean()) {
                        LOGGER.debug("[AnimController] {} transition: {} -> {}",
                                definition.getIdentifier(), currentStateName, trans.targetState());
                        enterState(trans.targetState(), transitionScope);
                        break;
                    }
                } catch (Throwable e) {
                    LOGGER.warn("[AnimController] {} transition eval error in state '{}' -> '{}': {}",
                            definition.getIdentifier(), currentStateName, trans.targetState(), e.getMessage());
                }
            }
        }

        final float totalFadingWeight = tickFadingStates(frameScope);
        final float incomingFactor = Math.max(0, 1.0f - totalFadingWeight);

        this.lastIncomingFactor = incomingFactor;
        currentBaseWeights.clear();
        stateAnimators.forEach((animId, animator) -> {
            float base = evalBlendWeight(parsedBlendWeights, animId, frameScope);
            currentBaseWeights.put(animId, base);
            animator.setBlendWeight(base * incomingFactor * controllerBlendWeight);
        });
    }

    private Scope buildTransitionScope(Scope frameScope) {
        boolean anyFinished = false;
        boolean allFinished = true;

        if (stateAnimators.isEmpty()) {
            anyFinished = true;
        } else {
            for (Animator animator : stateAnimators.values()) {
                if (animator.isDonePlaying()) {
                    anyFinished = true;
                } else {
                    allFinished = false;
                }
            }
        }

        reusableTransitionScope.reset(frameScope);
        final LayeredScope scope = reusableTransitionScope;
        // Reuse OverlayBinding instance to avoid per-frame allocation
        reusableTransitionOverlay.reset((MutableObjectBinding) frameScope.get("query"));
        reusableTransitionOverlay.set("any_animation_finished", Value.of(anyFinished ? 1.0 : 0.0));
        reusableTransitionOverlay.set("all_animations_finished", Value.of(allFinished ? 1.0 : 0.0));

        final float stateTime = (System.currentTimeMillis() - stateEnteredMS) / 1000f;
        reusableTransitionOverlay.set("anim_time", Value.of(stateTime));

        scope.set("query", reusableTransitionOverlay);
        scope.set("q", reusableTransitionOverlay);
        return scope;
    }

    public void animate(IBoneModel model) {
        if (controllerBlendWeight <= 0) {
            return;
        }

        FadingState shortestPathFs = null;
        for (int i = fadingStates.size() - 1; i >= 0; i--) {
            if (fadingStates.get(i).blendViaShortestPath) {
                shortestPathFs = fadingStates.get(i);
                break;
            }
        }

        for (FadingState fs : fadingStates) {
            if (fs == shortestPathFs) continue;
            applyAnimators(fs.animators.values(), model);
        }

        if (shortestPathFs != null) {
            animateWithShortestPath(model, shortestPathFs);
        } else {
            applyAnimators(stateAnimators.values(), model);
        }
    }

    private void applyAnimators(Collection<Animator> animators, IBoneModel model) {
        for (Animator animator : animators) {
            try {
                animator.animate(model);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Two-pass blending with shortest rotation path for a fading state cross-fade.
     */
    private void animateWithShortestPath(IBoneModel model, FadingState outgoing) {
        final Iterable<IBoneTarget> allBones = model.getAllBones();

        // Save current bone state
        final Map<IBoneTarget, BoneTransform> saved = new IdentityHashMap<>();
        for (IBoneTarget bone : allBones) {
            saved.put(bone, BoneTransform.capture(bone));
        }

        // --- Pass 1: Outgoing at base weight ---
        setAnimatorWeights(outgoing.animators, outgoing.baseWeights, 1.0f);
        applyAnimators(outgoing.animators.values(), model);

        // Capture outgoing result, then restore bones for pass 2
        final Map<IBoneTarget, BoneTransform> afterOut = new IdentityHashMap<>();
        for (IBoneTarget bone : allBones) {
            afterOut.put(bone, BoneTransform.capture(bone));
            saved.get(bone).restore(bone);
        }

        // Restore outgoing animator weights
        setAnimatorWeights(outgoing.animators, outgoing.baseWeights,
                outgoing.getCurrentWeight() * controllerBlendWeight);

        // --- Pass 2: Incoming at base weight ---
        setAnimatorWeights(stateAnimators, currentBaseWeights, 1.0f);
        applyAnimators(stateAnimators.values(), model);

        // --- Blend outgoing/incoming deltas and apply ---
        final float inFactor = lastIncomingFactor;
        for (IBoneTarget bone : allBones) {
            final BoneTransform s = saved.get(bone);
            final BoneTransform out = afterOut.get(bone);
            if (s == null || out == null) continue;

            final Vector3f rot = bone.getRotation();
            final Vector3f off = bone.getOffset();

            // Outgoing delta
            float outRx = out.rx() - s.rx(), outRy = out.ry() - s.ry(), outRz = out.rz() - s.rz();
            float outOx = out.ox() - s.ox(), outOy = out.oy() - s.oy(), outOz = out.oz() - s.oz();
            float outSx = out.sx() - s.sx(), outSy = out.sy() - s.sy(), outSz = out.sz() - s.sz();

            // Incoming delta
            float inRx = rot.x - s.rx(), inRy = rot.y - s.ry(), inRz = rot.z - s.rz();
            float inOx = off.x - s.ox(), inOy = off.y - s.oy(), inOz = off.z - s.oz();
            float inSx = bone.getScaleX() - s.sx(), inSy = bone.getScaleY() - s.sy(), inSz = bone.getScaleZ() - s.sz();

            // Rotation: shortest path lerp
            float bRx = outRx + MathUtil.normalizeAngleDeg(inRx - outRx) * inFactor;
            float bRy = outRy + MathUtil.normalizeAngleDeg(inRy - outRy) * inFactor;
            float bRz = outRz + MathUtil.normalizeAngleDeg(inRz - outRz) * inFactor;

            // Offset & scale: linear lerp
            float bOx = outOx + (inOx - outOx) * inFactor;
            float bOy = outOy + (inOy - outOy) * inFactor;
            float bOz = outOz + (inOz - outOz) * inFactor;
            float bSx = outSx + (inSx - outSx) * inFactor;
            float bSy = outSy + (inSy - outSy) * inFactor;
            float bSz = outSz + (inSz - outSz) * inFactor;

            // Apply: saved + blended_delta x controllerBlendWeight
            rot.set(s.rx() + bRx * controllerBlendWeight,
                    s.ry() + bRy * controllerBlendWeight,
                    s.rz() + bRz * controllerBlendWeight);
            off.set(s.ox() + bOx * controllerBlendWeight,
                    s.oy() + bOy * controllerBlendWeight,
                    s.oz() + bOz * controllerBlendWeight);
            bone.setScale(s.sx() + bSx * controllerBlendWeight,
                    s.sy() + bSy * controllerBlendWeight,
                    s.sz() + bSz * controllerBlendWeight);
        }

        // Restore incoming animator weights
        setAnimatorWeights(stateAnimators, currentBaseWeights,
                lastIncomingFactor * controllerBlendWeight);
    }

    private void setAnimatorWeights(Map<String, Animator> animators,
                                    Map<String, Float> baseWeights, float factor) {
        animators.forEach((animId, animator) -> {
            Float base = baseWeights.get(animId);
            animator.setBlendWeight((base != null ? base : 1.0f) * factor);
        });
    }

    private void enterState(String stateName, Scope scope) {
        final AnimationController.State newState = definition.getStates().get(stateName);
        if (newState == null) {
            LOGGER.warn("[AnimController] State '{}' not found in controller '{}'",
                    stateName, definition.getIdentifier());
            return;
        }

        if (currentState != null) {
            executeScripts(currentState.getOnExit(), scope);

            final BlendTransitionCurve curve = currentState.getBlendTransitionCurve();
            if (!curve.isNone() && !stateAnimators.isEmpty()) {
                fadingStates.add(new FadingState(
                        new LinkedHashMap<>(stateAnimators),
                        new HashMap<>(parsedBlendWeights),
                        curve,
                        System.currentTimeMillis(),
                        currentState.isBlendViaShortestPath()
                ));
            }
        }

        stateAnimators.clear();
        parsedBlendWeights.clear();

        currentStateName = stateName;
        currentState = newState;
        stateEnteredMS = System.currentTimeMillis();

        for (AnimationController.StateAnimation sa : currentState.getAnimations()) {
            final String animId = entityAnimations.get(sa.shortName());
            if (animId == null) {
                LOGGER.debug("[AnimController] Animation short name '{}' not found in entity animations map",
                        sa.shortName());
                continue;
            }

            final AnimationDefinitions.AnimationData animData = animationDefinitions.getAnimations().get(animId);
            if (animData == null) {
                LOGGER.debug("[AnimController] Animation '{}' ({}) not found in AnimationDefinitions",
                        sa.shortName(), animId);
                continue;
            }

            final Animator animator = new Animator(listener, animData);
            stateAnimators.put(animData.animation().getIdentifier(), animator);

            if (sa.blendWeightExpression() != null && !sa.blendWeightExpression().isBlank()) {
                try {
                    parsedBlendWeights.put(animData.animation().getIdentifier(),
                            MoLangEngine.parse(sa.blendWeightExpression()));
                } catch (IOException e) {
                    LOGGER.warn("[AnimController] Failed to parse blend weight '{}' for animation '{}'",
                            sa.blendWeightExpression(), sa.shortName(), e);
                }
            }
        }

        executeScripts(currentState.getOnEntry(), scope);

        // Trigger particle effects defined on this state
        for (AnimationController.ParticleEffect pe : currentState.getParticleEffects()) {
            listener.onParticleEvent(pe.effect(), pe.locator());
        }
    }

    private void executeScripts(List<String> scripts, Scope scope) {
        for (String expr : scripts) {
            try {
                MoLangEngine.eval(scope, expr);
            } catch (Throwable e) {
                LOGGER.debug("[AnimController] Failed to execute script: {}", expr, e);
            }
        }
    }

    private float evalBlendWeight(Map<String, List<Expression>> blendWeightMap,
                                  String animId, Scope frameScope) {
        final List<Expression> expr = blendWeightMap.get(animId);
        if (expr == null) return 1.0f;
        try {
            return (float) MoLangEngine.eval(frameScope, expr).getAsNumber();
        } catch (Throwable e) {
            return 1.0f;
        }
    }

    private float tickFadingStates(Scope frameScope) {
        float total = 0;
        final Iterator<FadingState> it = fadingStates.iterator();
        while (it.hasNext()) {
            final FadingState fs = it.next();
            if (fs.isFinished()) {
                it.remove();
                continue;
            }
            final float fadeWeight = fs.getCurrentWeight();
            total += fadeWeight;
            fs.baseWeights.clear();
            fs.animators.forEach((animId, animator) -> {
                float base = evalBlendWeight(fs.blendWeights, animId, frameScope);
                fs.baseWeights.put(animId, base);
                animator.setBlendWeight(base * fadeWeight * controllerBlendWeight);
            });
        }
        return total;
    }

    private record ParsedTransition(String targetState, List<Expression> parsedCondition) {}

    private static final class FadingState {
        final Map<String, Animator> animators;
        final Map<String, List<Expression>> blendWeights;
        final BlendTransitionCurve curve;
        final long fadeStartMS;
        final boolean blendViaShortestPath;
        final Map<String, Float> baseWeights = new HashMap<>();

        FadingState(Map<String, Animator> animators,
                    Map<String, List<Expression>> blendWeights,
                    BlendTransitionCurve curve, long fadeStartMS,
                    boolean blendViaShortestPath) {
            this.animators = animators;
            this.blendWeights = blendWeights;
            this.curve = curve;
            this.fadeStartMS = fadeStartMS;
            this.blendViaShortestPath = blendViaShortestPath;
        }

        float getElapsed() {
            return (System.currentTimeMillis() - fadeStartMS) / 1000f;
        }

        float getCurrentWeight() {
            return curve.getOldStateWeight(getElapsed());
        }

        boolean isFinished() {
            return getCurrentWeight() <= 0;
        }
    }
}
