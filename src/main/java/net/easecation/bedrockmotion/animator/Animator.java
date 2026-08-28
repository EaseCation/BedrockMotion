package net.easecation.bedrockmotion.animator;

import lombok.Getter;
import lombok.Setter;
import net.easecation.bedrockmotion.animation.Animation;
import net.easecation.bedrockmotion.animation.vanilla.AnimationHelper;
import net.easecation.bedrockmotion.model.AnimationEventListener;
import net.easecation.bedrockmotion.model.AnimationParticleEvent;
import net.easecation.bedrockmotion.model.AnimationSoundEvent;
import net.easecation.bedrockmotion.model.IBoneModel;
import net.easecation.bedrockmotion.model.IBoneTarget;
import net.easecation.bedrockmotion.mocha.LayeredScope;
import net.easecation.bedrockmotion.mocha.MoLangEngine;
import net.easecation.bedrockmotion.mocha.MoLangEvaluationContext;
import net.easecation.bedrockmotion.mocha.OverlayBinding;
import net.easecation.bedrockmotion.pack.definitions.AnimationDefinitions;
import org.joml.Vector3f;
import team.unnamed.mocha.runtime.Scope;
import team.unnamed.mocha.runtime.value.MutableObjectBinding;
import team.unnamed.mocha.runtime.value.Value;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class Animator {
    private final AnimationEventListener listener;
    private final AnimationDefinitions.AnimationData data;
    private final AnimationClock clock;
    private final MoLangEngine.CompiledExpression animationTimeUpdate;

    private long animationStartMS;
    private long animationCompletedMS;
    private long lastAdvanceTick = Long.MIN_VALUE;

    private boolean donePlaying, started, firstPlay;
    private boolean completionEventsPending;
    private float lastEventSampleTime = -Float.MIN_VALUE;
    private float customAnimationTime;
    private long customTimeFrameTick = Long.MIN_VALUE;
    private int customTimePartialTickBits = Float.floatToIntBits(Float.NaN);
    private long customTimeUpdatedMillis;

    private final Vector3f TEMP_VEC = new Vector3f();
    private final LayeredScope reusableScope = new LayeredScope(Scope.create());
    private final OverlayBinding reusableOverlay = new OverlayBinding(null);

    @Setter
    private Scope baseScope;

    /** Actor context for start/loop delay evaluation; propagated by the runtime each tick/sample. */
    @Setter
    private MoLangEvaluationContext evaluationContext = MoLangEvaluationContext.EMPTY;

    @Getter @Setter
    private float blendWeight = 1.0f;

    public Animator(AnimationEventListener listener, AnimationDefinitions.AnimationData data) {
        this(listener, data, AnimationClock.SYSTEM);
    }

    public Animator(AnimationEventListener listener, AnimationDefinitions.AnimationData data, AnimationClock clock) {
        this.listener = listener;
        this.data = data;
        this.clock = clock;

        final String timeUpdate = data.animation().getTimePassExpression();
        try {
            this.animationTimeUpdate = timeUpdate == null || timeUpdate.isBlank()
                    ? null : MoLangEngine.compile(timeUpdate);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid anim_time_update in "
                    + data.animation().getIdentifier() + ": " + timeUpdate, exception);
        }

        this.animationStartMS = clock.timeMillis();
        this.customTimeUpdatedMillis = this.animationStartMS;
        this.firstPlay = true;
    }

    public void animate(IBoneModel model) throws IOException {
        animate(model, true);
    }

    /**
     * Advances animation state (loop wraparound, start/loop delay handling, timeout stop) to the
     * current clock time without touching model bones. Tick paths call this once per tick;
     * {@code animate(model, false)} only samples bones and never mutates this state.
     */
    public void advance() {
        if (this.blendWeight <= 0 || this.baseScope == null) {
            return;
        }
        final long currentTick = this.clock.tick();
        if (currentTick == this.lastAdvanceTick) {
            return;
        }
        this.lastAdvanceTick = currentTick;

        final boolean wasDonePlaying = this.donePlaying;

        if (this.donePlaying) {
            if (isLooping()) {
                this.donePlaying = false;
                this.started = false;
                this.animationStartMS = this.animationCompletedMS;
                this.completionEventsPending = false;
            } else {
                if (!holdsOnLastFrame()) {
                    this.started = false;
                    this.completionEventsPending = false;
                }
                return;
            }
        }

        if (!this.started) {
            final float seconds = (clock.timeMillis() - this.animationStartMS) / 1000F;
            final double requiredLaunchTime;
            try {
                requiredLaunchTime = MoLangEngine.eval(this.baseScope, this.evaluationContext,
                        this.firstPlay ? this.data.animation().getStartDelay()
                                : this.data.animation().getLoopDelay()).getAsNumber();
            } catch (IOException e) {
                throw new RuntimeException("Failed to evaluate animation start/loop delay", e);
            }
            if (seconds >= requiredLaunchTime) {
                this.started = true;
                this.firstPlay = false;
                this.animationStartMS = clock.timeMillis();
                this.lastEventSampleTime = -Float.MIN_VALUE;
            }
        }

        final boolean animationFinished;
        if (!this.started || data.compiled().lengthInSeconds() <= 0) {
            animationFinished = false;
        } else if (this.animationTimeUpdate != null) {
            animationFinished = !isLooping()
                    && updateCustomAnimationTime() >= data.compiled().lengthInSeconds();
        } else {
            animationFinished = (clock.timeMillis() - this.animationStartMS) / 1000F
                    >= data.compiled().lengthInSeconds();
        }
        if (animationFinished) {
            // Every mode exposes the terminal pose for this authoritative tick. On the next
            // advance, looping animations restart, plain loop=false releases the pose, and
            // hold_on_last_frame keeps it. donePlaying is immediately visible to controllers.
            this.finishAtEnd();
        }

        dispatchEvents(!wasDonePlaying && this.donePlaying);
    }

    /**
     * With {@code fireEvents=true}: advances state (see {@link #advance()}), samples bones and fires
     * timeline/particle events - the legacy combined tick+render entry point, behavior unchanged.
     * With {@code fireEvents=false}: pure bone sampling; never reads or writes lifecycle state beyond
     * what {@link #advance()} already produced, so repeated same-frame passes stay consistent.
     */
    public void animate(IBoneModel model, boolean fireEvents) throws IOException {
        if (fireEvents) {
            final boolean wasStarted = this.started;
            this.advance();
            if (!wasStarted && this.started && this.data.animation().isResetBeforePlay()) {
                model.resetAllBones();
                this.TEMP_VEC.set(0);
            }
        }
        sample(model);
    }

    private void sample(IBoneModel model) throws IOException {
        if (this.blendWeight <= 0 || !this.started || this.baseScope == null) {
            return;
        }

        final long elapsedMillis = sampleTimeMillis();

        // baseScope already contains complete query bindings from buildFrameScope().
        // Only overlay animation-specific anim_time/life_time.
        reusableScope.reset(this.baseScope);
        final Scope scope = reusableScope;

        float runningTime = AnimationHelper.getRunningSeconds(data.animation(), data.compiled(), elapsedMillis);

        // Override life_time and anim_time with animation-specific values (not entity lifetime)
        // Reuse OverlayBinding instance to avoid per-frame allocation
        reusableOverlay.reset((MutableObjectBinding) this.baseScope.get("query"));
        reusableOverlay.set("anim_time", Value.of(runningTime));
        reusableOverlay.set("life_time", Value.of(runningTime));
        scope.set("query", reusableOverlay);
        scope.set("q", reusableOverlay);

        // Use IBoneModel's own lazily-built bone index (cached permanently by McBoneModel)
        Map<String, IBoneTarget> boneIndex = model.getBoneIndex();

        AnimationHelper.animate(scope, this.evaluationContext, model, data.compiled(), elapsedMillis,
                this.blendWeight, TEMP_VEC, boneIndex);
    }

    private void dispatchEvents(boolean completedNow) {
        if (!this.started || (this.donePlaying && !completedNow && !this.completionEventsPending)) {
            return;
        }
        final long elapsedMillis = sampleTimeMillis();
        final float runningTimeWithoutLoop = elapsedMillis / 1000F;
        final float previousEventTime = runningTimeWithoutLoop < this.lastEventSampleTime
                ? -Float.MIN_VALUE : this.lastEventSampleTime;
        this.tickTimeline(previousEventTime, runningTimeWithoutLoop);
        this.tickParticleEffects(previousEventTime, runningTimeWithoutLoop);
        this.tickSoundEffects(previousEventTime, runningTimeWithoutLoop);
        this.lastEventSampleTime = runningTimeWithoutLoop;
        if (this.donePlaying) {
            this.completionEventsPending = false;
        }
    }

    private void tickTimeline(float previousTime, float runningTime) {
        final Map<Float, List<String>> timeline = this.data.animation().getTimeline();
        if (timeline.isEmpty()) {
            return;
        }

        for (Map.Entry<Float, List<String>> entry : timeline.entrySet()) {
            final float timestamp = entry.getKey();
            if (timestamp > runningTime) {
                break;
            }
            if (timestamp > previousTime && !entry.getValue().isEmpty()) {
                this.listener.onTimelineEvent(entry.getValue());
            }
        }
    }

    private void tickParticleEffects(float previousTime, float runningTime) {
        final Map<Float, List<Animation.ParticleKeyframe>> effects = this.data.animation().getParticleEffects();
        if (effects.isEmpty()) {
            return;
        }

        for (Map.Entry<Float, List<Animation.ParticleKeyframe>> entry : effects.entrySet()) {
            final float timestamp = entry.getKey();
            if (timestamp > runningTime) {
                break;
            }
            if (timestamp > previousTime) {
                for (Animation.ParticleKeyframe keyframe : entry.getValue()) {
                    this.listener.onParticleEvent(new AnimationParticleEvent(
                            keyframe.effect(), keyframe.locator(), keyframe.preEffectExpression(), this.clock.tick()));
                }
            }
        }
    }

    private void tickSoundEffects(float previousTime, float runningTime) {
        final Map<Float, List<Animation.SoundKeyframe>> effects = this.data.animation().getSoundEffects();
        for (Map.Entry<Float, List<Animation.SoundKeyframe>> entry : effects.entrySet()) {
            final float timestamp = entry.getKey();
            if (timestamp > runningTime) {
                break;
            }
            if (timestamp > previousTime) {
                for (Animation.SoundKeyframe keyframe : entry.getValue()) {
                    this.listener.onSoundEvent(new AnimationSoundEvent(
                            keyframe.effect(), keyframe.locator(), keyframe.preEffectExpression(), this.clock.tick()));
                }
            }
        }
    }

    public boolean isDonePlaying() {
        return donePlaying;
    }

    private boolean isLooping() {
        return this.data.animation().getLoop().getValue().equals(true);
    }

    private boolean holdsOnLastFrame() {
        return "hold_on_last_frame".equalsIgnoreCase(
                String.valueOf(this.data.animation().getLoop().getValue()));
    }

    public void stop() {
        this.animationStartMS = clock.timeMillis();
        this.animationCompletedMS = this.animationStartMS;
        this.donePlaying = true;
        this.started = false;
        this.completionEventsPending = false;
        this.lastEventSampleTime = -Float.MIN_VALUE;
    }

    private long sampleTimeMillis() {
        if (this.donePlaying && data.compiled().lengthInSeconds() > 0) {
            return Math.round(data.compiled().lengthInSeconds() * 1000.0F);
        }
        if (this.animationTimeUpdate == null) {
            return clock.timeMillis() - this.animationStartMS;
        }
        return Math.round(updateCustomAnimationTime() * 1000.0F);
    }

    private float updateCustomAnimationTime() {
        final long frameTick = clock.tick();
        final int partialTickBits = Float.floatToIntBits(clock.partialTick());
        if (frameTick == customTimeFrameTick
                && partialTickBits == customTimePartialTickBits) {
            return customAnimationTime;
        }

        final long nowMillis = clock.timeMillis();
        final float deltaTime = Math.max(0L, nowMillis - customTimeUpdatedMillis) / 1000.0F;
        reusableScope.reset(this.baseScope);
        reusableOverlay.reset((MutableObjectBinding) this.baseScope.get("query"));
        reusableOverlay.set("anim_time", Value.of(customAnimationTime));
        reusableOverlay.set("delta_time", Value.of(deltaTime));
        reusableScope.set("query", reusableOverlay);
        reusableScope.set("q", reusableOverlay);
        customAnimationTime = (float) MoLangEngine.eval(reusableScope, this.evaluationContext,
                animationTimeUpdate).getAsNumber();
        customTimeFrameTick = frameTick;
        customTimePartialTickBits = partialTickBits;
        customTimeUpdatedMillis = nowMillis;
        return customAnimationTime;
    }

    /** Marks a non-looping animation complete while retaining its terminal pose for sampling. */
    private void finishAtEnd() {
        this.animationCompletedMS = clock.timeMillis();
        this.donePlaying = true;
        this.started = true;
        this.completionEventsPending = true;
    }
}
