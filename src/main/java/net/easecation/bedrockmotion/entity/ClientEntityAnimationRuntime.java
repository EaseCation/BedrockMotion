package net.easecation.bedrockmotion.entity;

import net.easecation.bedrockmotion.animator.AnimationClock;
import net.easecation.bedrockmotion.animator.Animator;
import net.easecation.bedrockmotion.controller.AnimationController;
import net.easecation.bedrockmotion.controller.AnimationControllerInstance;
import net.easecation.bedrockmotion.model.AnimationEventListener;
import net.easecation.bedrockmotion.model.IBoneModel;
import net.easecation.bedrockmotion.mocha.MoLangEngine;
import net.easecation.bedrockmotion.mocha.MoLangEvaluationContext;
import net.easecation.bedrockmotion.pack.PackManager;
import org.cube.converter.data.bedrock.BedrockEntityData;
import team.unnamed.mocha.runtime.Scope;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Minecraft-independent runtime for a Bedrock client entity. Tick owns state mutation and events;
 * sample only writes the current pose into a consumer-owned bone tree.
 */
public final class ClientEntityAnimationRuntime {
    private final BedrockEntityData entity;
    private final Map<String, String> animationAliases;
    private final PackManager packs;
    private final AnimationClock.Client clock;
    private final AnimationEventListener listener;
    private final List<MoLangEngine.CompiledExpression> initializeScripts;
    private final List<MoLangEngine.CompiledExpression> preAnimationScripts;
    private final List<AnimateScript> animateScripts;
    private final ScaleExpressions scaleExpressions;
    private final Map<String, AnimationControllerInstance> controllers = new LinkedHashMap<>();
    private final Map<String, Animator> animators = new LinkedHashMap<>();

    private List<ActivePlayback> activePlayback = List.of();
    private boolean initialized;
    private long lastTick = Long.MIN_VALUE;
    private long preparedFrameTick = Long.MIN_VALUE;
    private int preparedPartialTickBits = Float.floatToIntBits(Float.NaN);
    private Scale rootScale = Scale.ONE;

    public ClientEntityAnimationRuntime(BedrockEntityData entity,
                                        Map<String, String> animationOverrides,
                                        PackManager packs,
                                        AnimationClock.Client clock,
                                        AnimationEventListener listener) {
        this.entity = Objects.requireNonNull(entity, "entity");
        this.packs = Objects.requireNonNull(packs, "packs");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.listener = Objects.requireNonNull(listener, "listener");
        if (packs.getAnimationDefinitions() == null || packs.getAnimationControllerDefinitions() == null) {
            throw new IllegalArgumentException("Client entity runtime requires animation definitions");
        }

        final LinkedHashMap<String, String> aliases = new LinkedHashMap<>(entity.getAnimations());
        aliases.putAll(Objects.requireNonNull(animationOverrides, "animationOverrides"));
        this.animationAliases = Map.copyOf(aliases);
        this.initializeScripts = compileAll("scripts.initialize", entity.getScripts().initialize());
        this.preAnimationScripts = compileAll("scripts.pre_animation", entity.getScripts().pre_animation());
        this.animateScripts = compileAnimates(entity.getScripts().animates());
        this.scaleExpressions = compileScale(entity.getScripts().scale());
        validateAnimationGraph();
    }

    /** Returns false when this authoritative client tick was already processed. */
    public boolean tick(long tick, float partialTick, Scope scope, MoLangEvaluationContext context,
                        Consumer<Scope> bindHostInputs) {
        if (tick == lastTick) {
            return false;
        }
        clock.advanceTick(tick);
        clock.sample(partialTick);
        lastTick = tick;

        if (!initialized) {
            evaluateAll(initializeScripts, scope, context);
            initialized = true;
        }
        Objects.requireNonNull(bindHostInputs, "bindHostInputs").accept(scope);
        prepareFrame(scope, context);

        final List<ActivePlayback> next = new ArrayList<>();
        for (AnimateScript script : animateScripts) {
            if (script.condition != null
                    && !MoLangEngine.eval(scope, context, script.condition).getAsBoolean()) {
                continue;
            }
            final String identifier = animationAliases.get(script.shortName);
            final AnimationController controller = packs.getAnimationControllerDefinitions()
                    .getControllers().get(identifier);
            if (controller != null) {
                final AnimationControllerInstance instance = controllers.computeIfAbsent(
                        script.shortName,
                        ignored -> new AnimationControllerInstance(
                                controller, animationAliases, packs.getAnimationDefinitions(),
                                packs.getAnimationControllerDefinitions(), listener, clock));
                instance.setBaseScope(scope);
                instance.setEvaluationContext(context);
                instance.tick(scope, context);
                next.add(new ActivePlayback(script.shortName, PlaybackType.CONTROLLER));
                continue;
            }

            final Animator animator = animators.computeIfAbsent(script.shortName,
                    ignored -> new Animator(listener,
                            packs.getAnimationDefinitions().getAnimations().get(identifier), clock));
            animator.setBaseScope(scope);
            animator.setEvaluationContext(context);
            animator.advance();
            next.add(new ActivePlayback(script.shortName, PlaybackType.ANIMATION));
        }
        activePlayback = List.copyOf(next);
        return true;
    }

