package net.easecation.bedrockmotion.attachable;

import net.easecation.bedrockmotion.animator.AnimationClock;
import net.easecation.bedrockmotion.animator.Animator;
import net.easecation.bedrockmotion.controller.AnimationController;
import net.easecation.bedrockmotion.controller.AnimationControllerInstance;
import net.easecation.bedrockmotion.model.AnimationEventListener;
import net.easecation.bedrockmotion.model.IBoneModel;
import net.easecation.bedrockmotion.mocha.MoLangEngine;
import net.easecation.bedrockmotion.mocha.MoLangEvaluationContext;
import net.easecation.bedrockmotion.pack.PackManager;
import net.easecation.bedrockmotion.render.RenderControllerEvaluator;
import org.cube.converter.data.bedrock.BedrockAttachableData;
import team.unnamed.mocha.runtime.Scope;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minecraft-independent stateful attachable animation runtime. Tick mutates controller state;
 * render resets and samples a consumer-owned bone tree without advancing state or firing effects.
 */
public final class AttachableAnimationRuntime {
    private final BedrockAttachableData definition;
    private final PackManager packs;
    private final AnimationClock.Client clock;
    private final AnimationEventListener listener;
    private final LinkedHashMap<String, AnimationControllerInstance> controllers = new LinkedHashMap<>();
    private final LinkedHashMap<String, Animator> animators = new LinkedHashMap<>();
    private List<String> activeControllers = List.of();
    private List<String> activeAnimations = List.of();
    private boolean initialized;
    private long lastTick = Long.MIN_VALUE;
    private Scale rootScale = Scale.ONE;

    public AttachableAnimationRuntime(BedrockAttachableData definition, PackManager packs,
                                      AnimationClock.Client clock, AnimationEventListener listener) {
        this.definition = definition;
        this.packs = packs;
        this.clock = clock;
        this.listener = listener;
    }

    /** Returns false when the same authoritative client tick was already processed. */
    public boolean tick(long tick, Scope scope, MoLangEvaluationContext context) throws IOException {
        if (tick == lastTick) {
            return false;
        }
        clock.advanceTick(tick);
        lastTick = tick;

        if (!initialized) {
            evaluateAll(definition.getScripts().initialize(), scope, context);
            initialized = true;
        }
        evaluateAll(definition.getScripts().pre_animation(), scope, context);
        rootScale = evaluateScale(scope, context);

        final LinkedHashMap<String, Boolean> requested = new LinkedHashMap<>();
        for (BedrockAttachableData.Scripts.Animate animate : definition.getScripts().animates()) {
            final boolean enabled = animate.expression().isBlank()
                    || MoLangEngine.eval(scope, context, animate.expression()).getAsBoolean();
            requested.put(animate.name(), enabled);
        }

        final java.util.ArrayList<String> nextControllers = new java.util.ArrayList<>();
        final java.util.ArrayList<String> nextAnimations = new java.util.ArrayList<>();
        for (Map.Entry<String, Boolean> entry : requested.entrySet()) {
            if (!entry.getValue()) {
                continue;
            }
            final String identifier = definition.getAnimations().get(entry.getKey());
            if (identifier == null) {
                continue;
            }
            final AnimationController controller = packs.getAnimationControllerDefinitions()
                    .getControllers().get(identifier);
            if (controller != null) {
                final AnimationControllerInstance instance = controllers.computeIfAbsent(identifier,
                        ignored -> new AnimationControllerInstance(controller, definition.getAnimations(),
                                packs.getAnimationDefinitions(), packs.getAnimationControllerDefinitions(),
                                listener, clock));
                instance.setBaseScope(scope);
                instance.setEvaluationContext(context);
                instance.tick(scope, context);
                nextControllers.add(identifier);
                continue;
            }
            final var animation = packs.getAnimationDefinitions().getAnimations().get(identifier);
            if (animation != null) {
                final Animator animator = animators.computeIfAbsent(identifier,
                        ignored -> new Animator(listener, animation, clock));
                animator.setBaseScope(scope);
                animator.setEvaluationContext(context);
                // Bare animators (no controller) are advanced here on tick; sample() never advances.
                animator.advance();
                nextAnimations.add(identifier);
            }
        }
        activeControllers = List.copyOf(nextControllers);
        activeAnimations = List.copyOf(nextAnimations);
        return true;
    }

    public void sample(IBoneModel model, float partialTick, Scope scope) throws IOException {
        sample(model, partialTick, scope, MoLangEvaluationContext.EMPTY);
    }

    /**
     * Render-path sampling: resets the bone tree and samples the current pose without advancing
     * animation state or firing effects, so repeated same-frame passes produce identical poses.
     */
    public void sample(IBoneModel model, float partialTick, Scope scope,
                       MoLangEvaluationContext context) throws IOException {
        clock.sample(partialTick);
        model.resetAllBones();
        for (String identifier : activeControllers) {
            final AnimationControllerInstance controller = controllers.get(identifier);
            controller.setBaseScope(scope);
            controller.setEvaluationContext(context);
            controller.animate(model, false);
        }
        for (String identifier : activeAnimations) {
            final Animator animator = animators.get(identifier);
            animator.setBaseScope(scope);
            animator.setEvaluationContext(context);
            animator.animate(model, false);
        }
    }

    public List<RenderControllerEvaluator.EvaluatedRenderPass> evaluateRenderPasses(
            Scope scope, MoLangEvaluationContext context) {
        return RenderControllerEvaluator.evaluatePasses(
                definition,
                scope,
                context,
                packs.getRenderControllerDefinitions(),
                inverse(definition.getGeometries()),
                inverse(definition.getTextures()));
    }

    public Scale rootScale() {
        return rootScale;
    }

    public long lastTick() {
        return lastTick;
    }

    public Map<String, AnimationControllerInstance> controllers() {
        return Map.copyOf(controllers);
    }

    public Map<String, Animator> animators() {
        return Map.copyOf(animators);
    }

    private Scale evaluateScale(Scope scope, MoLangEvaluationContext context) throws IOException {
        final BedrockAttachableData.Scripts.Scale scale = definition.getScripts().scale();
        final float uniform = number(scope, context, scale.scale());
        return new Scale(
                uniform * number(scope, context, scale.scaleX()),
                uniform * number(scope, context, scale.scaleY()),
                uniform * number(scope, context, scale.scaleZ()));
    }

    private static float number(Scope scope, MoLangEvaluationContext context, String expression) throws IOException {
        return (float) MoLangEngine.eval(scope, context, expression).getAsNumber();
    }

    private static void evaluateAll(List<String> expressions, Scope scope,
                                    MoLangEvaluationContext context) throws IOException {
        for (String expression : expressions) {
            MoLangEngine.eval(scope, context, expression);
        }
    }

    private static Map<String, String> inverse(Map<String, String> values) {
        final LinkedHashMap<String, String> inverse = new LinkedHashMap<>();
        values.forEach((name, value) -> inverse.put(value, name));
        return inverse;
    }

    public record Scale(float x, float y, float z) {
        public static final Scale ONE = new Scale(1.0F, 1.0F, 1.0F);
    }
}
