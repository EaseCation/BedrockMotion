package net.easecation.bedrockmotion.animator;

import lombok.Getter;
import lombok.Setter;
import net.easecation.bedrockmotion.animation.vanilla.AnimationHelper;
import net.easecation.bedrockmotion.model.AnimationEventListener;
import net.easecation.bedrockmotion.model.IBoneModel;
import net.easecation.bedrockmotion.model.IBoneTarget;
import net.easecation.bedrockmotion.mocha.LayeredScope;
import net.easecation.bedrockmotion.mocha.MoLangEngine;
import net.easecation.bedrockmotion.mocha.OverlayBinding;
import net.easecation.bedrockmotion.pack.definitions.AnimationDefinitions;
import org.joml.Vector3f;
import team.unnamed.mocha.runtime.Scope;
import team.unnamed.mocha.runtime.value.MutableObjectBinding;
import team.unnamed.mocha.runtime.value.Value;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class Animator {
    private final AnimationEventListener listener;
    private final AnimationDefinitions.AnimationData data;

    private long animationStartMS;

    private boolean donePlaying, started, firstPlay;

    private final Vector3f TEMP_VEC = new Vector3f();
    private final LayeredScope reusableScope = new LayeredScope(Scope.create());

    // Single-instance bone index cache (avoids O(N) linear scan per animate() call)
    private IBoneModel cachedModel;
    private Map<String, IBoneTarget> cachedBoneIndex;

    @Setter
    private Scope baseScope;

    @Getter @Setter
    private float blendWeight = 1.0f;

    public Animator(AnimationEventListener listener, AnimationDefinitions.AnimationData data) {
        this.listener = listener;
        this.data = data;

        this.animationStartMS = System.currentTimeMillis();
        this.firstPlay = true;
    }

    public void animate(IBoneModel model) throws IOException {
        if (this.blendWeight <= 0) {
            return;
        }

        if (this.donePlaying) {
            if (this.data.animation().getLoop().getValue().equals(true)) {
                this.donePlaying = false;
            } else {
                return;
            }
        }

        if (this.baseScope == null) {
            return;
        }

        // baseScope already contains complete query bindings from buildFrameScope().
        // Only overlay animation-specific anim_time/life_time.
        reusableScope.reset(this.baseScope);
        final Scope scope = reusableScope;

        if (!this.started) {
            boolean skipThisTick = true;

            float seconds = (System.currentTimeMillis() - this.animationStartMS) / 1000F;
            double requiredLaunchTime = MoLangEngine.eval(scope, this.firstPlay ? this.data.animation().getStartDelay() : this.data.animation().getLoopDelay()).getAsNumber();
            if (seconds >= requiredLaunchTime) {
                skipThisTick = false;
                this.started = true;
                this.firstPlay = false;

                this.animationStartMS = System.currentTimeMillis();
            }

            if (this.started && this.data.animation().isResetBeforePlay()) {
                model.resetAllBones();
                this.TEMP_VEC.set(0);
            }

            if (skipThisTick) {
                return;
            }
        }

        float runningTime = AnimationHelper.getRunningSeconds(data.animation(), data.compiled(), System.currentTimeMillis() - this.animationStartMS);

        // Override life_time and anim_time with animation-specific values (not entity lifetime)
        // Use OverlayBinding to avoid expensive setAllFrom() copy
        final OverlayBinding animQueryBinding = new OverlayBinding(
                (MutableObjectBinding) this.baseScope.get("query"));
        animQueryBinding.set("anim_time", Value.of(runningTime));
        animQueryBinding.set("life_time", Value.of(runningTime));
        scope.set("query", animQueryBinding);
        scope.set("q", animQueryBinding);

        Map<String, IBoneTarget> boneIndex;
        if (model == cachedModel && cachedBoneIndex != null) {
            boneIndex = cachedBoneIndex;
        } else {
            boneIndex = new HashMap<>();
            for (IBoneTarget bone : model.getAllBones()) {
                String name = bone.getName();
                if (name != null) {
                    boneIndex.putIfAbsent(name.toLowerCase(Locale.ROOT), bone);
                }
            }
            cachedModel = model;
            cachedBoneIndex = boneIndex;
        }

        AnimationHelper.animate(scope, model, data.compiled(), System.currentTimeMillis() - this.animationStartMS, this.blendWeight, TEMP_VEC, boneIndex);

        float runningTimeWithoutLoop = (System.currentTimeMillis() - this.animationStartMS) / 1000F;
        this.tickTimeline(runningTimeWithoutLoop);

        if (data.compiled().lengthInSeconds() > 0 && runningTimeWithoutLoop >= data.compiled().lengthInSeconds()) {
            this.stop();
        }
    }

    private void tickTimeline(float runningTime) {
        final Map<Float, List<String>> timeline = this.data.animation().getTimeline();
        if (timeline.isEmpty()) {
            return;
        }

        Float nextTimestamp = null;
        Map.Entry<Float, List<String>> candidate = null;

        for (Map.Entry<Float, List<String>> entry : timeline.entrySet()) {
            float timestamp = entry.getKey();
            if (timestamp > runningTime) {
                nextTimestamp = timestamp;
                break;
            }
            if (!entry.getValue().isEmpty()) {
                candidate = entry;
            }
        }

        if (candidate != null
                && (nextTimestamp == null || nextTimestamp > runningTime)
                && Math.abs(candidate.getKey() - runningTime) < 0.005F) {
            this.listener.onTimelineEvent(candidate.getValue());
        }
    }

    public boolean isDonePlaying() {
        return donePlaying;
    }

    public void stop() {
        this.animationStartMS = System.currentTimeMillis();
        this.donePlaying = true;
        this.started = false;
    }
}