    /** Repeated calls with the same tick and partial tick are pure and produce the same pose. */
    public void sample(IBoneModel model, float partialTick, Scope scope,
                       MoLangEvaluationContext context) throws IOException {
        clock.sample(partialTick);
        final int partialTickBits = Float.floatToIntBits(clock.partialTick());
        if (preparedFrameTick != clock.tick() || preparedPartialTickBits != partialTickBits) {
            prepareFrame(scope, context);
        }
        model.resetAllBones();
        for (ActivePlayback playback : activePlayback) {
            if (playback.type == PlaybackType.CONTROLLER) {
                final AnimationControllerInstance controller = controllers.get(playback.shortName);
                controller.setBaseScope(scope);
                controller.setEvaluationContext(context);
                controller.animate(model, false);
            } else {
                final Animator animator = animators.get(playback.shortName);
                animator.setBaseScope(scope);
                animator.setEvaluationContext(context);
                animator.animate(model, false);
            }
        }
    }

    private void prepareFrame(Scope scope, MoLangEvaluationContext context) {
        evaluateAll(preAnimationScripts, scope, context);
        rootScale = scaleExpressions.evaluate(scope, context);
        preparedFrameTick = clock.tick();
        preparedPartialTickBits = Float.floatToIntBits(clock.partialTick());
    }

    public Map<String, String> animationAliases() {
        return animationAliases;
    }

    public Scale rootScale() {
        return rootScale;
    }

    public long lastTick() {
        return lastTick;
    }

    private List<AnimateScript> compileAnimates(List<BedrockEntityData.Scripts.Animate> animates) {
        final List<AnimateScript> compiled = new ArrayList<>(animates.size());
        for (BedrockEntityData.Scripts.Animate animate : animates) {
            final MoLangEngine.CompiledExpression condition = animate.expression().isBlank()
                    ? null
                    : compile("scripts.animate." + animate.name(), animate.expression());
            compiled.add(new AnimateScript(animate.name(), condition));
        }
        return List.copyOf(compiled);
    }

    private static List<MoLangEngine.CompiledExpression> compileAll(String source,
                                                                    List<String> expressions) {
        final List<MoLangEngine.CompiledExpression> compiled = new ArrayList<>(expressions.size());
        for (int i = 0; i < expressions.size(); i++) {
            compiled.add(compile(source + '[' + i + ']', expressions.get(i)));
        }
        return List.copyOf(compiled);
    }

    private static ScaleExpressions compileScale(BedrockEntityData.Scripts.Scale scale) {
        return new ScaleExpressions(
                compile("scripts.scale", scale.scale()),
                compile("scripts.scaleX", scale.scaleX()),
                compile("scripts.scaleY", scale.scaleY()),
                compile("scripts.scaleZ", scale.scaleZ()));
    }

    private static MoLangEngine.CompiledExpression compile(String source, String expression) {
        try {
            return MoLangEngine.compile(expression);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid MoLang in " + source + ": " + expression, e);
        }
    }

    private static void evaluateAll(List<MoLangEngine.CompiledExpression> expressions,
                                    Scope scope, MoLangEvaluationContext context) {
        for (MoLangEngine.CompiledExpression expression : expressions) {
            MoLangEngine.eval(scope, context, expression);
        }
    }

    private void validateAnimationGraph() {
        for (AnimateScript script : animateScripts) {
            final String identifier = requireAlias(script.shortName,
                    entity.getIdentifier() + " scripts.animate." + script.shortName);
            validateDefinition(identifier,
                    entity.getIdentifier() + " scripts.animate." + script.shortName,
                    new LinkedHashSet<>());
        }
    }

