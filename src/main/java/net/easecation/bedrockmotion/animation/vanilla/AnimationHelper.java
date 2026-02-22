package net.easecation.bedrockmotion.animation.vanilla;

import net.easecation.bedrockmotion.animation.Animation;
import net.easecation.bedrockmotion.model.IBoneModel;
import net.easecation.bedrockmotion.model.IBoneTarget;
import net.easecation.bedrockmotion.util.MathUtil;
import org.joml.Vector3f;
import team.unnamed.mocha.runtime.Scope;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AnimationHelper {
    public static void animate(Scope scope, IBoneModel model, VBUAnimation animation, long runningTime, float scale, Vector3f tempVec,
                               Map<String, IBoneTarget> boneIndex) {
        float g = AnimationHelper.getRunningSeconds(animation, runningTime);
        Map<String, IBoneTarget> index = boneIndex != null ? boneIndex : model.getBoneIndex();

        for (Map.Entry<String, List<AnimateTransformation>> entry : animation.boneAnimations().entrySet()) {
            IBoneTarget bone = index.get(entry.getKey().toLowerCase(Locale.ROOT));
            if (bone == null) {
                continue;
            }
            List<AnimateTransformation> list = entry.getValue();
            for (AnimateTransformation transformation : list) {
                VBUKeyFrame[] lvs = transformation.keyframes();
                int i = Math.max(0, MathUtil.binarySearch(0, lvs.length, idx -> {
                    if (lvs[idx] == null) {
                        return false;
                    }

                    return g <= lvs[idx].timestamp();
                }) - 1);
                int j = Math.min(lvs.length - 1, i + 1);
                if (lvs[i] == null || lvs[j] == null) {
                    continue;
                }

                VBUKeyFrame lv = lvs[i];
                VBUKeyFrame lv2 = lvs[j];
                float h = g - lv.timestamp();
                float k = j != i ? MathUtil.clamp(h / (lv2.timestamp() - lv.timestamp()), 0.0f, 1.0f) : 1F;

                // Select interpolation type following Blockbench logic:
                // step takes priority, then catmullrom if either side uses it
                AnimateTransformation.Interpolation interp;
                if (lv.interpolation() == AnimateTransformation.Interpolations.STEP) {
                    interp = AnimateTransformation.Interpolations.STEP;
                } else if (lv.interpolation() == AnimateTransformation.Interpolations.CUBIC
                        || lv2.interpolation() == AnimateTransformation.Interpolations.CUBIC) {
                    interp = AnimateTransformation.Interpolations.CUBIC;
                } else {
                    interp = lv2.interpolation();
                }
                interp.apply(scope, tempVec, k, lvs, i, j, scale);
                transformation.target().apply(bone, tempVec, scale);
            }
        }
    }

    private static float getRunningSeconds(VBUAnimation animation, long runningTime) {
        float f = (float)runningTime / 1000.0f;
        if (!animation.looping() || animation.lengthInSeconds() <= 0) {
            return f;
        }
        return f % animation.lengthInSeconds();
    }

    public static float getRunningSeconds(Animation animation, VBUAnimation vbu, long runningTime) {
        float f = (float)runningTime / 1000.0f;
        if (!animation.getLoop().getValue().equals(true) || vbu.lengthInSeconds() <= 0) {
            return f;
        }
        return f % vbu.lengthInSeconds();
    }
}
