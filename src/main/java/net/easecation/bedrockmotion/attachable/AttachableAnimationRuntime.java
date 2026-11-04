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
 * Minecraft-independent stateful attachable animation runtime. Frame preparation selects the
 * visible stack; lifecycle/events advance at most once per playback and integer tick. Material
 * passes only sample bones, including after a view switch between ticks.
 */
public final class AttachableAnimationRuntime {
    private final BedrockAttachableData definition;
    private final PackManager packs;
    private final AttachableProgram program;
    private final RenderControllerEvaluator.Prepared renderProgram;
    private final Map<String, String> inverseGeometry, inverseTexture;
    private final AnimationClock.Client clock;
    private final AnimationEventListener listener;
    private final LinkedHashMap<String, AnimationControllerInstance> controllers = new LinkedHashMap<>();
    private final LinkedHashMap<String, Animator> animators = new LinkedHashMap<>();
    private static final class Playback {
        final String identifier;
        final AnimationControllerInstance controller;
        final Animator animator;
        long advancedTick = Long.MIN_VALUE;
        Playback(String identifier, AnimationControllerInstance controller, Animator animator) {
            this.identifier = identifier; this.controller = controller; this.animator = animator;
        }
        String identifier() { return identifier; }
        AnimationControllerInstance controller() { return controller; }
        Animator animator() { return animator; }
    }
    private final Map<String, Playback> playback = new LinkedHashMap<>();
    private List<Playback> activePlayback = List.of();
    private boolean initialized;
    private long lastTick = Long.MIN_VALUE;
    private long lastFrame = Long.MIN_VALUE;
    private long preparedFrames;
    private RuntimeException initializationFailure;
    private Scale rootScale = Scale.ONE;
    private static final class PreparedView {
        final long tick;
        Scale scale;
        List<Playback> playback;
        List<RenderControllerEvaluator.EvaluatedRenderPass> passes;
        boolean ready, replayed;
        RuntimeException failure;
        PreparedView(long tick) { this.tick = tick; }
    }
    private static final class SampledPose {
        Runnable restore;
        Exception failure;
    }
    private static final class SampledModel {
        final Map<PreparedView, SampledPose> views = new java.util.IdentityHashMap<>();
        SampledPose resident;
    }
    private final Map<Object, PreparedView> preparedViews = new java.util.HashMap<>();
    private final Map<IBoneModel, SampledModel> sampledModels = new java.util.IdentityHashMap<>();
    private PreparedView preparedView;
    private long preparedTick = Long.MIN_VALUE;
    private long poseSamples, poseReuses, poseCopies;
    /** An explicitly owned external pose replaces this object's authored pose stack, not its materials. */
    @FunctionalInterface public interface PoseOverride { void apply(IBoneModel model); }
    private PoseOverride poseOverride;
    public void setPoseOverride(PoseOverride replacement) {
        if (poseOverride == replacement) return;
        poseOverride = replacement;
        preparedViews.clear(); sampledModels.clear(); preparedView = null; lastFrame = Long.MIN_VALUE;
        activePlayback = List.of();
    }

    public AttachableAnimationRuntime(BedrockAttachableData definition, PackManager packs,
                                      AnimationClock.Client clock, AnimationEventListener listener) {
        this(definition, AttachableProgram.compile(definition), packs, clock, listener);
    }

    public AttachableAnimationRuntime(BedrockAttachableData definition, AttachableProgram program, PackManager packs,
                                      AnimationClock.Client clock, AnimationEventListener listener) {
        this.definition = definition;
        this.program = program;
        this.packs = packs;
        this.renderProgram = RenderControllerEvaluator.prepare(definition, packs.getRenderControllerDefinitions());
        this.inverseGeometry = inverse(definition.getGeometries());
        this.inverseTexture = inverse(definition.getTextures());
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
        if (preparedTick != tick) {
            // An off-screen holder still ticks; do not retain its last frame's pose copies until it draws again.
            preparedViews.clear(); sampledModels.clear(); preparedView = null;
        }

        initialize(scope, context);

        // Display conditions may have side effects or read view-derived variables. Once a
        // displayed plan exists, ticks advance that plan instead of selecting it a second time.
        // Tick-only callers can still establish a plan before their first displayed preparation.
        if (lastFrame == Long.MIN_VALUE && poseOverride == null) selectPlayback(scope, context);
        advanceSelected(tick, scope, context);
        return true;
    }