    private void validateDefinition(String identifier, String reference, Set<String> controllerPath) {
        if (packs.getAnimationDefinitions().getAnimations().containsKey(identifier)) {
            return;
        }
        final AnimationController controller = packs.getAnimationControllerDefinitions()
                .getControllers().get(identifier);
        if (controller == null) {
            throw new IllegalArgumentException("Missing animation/controller '" + identifier
                    + "' referenced by " + reference);
        }
        validateController(controller, reference, controllerPath);
    }

    private void validateController(AnimationController controller, String reference,
                                    Set<String> parentPath) {
        final LinkedHashSet<String> path = new LinkedHashSet<>(parentPath);
        if (!path.add(controller.getIdentifier())) {
            throw new IllegalArgumentException("Animation controller cycle from " + reference + ": "
                    + String.join(" -> ", path) + " -> " + controller.getIdentifier());
        }
        if (!controller.getStates().containsKey(controller.getInitialState())) {
            throw new IllegalArgumentException("Missing initial state '" + controller.getInitialState()
                    + "' in controller '" + controller.getIdentifier() + "'");
        }

        final Set<String> visitedStates = new LinkedHashSet<>();
        final List<String> pendingStates = new ArrayList<>();
        pendingStates.add(controller.getInitialState());
        for (int index = 0; index < pendingStates.size(); index++) {
            final String stateName = pendingStates.get(index);
            if (!visitedStates.add(stateName)) {
                continue;
            }
            final AnimationController.State state = controller.getStates().get(stateName);
            if (state == null) {
                throw new IllegalArgumentException("Missing state '" + stateName
                        + "' in controller '" + controller.getIdentifier() + "'");
            }
            compileAll(controller.getIdentifier() + '.' + stateName + ".on_entry", state.getOnEntry());
            compileAll(controller.getIdentifier() + '.' + stateName + ".on_exit", state.getOnExit());
            for (AnimationController.StateAnimation animation : state.getAnimations()) {
                if (animation.blendWeightExpression() != null
                        && !animation.blendWeightExpression().isBlank()) {
                    compile(controller.getIdentifier() + '.' + stateName + ".animations."
                            + animation.shortName(), animation.blendWeightExpression());
                }
                final String childIdentifier = animationAliases.get(animation.shortName());
                if (childIdentifier == null || childIdentifier.isBlank()) {
                    continue;
                }
                validateDefinition(childIdentifier,
                        controller.getIdentifier() + '.' + stateName + '.' + animation.shortName(), path);
            }
            for (AnimationController.Transition transition : state.getTransitions()) {
                compile(controller.getIdentifier() + '.' + stateName + ".transition."
                        + transition.targetState(), transition.condition());
                if (!controller.getStates().containsKey(transition.targetState())) {
                    throw new IllegalArgumentException("Missing transition target '"
                            + transition.targetState() + "' in controller '"
                            + controller.getIdentifier() + "'");
                }
                pendingStates.add(transition.targetState());
            }
        }
    }

    private String requireAlias(String shortName, String reference) {
        final String identifier = animationAliases.get(shortName);
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("Missing animation alias '" + shortName
                    + "' referenced by " + reference);
        }
        return identifier;
    }

    private record AnimateScript(String shortName, MoLangEngine.CompiledExpression condition) {
    }

    private record ActivePlayback(String shortName, PlaybackType type) {
    }

    private enum PlaybackType {
        CONTROLLER,
        ANIMATION
    }

    private record ScaleExpressions(MoLangEngine.CompiledExpression uniform,
                                    MoLangEngine.CompiledExpression x,
                                    MoLangEngine.CompiledExpression y,
                                    MoLangEngine.CompiledExpression z) {
        private Scale evaluate(Scope scope, MoLangEvaluationContext context) {
            final float uniformValue = (float) MoLangEngine.eval(scope, context, uniform).getAsNumber();
            return new Scale(
                    uniformValue * (float) MoLangEngine.eval(scope, context, x).getAsNumber(),
                    uniformValue * (float) MoLangEngine.eval(scope, context, y).getAsNumber(),
                    uniformValue * (float) MoLangEngine.eval(scope, context, z).getAsNumber());
        }
    }

    public record Scale(float x, float y, float z) {
        public static final Scale ONE = new Scale(1.0F, 1.0F, 1.0F);
    }
}
