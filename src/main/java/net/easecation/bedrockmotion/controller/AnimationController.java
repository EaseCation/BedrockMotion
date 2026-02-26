package net.easecation.bedrockmotion.controller;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.Getter;

import java.util.*;

/**
 * Parsed data model for a Bedrock Animation Controller (state machine).
 */
@Getter
public class AnimationController {
    private final String identifier;
    private final String initialState;
    private final Map<String, State> states;

    public AnimationController(String identifier, String initialState, Map<String, State> states) {
        this.identifier = identifier;
        this.initialState = initialState;
        this.states = states;
    }

    public static List<AnimationController> parse(JsonObject root) {
        final JsonObject controllers = root.getAsJsonObject("animation_controllers");
        if (controllers == null || controllers.isEmpty()) {
            return List.of();
        }

        final List<AnimationController> result = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : controllers.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }

            final String identifier = entry.getKey();
            final JsonObject controllerObj = entry.getValue().getAsJsonObject();

            final String initialState = controllerObj.has("initial_state")
                    ? controllerObj.get("initial_state").getAsString()
                    : "default";

            final Map<String, State> states = new LinkedHashMap<>();
            final JsonObject statesObj = controllerObj.getAsJsonObject("states");
            if (statesObj != null) {
                for (Map.Entry<String, JsonElement> stateEntry : statesObj.entrySet()) {
                    if (!stateEntry.getValue().isJsonObject()) {
                        continue;
                    }
                    states.put(stateEntry.getKey(), State.parse(stateEntry.getValue().getAsJsonObject()));
                }
            }

            result.add(new AnimationController(identifier, initialState, states));
        }

        return result;
    }

    @Getter
    public static class State {
        private final List<StateAnimation> animations;
        private final List<Transition> transitions;
        private final List<String> onEntry;
        private final List<String> onExit;
        private final List<ParticleEffect> particleEffects;
        private final BlendTransitionCurve blendTransitionCurve;
        private final boolean blendViaShortestPath;

        public State(List<StateAnimation> animations, List<Transition> transitions,
                     List<String> onEntry, List<String> onExit,
                     List<ParticleEffect> particleEffects,
                     BlendTransitionCurve blendTransitionCurve, boolean blendViaShortestPath) {
            this.animations = animations;
            this.transitions = transitions;
            this.onEntry = onEntry;
            this.onExit = onExit;
            this.particleEffects = particleEffects;
            this.blendTransitionCurve = blendTransitionCurve;
            this.blendViaShortestPath = blendViaShortestPath;
        }

        static State parse(JsonObject obj) {
            // Parse animations: ["anim1", {"anim2": "blend_weight_molang"}]
            final List<StateAnimation> animations = new ArrayList<>();
            final JsonArray animArray = obj.getAsJsonArray("animations");
            if (animArray != null) {
                for (JsonElement elem : animArray) {
                    if (elem.isJsonPrimitive()) {
                        animations.add(new StateAnimation(elem.getAsString(), ""));
                    } else if (elem.isJsonObject()) {
                        final JsonObject animObj = elem.getAsJsonObject();
                        for (Map.Entry<String, JsonElement> e : animObj.entrySet()) {
                            animations.add(new StateAnimation(e.getKey(), e.getValue().getAsString()));
                        }
                    }
                }
            }

            // Parse transitions: [{"target_state": "molang_condition"}]
            final List<Transition> transitions = new ArrayList<>();
            final JsonArray transArray = obj.getAsJsonArray("transitions");
            if (transArray != null) {
                for (JsonElement elem : transArray) {
                    if (elem.isJsonObject()) {
                        final JsonObject transObj = elem.getAsJsonObject();
                        for (Map.Entry<String, JsonElement> e : transObj.entrySet()) {
                            transitions.add(new Transition(e.getKey(), e.getValue().getAsString()));
                        }
                    }
                }
            }

            // Parse on_entry / on_exit
            final List<String> onEntry = parseStringArray(obj.getAsJsonArray("on_entry"));
            final List<String> onExit = parseStringArray(obj.getAsJsonArray("on_exit"));

            // Parse blend_transition (supports both float and keyframe object)
            final BlendTransitionCurve blendTransitionCurve = BlendTransitionCurve.parse(
                    obj.get("blend_transition"));

            // Parse blend_via_shortest_path
            final boolean blendViaShortestPath = obj.has("blend_via_shortest_path")
                    && obj.get("blend_via_shortest_path").getAsBoolean();

            // Parse particle_effects: [{"effect": "short_name", "locator": "bone"}]
            final List<ParticleEffect> particleEffects = new ArrayList<>();
            final JsonArray particleArray = obj.getAsJsonArray("particle_effects");
            if (particleArray != null) {
                for (JsonElement elem : particleArray) {
                    if (elem.isJsonObject()) {
                        final JsonObject peObj = elem.getAsJsonObject();
                        String effect = peObj.has("effect") ? peObj.get("effect").getAsString() : "";
                        String locator = peObj.has("locator") ? peObj.get("locator").getAsString() : "";
                        String preEffect = peObj.has("pre_effect_script") ? peObj.get("pre_effect_script").getAsString() : "";
                        particleEffects.add(new ParticleEffect(effect, locator, preEffect));
                    }
                }
            }

            return new State(animations, transitions, onEntry, onExit,
                    particleEffects, blendTransitionCurve, blendViaShortestPath);
        }

        private static List<String> parseStringArray(JsonArray array) {
            if (array == null) {
                return List.of();
            }
            final List<String> result = new ArrayList<>();
            for (JsonElement elem : array) {
                if (elem.isJsonPrimitive()) {
                    result.add(elem.getAsString());
                }
            }
            return result;
        }
    }

    public record StateAnimation(String shortName, String blendWeightExpression) {}

    public record Transition(String targetState, String condition) {}

    public record ParticleEffect(String effect, String locator, String preEffectExpression) {}
}
