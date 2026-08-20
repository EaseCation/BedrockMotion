package net.easecation.bedrockmotion.animation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.apache.commons.lang3.math.NumberUtils;
import net.easecation.bedrockmotion.animation.element.Cube;
import net.easecation.bedrockmotion.util.JsonUtil;
import net.easecation.bedrockmotion.util.mojangweirdformat.ValueOrValue;

import java.util.*;

// https://bedrock.dev/docs/stable/Schemas
// https://learn.microsoft.com/en-us/minecraft/creator/documents/animations/animationsoverview?
@RequiredArgsConstructor
@ToString
@Getter
public class Animation {
    private final String identifier;
    private ValueOrValue<?> loop;
    private String startDelay = "", loopDelay = "";
    private String timePassExpression = ""; // anim_time_update TODO: Implement this.
    private boolean resetBeforePlay; // override_previous_animation
    private float animationLength = -1;
    private List<Cube> cubes = new ArrayList<>();
    private Map<Float, List<String>> timeline = new TreeMap<>();
    private Map<Float, List<ParticleKeyframe>> particleEffects = new TreeMap<>();
    private Map<Float, List<SoundKeyframe>> soundEffects = new TreeMap<>();
    private boolean immutable;

    public record ParticleKeyframe(String effect, String locator, String preEffectExpression) {}
    public record SoundKeyframe(String effect, String locator, String preEffectExpression) {}

    public static List<Animation> parse(final JsonObject object) {
        final JsonObject animationsList = object.getAsJsonObject("animations");
        if (animationsList == null || animationsList.isEmpty()) {
            return List.of();
        }

        final List<Animation> animations = new ArrayList<>();
        for (String identifier : animationsList.keySet()) {
            if (!animationsList.get(identifier).isJsonObject()) {
                continue;
            }

            final Animation animation = new Animation(identifier);

            JsonObject animationObject = animationsList.getAsJsonObject(identifier);
            if (animationObject.has("loop")) {
                final JsonPrimitive loopElement = animationObject.getAsJsonPrimitive("loop");
                animation.setLoop(new ValueOrValue<>(loopElement.isBoolean() ? loopElement.getAsBoolean() : loopElement.getAsString()));
            } else {
                animation.setLoop(new ValueOrValue<>(false));
            }

            if (animationObject.has("start_delay")) {
                animation.setStartDelay(animationObject.get("start_delay").getAsString());
            }

            if (animationObject.has("loop_delay")) {
                animation.setLoopDelay(animationObject.get("loop_delay").getAsString());
            }

            if (animationObject.has("anim_time_update")) {
                animation.setTimePassExpression(animationObject.get("anim_time_update").getAsString());
            }

            if (animationObject.has("override_previous_animation")) {
                animation.setResetBeforePlay(animationObject.get("override_previous_animation").getAsBoolean());
            }

            if (animationObject.has("animation_length")) {
                animation.setAnimationLength(animationObject.get("animation_length").getAsFloat());
            }

            if (animationObject.has("timeline")) {
                final JsonObject timelineObject = animationObject.get("timeline").getAsJsonObject();
                for (final String string : timelineObject.keySet()) {
                    if (!NumberUtils.isCreatable(string)) {
                        continue;
                    }
                    float timestamp = Float.parseFloat(string);

                    final JsonElement element = timelineObject.get(string);
                    if (element instanceof JsonPrimitive primitive && primitive.isString()) {
                        animation.getTimeline().put(timestamp, Collections.singletonList(primitive.getAsString()));
                    } else if (element instanceof JsonArray array) {
                        animation.getTimeline().put(timestamp, JsonUtil.arrayToStringSet(array).stream().toList());
                    }
                }
            }

            if (animationObject.has("particle_effects")) {
                final JsonObject particleObj = animationObject.getAsJsonObject("particle_effects");
                if (particleObj != null) {
                    for (final String key : particleObj.keySet()) {
                        if (!NumberUtils.isCreatable(key)) {
                            continue;
                        }
                        float timestamp = Float.parseFloat(key);
                        final JsonElement el = particleObj.get(key);
                        final List<ParticleKeyframe> keyframes = new ArrayList<>();
                        if (el.isJsonObject()) {
                            keyframes.add(parseParticleKeyframe(el.getAsJsonObject()));
                        } else if (el.isJsonArray()) {
                            for (JsonElement item : el.getAsJsonArray()) {
                                if (item.isJsonObject()) {
                                    keyframes.add(parseParticleKeyframe(item.getAsJsonObject()));
                                }
                            }
                        }
                        if (!keyframes.isEmpty()) {
                            animation.getParticleEffects().put(timestamp, keyframes);
                        }
                    }
                }
            }

            if (animationObject.has("sound_effects")) {
                final JsonObject soundObj = animationObject.getAsJsonObject("sound_effects");
                if (soundObj != null) {
                    for (final String key : soundObj.keySet()) {
                        if (!NumberUtils.isCreatable(key)) {
                            continue;
                        }
                        final float timestamp = Float.parseFloat(key);
                        final JsonElement element = soundObj.get(key);
                        final List<SoundKeyframe> keyframes = new ArrayList<>();
                        if (element.isJsonObject()) {
                            keyframes.add(parseSoundKeyframe(element.getAsJsonObject()));
                        } else if (element.isJsonArray()) {
                            for (JsonElement item : element.getAsJsonArray()) {
                                if (item.isJsonObject()) {
                                    keyframes.add(parseSoundKeyframe(item.getAsJsonObject()));
                                }
                            }
                        }
                        if (!keyframes.isEmpty()) {
                            animation.getSoundEffects().put(timestamp, keyframes);
                        }
                    }
                }
            }

            if (!animationObject.has("bones")) {
                animations.add(animation);
                continue;
            }

            animation.getCubes().addAll(Cube.parse(animationObject.getAsJsonObject("bones")));
            animations.add(animation);
        }

        return animations;
    }