    /** Conditions belong to the current display, not the previous 20Hz tick's camera view. */
    private void selectPlayback(Scope scope, MoLangEvaluationContext context) {
        final LinkedHashMap<String, Boolean> requested = new LinkedHashMap<>();
        for (AttachableProgram.Animate animate : program.animate()) {
            requested.put(animate.name(), MoLangEngine.eval(scope, context, animate.condition()).getAsBoolean());
        }
        final java.util.ArrayList<Playback> next = new java.util.ArrayList<>();
        for (Map.Entry<String, Boolean> entry : requested.entrySet()) {
            if (!entry.getValue()) continue;
            final String identifier = definition.getAnimations().get(entry.getKey());
            if (identifier == null) continue;
            Playback existing = playback.get(identifier);
            if (existing == null) {
                existing = createPlayback(identifier);
                if (existing == null) continue;
                playback.put(identifier, existing);
            }
            next.add(existing);
        }
        if (!next.equals(activePlayback)) activePlayback = List.copyOf(next);
    }

    private Playback createPlayback(String identifier) {
        float partial = clock.partialTick();
        clock.sample(0);
        try {
            final AnimationController controller = packs.getAnimationControllerDefinitions().getControllers().get(identifier);
            if (controller != null) {
                final AnimationControllerInstance instance = new AnimationControllerInstance(controller, definition.getAnimations(),
                        packs.getAnimationDefinitions(), packs.getAnimationControllerDefinitions(), listener, clock);
                controllers.put(identifier, instance);
                return new Playback(identifier, instance, null);
            }
            final var animation = packs.getAnimationDefinitions().getAnimations().get(identifier);
            if (animation == null) return null;
            final Animator animator = new Animator(listener, animation, clock);
            animators.put(identifier, animator);
            return new Playback(identifier, null, animator);
        } finally { clock.sample(partial); }
    }

    /** A newly selected playback may first appear between ticks. Initialize it at the current
     * integer tick without advancing an already visited playback or consuming another interval.
     * Sampling and material passes still have no lifecycle/event side effects.
     */
    private void advanceSelected(long tick, Scope scope, MoLangEvaluationContext context) {
        float partial = clock.partialTick();
        clock.sample(0);
        try {
            for (Playback entry : activePlayback) {
                if (entry.advancedTick == tick) continue;
                // Mark before callbacks: a failed side effect must not be retried by another view.
                entry.advancedTick = tick;
                if (entry.controller != null) {
                    entry.controller.setBaseScope(scope);
                    entry.controller.setEvaluationContext(context);
                    entry.controller.tick(scope, context);
                } else {
                    entry.animator.setBaseScope(scope);
                    entry.animator.setEvaluationContext(context);
                    entry.animator.advance();
                }
            }
        } finally { clock.sample(partial); }
    }

    /**
     * Prepare once per displayed frame/view, before render controllers and bone passes. Pre-animation
     * may mutate variables, so it must not also run on tick or once per material. Conditions select
     * the current view immediately; newly selected playback is initialized once at the current
     * integer tick. Off-screen ticks continue lifecycle without inventing display frames.
     */
    public void prepareFrame(long frame, Object view, long tick, float partialTick,
                             Scope scope, MoLangEvaluationContext context) {
        clock.advanceTick(tick);
        clock.sample(partialTick);
        if (lastFrame != frame || preparedTick != tick) {
            preparedViews.clear(); sampledModels.clear(); preparedView = null;
            lastFrame = frame; preparedTick = tick;
        }
        PreparedView cached = preparedViews.get(view);
        if (cached != null) {
            if (cached.failure != null) throw cached.failure;
            if (!cached.ready) throw new IllegalStateException("Recursive preparation of the same display context");
            cached.replayed |= preparedView != cached;
            preparedView = cached; rootScale = cached.scale; activePlayback = cached.playback;
            return;
        }
        PreparedView next = new PreparedView(tick);
        preparedViews.put(view, next); preparedView = next;
        try {
            initialize(scope, context);
            if (poseOverride == null) evaluateAll(program.preAnimation(), scope, context);
            rootScale = evaluateScale(scope, context);
            if (poseOverride == null) {
                selectPlayback(scope, context);
                advanceSelected(tick, scope, context);
            } else activePlayback = List.of();
            next.scale = rootScale; next.playback = activePlayback; next.ready = true;
            preparedFrames++;
        } catch (RuntimeException failure) {
            next.failure = failure;
            throw failure;
        }
    }

    private void initialize(Scope scope, MoLangEvaluationContext context) {
        if (initializationFailure != null) throw initializationFailure;
        if (initialized) return;
        initialized = true;
        try {
            evaluateAll(program.initialize(), scope, context);
        } catch (RuntimeException failure) {
            initializationFailure = failure;
            throw failure;
        }
    }

    public long preparedFrames() { return preparedFrames; }
    public long lastFrame() { return lastFrame; }

