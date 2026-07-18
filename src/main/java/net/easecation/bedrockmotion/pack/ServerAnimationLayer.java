package net.easecation.bedrockmotion.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.easecation.bedrockmotion.animation.Animation;
import net.easecation.bedrockmotion.animation.vanilla.AnimateBuilder;
import net.easecation.bedrockmotion.controller.AnimationController;
import net.easecation.bedrockmotion.pack.content.Content;
import net.easecation.bedrockmotion.pack.definitions.AnimationDefinitions;
import org.cube.converter.data.bedrock.controller.BedrockRenderController;
import org.cube.converter.parser.bedrock.controller.BedrockControllerParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deeply immutable BedrockMotion definitions parsed from one pack. ViaBedrock can cache this object
 * without converting the pack to another in-memory ZIP or exposing mutable parser-owned values.
 */
public final class ServerAnimationLayer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerAnimationLayer.class);
    private static final Gson GSON = new GsonBuilder().create();

    private final Map<String, AnimationDefinitions.AnimationData> animations;
    private final Map<String, AnimationController> animationControllers;
    private final Map<String, BedrockRenderController> renderControllers;

    public ServerAnimationLayer(final Map<String, AnimationDefinitions.AnimationData> animations,
                                final Map<String, AnimationController> animationControllers,
                                final Map<String, BedrockRenderController> renderControllers) {
        final Map<String, AnimationDefinitions.AnimationData> frozenAnimations = new LinkedHashMap<>();
        animations.forEach((identifier, data) -> frozenAnimations.put(identifier, freezeAnimation(data)));
        final Map<String, AnimationController> frozenControllers = new LinkedHashMap<>();
        animationControllers.forEach((identifier, controller) ->
                frozenControllers.put(identifier, controller.immutableCopy()));
        final Map<String, BedrockRenderController> frozenRenderControllers = new LinkedHashMap<>();
        renderControllers.forEach((identifier, controller) ->
                frozenRenderControllers.put(identifier, freezeRenderController(controller)));
        this.animations = immutableMap(frozenAnimations);
        this.animationControllers = immutableMap(frozenControllers);
        this.renderControllers = immutableMap(frozenRenderControllers);
    }

    private ServerAnimationLayer(final Map<String, AnimationDefinitions.AnimationData> animations,
                                 final Map<String, AnimationController> animationControllers,
                                 final Map<String, BedrockRenderController> renderControllers,
                                 final boolean valuesAlreadyFrozen) {
        if (!valuesAlreadyFrozen) throw new IllegalArgumentException("Expected frozen definitions");
        this.animations = immutableMap(animations);
        this.animationControllers = immutableMap(animationControllers);
        this.renderControllers = immutableMap(renderControllers);
    }

    public Map<String, AnimationDefinitions.AnimationData> animations() {
        return this.animations;
    }

    public Map<String, AnimationController> animationControllers() {
        return this.animationControllers;
    }

    public Map<String, BedrockRenderController> renderControllers() {
        return this.renderControllers;
    }

    public static ServerAnimationLayer parse(final ContentView content) {
        return parse(content, null);
    }

    /** Parses animation data while reusing render controllers already parsed by ViaBedrock. */
    public static ServerAnimationLayer parse(final ContentView content,
                                             final Map<String, BedrockRenderController> parsedRenderControllers) {
        return parse(content, parsedRenderControllers, false);
    }

    /**
     * Parses animation data while preserving render-controller values that the caller has already made deeply
     * immutable.
     */
    public static ServerAnimationLayer parseWithFrozenRenderControllers(
            final ContentView content, final Map<String, BedrockRenderController> parsedRenderControllers) {
        Objects.requireNonNull(parsedRenderControllers, "parsedRenderControllers");
        return parse(content, parsedRenderControllers, true);
    }

    private static ServerAnimationLayer parse(final ContentView content,
                                              final Map<String, BedrockRenderController> parsedRenderControllers,
                                              final boolean renderControllersAlreadyFrozen) {
        Objects.requireNonNull(content, "content");
        final Map<String, AnimationDefinitions.AnimationData> animations = new LinkedHashMap<>();
        for (String path : content.getFilesDeep("animations/", ".json")) {
            try {
                for (Animation animation : Animation.parse(
                        GSON.fromJson(content.getString(path), com.google.gson.JsonObject.class))) {
                    animations.put(animation.getIdentifier(),
                            new AnimationDefinitions.AnimationData(animation, AnimateBuilder.build(animation)));
                }
            } catch (Throwable e) {
                LOGGER.warn("Failed to parse animation definition {}", path, e);
            }
        }

        final Map<String, AnimationController> controllers = new LinkedHashMap<>();
        for (String path : content.getFilesDeep("animation_controllers/", ".json")) {
            try {
                for (AnimationController controller : AnimationController.parse(
                        GSON.fromJson(content.getString(path), com.google.gson.JsonObject.class))) {
                    controllers.put(controller.getIdentifier(), controller);
                }
            } catch (Throwable e) {
                LOGGER.warn("Failed to parse animation controller definition {}", path, e);
            }
        }

        final Map<String, BedrockRenderController> renderControllers = new LinkedHashMap<>();
        if (parsedRenderControllers != null) {
            renderControllers.putAll(parsedRenderControllers);
        } else {
            for (String path : content.getFilesDeep("render_controllers/", ".json")) {
                try {
                    for (BedrockRenderController controller : BedrockControllerParser.parse(content.getString(path))) {
                        renderControllers.put(controller.identifier(), controller);
                    }
                } catch (Throwable e) {
                    LOGGER.warn("Failed to parse render controller definition {}", path, e);
                }
            }
        }
        if (!renderControllersAlreadyFrozen) {
            return new ServerAnimationLayer(animations, controllers, renderControllers);
        }

        final ServerAnimationLayer parsed = new ServerAnimationLayer(animations, controllers, Map.of());
        return new ServerAnimationLayer(
                parsed.animations, parsed.animationControllers, renderControllers, true);
    }

    public static ServerAnimationLayer fromContent(final Content content) {
        return parse(new ContentView() {
            @Override
            public List<String> getFilesDeep(final String path, final String extension) {
                return content.getFilesDeep(path, extension);
            }

            @Override
            public String getString(final String path) {
                return content.getString(path);
            }
        });
    }

    /** Later layers override earlier layers; malformed entries never create tombstones. */
    public static ServerAnimationLayer foldBottomToTop(final Collection<ServerAnimationLayer> layers) {
        final Map<String, AnimationDefinitions.AnimationData> animations = new LinkedHashMap<>();
        final Map<String, AnimationController> controllers = new LinkedHashMap<>();
        final Map<String, BedrockRenderController> renderControllers = new LinkedHashMap<>();
        for (ServerAnimationLayer layer : layers) {
            animations.putAll(layer.animations());
            controllers.putAll(layer.animationControllers());
            renderControllers.putAll(layer.renderControllers());
        }
        // Every input layer already owns frozen values; preserve identity across stack folds.
        return new ServerAnimationLayer(animations, controllers, renderControllers, true);
    }

    public long estimatedWeightBytes() {
        return 256L + (long) (this.animations.size() + this.animationControllers.size()
                + this.renderControllers.size()) * 512L;
    }

    @Override
    public boolean equals(final Object object) {
        if (this == object) return true;
        if (!(object instanceof ServerAnimationLayer that)) return false;
        return this.animations.equals(that.animations)
                && this.animationControllers.equals(that.animationControllers)
                && this.renderControllers.equals(that.renderControllers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.animations, this.animationControllers, this.renderControllers);
    }

    @Override
    public String toString() {
        return "ServerAnimationLayer[animations=" + this.animations
                + ", animationControllers=" + this.animationControllers
                + ", renderControllers=" + this.renderControllers + ']';
    }

    private static AnimationDefinitions.AnimationData freezeAnimation(
            final AnimationDefinitions.AnimationData source) {
        Objects.requireNonNull(source, "animation data");
        final Animation immutableAnimation = Objects.requireNonNull(source.animation(), "animation").immutableCopy();
        final net.easecation.bedrockmotion.animation.vanilla.VBUAnimation immutableCompiled =
                Objects.requireNonNull(source.compiled(), "compiled animation").immutableCopy();
        return immutableAnimation == source.animation() && immutableCompiled == source.compiled()
                ? source
                : new AnimationDefinitions.AnimationData(immutableAnimation, immutableCompiled);
    }

    private static BedrockRenderController freezeRenderController(final BedrockRenderController source) {
        Objects.requireNonNull(source, "render controller");
        // CubeConverter's record constructor is shallow, so rebuild every collection component here.
        return new BedrockRenderController(
                source.identifier(),
                immutableMap(source.materialsMap()),
                source.geometryExpression(),
                List.copyOf(source.textureExpressions()),
                freezeArrays(source.materials()),
                freezeArrays(source.textures()),
                freezeArrays(source.geometries()),
                immutableMap(source.partVisibility()),
                source.ignoreLighting(),
                source.lightColorMultiplier()
        );
    }

    private static List<BedrockRenderController.Array> freezeArrays(
            final List<BedrockRenderController.Array> source) {
        final List<BedrockRenderController.Array> copy = new ArrayList<>(source.size());
        for (BedrockRenderController.Array array : source) {
            copy.add(new BedrockRenderController.Array(array.name(), List.copyOf(array.values())));
        }
        return List.copyOf(copy);
    }

    private static <K, V> Map<K, V> immutableMap(final Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public interface ContentView {
        List<String> getFilesDeep(String path, String extension);

        String getString(String path);
    }
}