    private static ParticleKeyframe parseParticleKeyframe(JsonObject obj) {
        String effect = obj.has("effect") ? obj.get("effect").getAsString() : "";
        String locator = obj.has("locator") ? obj.get("locator").getAsString() : "";
        String preEffect = obj.has("pre_effect_script") ? obj.get("pre_effect_script").getAsString() : "";
        return new ParticleKeyframe(effect, locator, preEffect);
    }

    private static SoundKeyframe parseSoundKeyframe(JsonObject obj) {
        final String effect = obj.has("effect") ? obj.get("effect").getAsString() : "";
        final String locator = obj.has("locator") ? obj.get("locator").getAsString() : "";
        final String preEffect = obj.has("pre_effect_script") ? obj.get("pre_effect_script").getAsString() : "";
        return new SoundKeyframe(effect, locator, preEffect);
    }

    /** Returns a detached immutable snapshot suitable for process-wide sharing. */
    public Animation immutableCopy() {
        if (this.immutable) return this;
        final Animation copy = new Animation(this.identifier);
        copy.loop = ValueOrValue.immutableCopy(this.loop);
        copy.startDelay = this.startDelay;
        copy.loopDelay = this.loopDelay;
        copy.timePassExpression = this.timePassExpression;
        copy.resetBeforePlay = this.resetBeforePlay;
        copy.animationLength = this.animationLength;
        copy.cubes = this.cubes.stream().map(Cube::immutableCopy).toList();
        final Map<Float, List<String>> timeline = new TreeMap<>();
        this.timeline.forEach((timestamp, expressions) -> timeline.put(timestamp, List.copyOf(expressions)));
        copy.timeline = Collections.unmodifiableMap(timeline);
        final Map<Float, List<ParticleKeyframe>> particleEffects = new TreeMap<>();
        this.particleEffects.forEach((timestamp, effects) -> particleEffects.put(timestamp, List.copyOf(effects)));
        copy.particleEffects = Collections.unmodifiableMap(particleEffects);
        final Map<Float, List<SoundKeyframe>> soundEffects = new TreeMap<>();
        this.soundEffects.forEach((timestamp, effects) -> soundEffects.put(timestamp, List.copyOf(effects)));
        copy.soundEffects = Collections.unmodifiableMap(soundEffects);
        copy.immutable = true;
        return copy;
    }

    public void setLoop(final ValueOrValue<?> loop) {
        this.ensureMutable();
        this.loop = loop;
    }

    public void setStartDelay(final String startDelay) {
        this.ensureMutable();
        this.startDelay = startDelay;
    }

    public void setLoopDelay(final String loopDelay) {
        this.ensureMutable();
        this.loopDelay = loopDelay;
    }

    public void setTimePassExpression(final String timePassExpression) {
        this.ensureMutable();
        this.timePassExpression = timePassExpression;
    }

    public void setResetBeforePlay(final boolean resetBeforePlay) {
        this.ensureMutable();
        this.resetBeforePlay = resetBeforePlay;
    }

    public void setAnimationLength(final float animationLength) {
        this.ensureMutable();
        this.animationLength = animationLength;
    }

    private void ensureMutable() {
        if (this.immutable) {
            throw new UnsupportedOperationException("Shared animation definitions are immutable");
        }
    }
}