    /** Explicit diagnostics only; cache membership alone does not describe the sampled stack. */
    public List<String> activeAnimations() { return activePlayback.stream().map(Playback::identifier).toList(); }

    /** Explicit bounded diagnostics; counts do not measure render time. */
    public Map<String, Long> frameCacheStatistics() {
        return Map.of("pose_samples", poseSamples, "pose_reuses", poseReuses, "pose_copies", poseCopies,
                "views", (long)preparedViews.size(), "models", (long)sampledModels.size());
    }

    public void sample(IBoneModel model, float partialTick, Scope scope) throws IOException {
        sample(model, partialTick, scope, MoLangEvaluationContext.EMPTY);
    }

    /**
     * Sample a model once per prepared display context, then reuse its complete pose. Repeated
     * same-frame passes do not reevaluate channels, advance animation state or fire effects.
     */
    public void sample(IBoneModel model, float partialTick, Scope scope,
                       MoLangEvaluationContext context) throws IOException {
        sample(model, partialTick, scope, context, null);
    }

    /** Host skeletal inheritance is applied after reset, before this attachable's own tracks. */
    public void sample(IBoneModel model, float partialTick, Scope scope,
                       MoLangEvaluationContext context, Runnable bindHostPose) throws IOException {
        clock.sample(partialTick);
        if (preparedView == null) {
            samplePose(model, scope, context, bindHostPose);
            return;
        }
        SampledModel state = sampledModels.computeIfAbsent(model, ignored -> new SampledModel());
        SampledPose cached = state.views.get(preparedView);
        if (cached != null && cached.failure != null) {
            if (cached.failure instanceof IOException io) throw io;
            throw (RuntimeException)cached.failure;
        }
        if (cached == state.resident && cached != null) { poseReuses++; return; }
        // Only copying on an actual same-model context collision keeps ordinary frames allocation-light.
        if (state.resident != null && state.resident.restore == null) {
            state.resident.restore = model.capturePose(); poseCopies++;
        }
        if (cached != null) {
            cached.restore.run(); state.resident = cached; poseReuses++; return;
        }
        if (preparedView.replayed) {
            throw new IllegalStateException("Replayed display context cannot introduce an unsampled model");
        }
        SampledPose next = new SampledPose(); state.views.put(preparedView, next); state.resident = null;
        try {
            samplePose(model, scope, context, bindHostPose);
            state.resident = next;
        } catch (IOException | RuntimeException failure) {
            next.failure = failure;
            throw failure;
        }
    }

    private void samplePose(IBoneModel model, Scope scope, MoLangEvaluationContext context,
                            Runnable bindHostPose) throws IOException {
        poseSamples++;
        model.resetAllBones();
        if (bindHostPose != null) bindHostPose.run();
        if (poseOverride != null) { poseOverride.apply(model); return; }
        // scripts.animate is one ordered stack. Grouping controllers ahead of bare animations
        // changes `this`, override and scale composition even if both groups preserve local order.
        for (Playback entry : activePlayback) {
            if (entry.controller() != null) {
                entry.controller().setBaseScope(scope);
                entry.controller().setEvaluationContext(context);
                entry.controller().animate(model, false);
            } else {
                entry.animator().setBaseScope(scope);
                entry.animator().setEvaluationContext(context);
                entry.animator().animate(model, false);
            }
        }
    }

    public List<RenderControllerEvaluator.EvaluatedRenderPass> evaluateRenderPasses(
            Scope scope, MoLangEvaluationContext context) {
        if (preparedView != null) {
            if (preparedView.failure != null) throw preparedView.failure;
            if (preparedView.passes != null) return preparedView.passes;
        }
        try {
            var passes = RenderControllerEvaluator.evaluatePasses(renderProgram, scope, context, inverseGeometry, inverseTexture);
            if (preparedView != null) preparedView.passes = List.copyOf(passes);
            return passes;
        } catch (RuntimeException failure) {
            if (preparedView != null) preparedView.failure = failure;
            throw failure;
        }
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

    private Scale evaluateScale(Scope scope, MoLangEvaluationContext context) {
        final var scale = program.scale();
        final float uniform = number(scope, context, scale.get(0));
        return new Scale(
                uniform * number(scope, context, scale.get(1)),
                uniform * number(scope, context, scale.get(2)),
                uniform * number(scope, context, scale.get(3)));
    }

    private static float number(Scope scope, MoLangEvaluationContext context, MoLangEngine.CompiledExpression expression) {
        return (float) MoLangEngine.eval(scope, context, expression).getAsNumber();
    }

    private static void evaluateAll(List<MoLangEngine.CompiledExpression> expressions, Scope scope,
                                    MoLangEvaluationContext context) {
        for (var expression : expressions) {
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
