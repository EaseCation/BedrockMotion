package net.easecation.bedrockmotion.render;

import net.easecation.bedrockmotion.mocha.MoLangEngine;
import net.easecation.bedrockmotion.mocha.MoLangEvaluationContext;
import net.easecation.bedrockmotion.pack.definitions.controller.RenderControllerDefinitions;
import org.cube.converter.data.bedrock.BedrockEntityData;
import org.cube.converter.data.bedrock.controller.BedrockRenderController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import team.unnamed.mocha.runtime.Scope;
import team.unnamed.mocha.runtime.value.MutableObjectBinding;
import team.unnamed.mocha.runtime.value.Value;

import java.io.IOException;
import java.util.*;

/**
 * Evaluates Bedrock render controllers to determine which geometry/texture/material
 * combinations should be rendered for an entity.
 * Pure MoLang evaluation + BedrockRenderController data query, no MC dependencies.
 */
public class RenderControllerEvaluator {
    private static final Logger LOGGER = LoggerFactory.getLogger(RenderControllerEvaluator.class);

    /**
     * Result of evaluating render controllers for an entity.
     *
     * @param key          unique key (geometryName + "_" + textureName) for change detection
     * @param controller   the BedrockRenderController that produced this result
     * @param geometryValue the resolved geometry identifier
     * @param textureValue  the resolved texture path
     */
    public record EvaluatedModel(String key, BedrockRenderController controller,
                                 String geometryValue, String textureValue) {
    }

    public enum BlendMode {
        OPAQUE, ALPHA_TEST, ALPHA_BLEND
    }

    /**
     * Complete, backend-neutral description of one render-controller pass.
     *
     * <p>Known limitation: {@code textureValues} retains every texture slot, but the current
     * consumer (VBU) only renders the first slot, and the {@code enchanted} material degrades to
     * the vanilla glint instead of a dedicated enchanted pass.
     */
    public record EvaluatedRenderPass(
            String key,
            BedrockRenderController controller,
            String geometryValue,
            String textureValue,
            List<String> textureValues,
            Map<String, String> perBoneMaterial,
            Map<String, Boolean> partVisibility,
            boolean ignoreLighting,
            boolean cull,
            BlendMode blendMode,
            boolean emissive,
            boolean glint,
            float lightColorMultiplier,
            int colorArgb) {
        public EvaluatedRenderPass {
            textureValues = List.copyOf(textureValues);
        }

        /** Source-compatible constructor for backends that only expose the primary texture slot. */
        public EvaluatedRenderPass(
                String key,
                BedrockRenderController controller,
                String geometryValue,
                String textureValue,
                Map<String, String> perBoneMaterial,
                Map<String, Boolean> partVisibility,
                boolean ignoreLighting,
                boolean cull,
                BlendMode blendMode,
                boolean emissive,
                boolean glint,
                float lightColorMultiplier,
                int colorArgb) {
            this(key, controller, geometryValue, textureValue, List.of(textureValue), perBoneMaterial,
                    partVisibility, ignoreLighting, cull, blendMode, emissive, glint,
                    lightColorMultiplier, colorArgb);
        }
    }

    /**
     * Evaluate all render controllers for an entity definition against the given scope.
     *
     * @param entityData entity definition containing controllers, geometries, textures
     * @param scope       MoLang scope with query bindings (variant, flags, etc.)
     * @param rcDefs      render controller definitions from pack manager
     * @param inverseGeometryMap  geometry value -> geometry short name
     * @param inverseTextureMap   texture value -> texture short name
     * @return list of evaluated models, or empty list if evaluation fails
     */
    public static List<EvaluatedModel> evaluate(
            BedrockEntityData entityData,
            Scope scope,
            RenderControllerDefinitions rcDefs,
            Map<String, String> inverseGeometryMap,
            Map<String, String> inverseTextureMap) {

        // EvaluatedModel predates texture-slot-aware passes and represented every texture slot as a
        // separate model. Keep that behavior for existing entity consumers while new render backends
        // consume one EvaluatedRenderPass per controller with all slots intact.
        final List<EvaluatedModel> models = new ArrayList<>();
        for (EvaluatedRenderPass pass : evaluatePasses(entityData, scope, MoLangEvaluationContext.EMPTY,
                rcDefs, inverseGeometryMap, inverseTextureMap)) {
            final String geometryName = inverseGeometryMap.get(pass.geometryValue());
            for (String textureValue : pass.textureValues()) {
                final String textureName = inverseTextureMap.get(textureValue);
                if (geometryName != null && textureName != null) {
                    models.add(new EvaluatedModel(geometryName + "_" + textureName,
                            pass.controller(), pass.geometryValue(), textureValue));
                }
            }
        }
        return List.copyOf(models);
    }

    public static List<EvaluatedRenderPass> evaluatePasses(
            BedrockEntityData entityData,
            Scope scope,
            MoLangEvaluationContext context,
            RenderControllerDefinitions rcDefs,
            Map<String, String> inverseGeometryMap,
            Map<String, String> inverseTextureMap) {

        final List<EvaluatedRenderPass> passes = new ArrayList<>();
        final Map<String, String> inverseMaterialMap = inverse(entityData.getMaterials());
        final Scope resourceScope = withResourceBindings(scope, entityData);

        for (final BedrockEntityData.RenderController entityRenderController : entityData.getControllers()) {
            final BedrockRenderController renderController = rcDefs.getRenderControllers()
                    .get(entityRenderController.identifier());
            if (renderController == null) {
                continue;
            }

            // Evaluate render controller condition
            if (!entityRenderController.condition().isBlank()) {
                try {
                    final Value conditionResult = MoLangEngine.eval(scope, context, entityRenderController.condition());
                    if (!conditionResult.getAsBoolean()) {
                        continue;
                    }
                } catch (Throwable e) {
                    LOGGER.warn("Failed to evaluate render controller condition", e);
                    continue;
                }
            }

            try {
                final Scope geometryScope = resourceScope.copy();
                geometryScope.set("array", getArrayBinding(resourceScope, context, renderController.geometries()));
                final Scope textureScope = resourceScope.copy();
                textureScope.set("array", getArrayBinding(resourceScope, context, renderController.textures()));
                final Scope materialScope = resourceScope.copy();
                materialScope.set("array", getArrayBinding(resourceScope, context, renderController.materials()));

                final String geometryValue = MoLangEngine.eval(geometryScope, context, renderController.geometryExpression()).getAsString();
                final String geometryName = inverseGeometryMap.get(geometryValue);

                final LinkedHashMap<String, String> materials = new LinkedHashMap<>();
                final List<String> materialValues = new ArrayList<>();
                for (Map.Entry<String, String> material : renderController.materialsMap().entrySet()) {
                    try {
                        final String value = MoLangEngine.eval(materialScope, context, material.getValue()).getAsString();
                        materialValues.add(value);
                        materials.put(material.getKey(), inverseMaterialMap.getOrDefault(value, value));
                    } catch (Throwable e) {
                        // Tolerate a single broken material expression: keep the raw name and continue.
                        LOGGER.warn("Failed to evaluate render controller material '{}'", material.getValue(), e);
                        materialValues.add(material.getValue());
                        materials.put(material.getKey(), material.getValue());
                    }
                }
                final LinkedHashMap<String, Boolean> visibility = new LinkedHashMap<>();
                for (Map.Entry<String, String> part : renderController.partVisibility().entrySet()) {
                    try {
                        visibility.put(part.getKey(), MoLangEngine.eval(scope, context, part.getValue()).getAsBoolean());
                    } catch (Throwable e) {
                        // Tolerate a single broken visibility expression: default the part to visible.
                        LOGGER.warn("Failed to evaluate render controller part_visibility '{}'", part.getValue(), e);
                        visibility.put(part.getKey(), true);
                    }
                }

                // Keep the public per-bone map keyed by resource short names, but derive
                // render semantics from the evaluated material identifiers. Otherwise
                // Material.default becomes the literal string "default" and loses the
                // entity_alphatest -> entity_nocull inheritance.
                final MaterialState materialState = MaterialState.from(materialValues);
                final int colorArgb = evaluateColor(scope, context, renderController.colorExpressions());

                final List<String> textureValues = new ArrayList<>();
                for (String textureExpression : renderController.textureExpressions()) {
                    try {
                        textureValues.add(MoLangEngine.eval(
                                textureScope, context, textureExpression).getAsString());
                    } catch (Throwable e) {
                        LOGGER.warn("Failed to evaluate render controller texture '{}'",
                                textureExpression, e);
                    }
                }
                if (geometryName != null && !textureValues.isEmpty()) {
                    final String primaryTexture = textureValues.getFirst();
                    // The pass backend can consume a literal resource path even when it did not
                    // originate from description.textures. The legacy adapter below still filters
                    // each slot through inverseTextureMap, preserving its old per-slot behavior.
                    final String primaryTextureName = inverseTextureMap.getOrDefault(
                            primaryTexture, primaryTexture);
                    passes.add(new EvaluatedRenderPass(
                            geometryName + "_" + primaryTextureName,
                            renderController, geometryValue, primaryTexture, textureValues,
                            Collections.unmodifiableMap(new LinkedHashMap<>(materials)),
                            Collections.unmodifiableMap(new LinkedHashMap<>(visibility)),
                            renderController.ignoreLighting(), materialState.cull(),
                            materialState.blendMode(), materialState.emissive(), materialState.glint(),
                            renderController.lightColorMultiplier(), colorArgb));
                }
            } catch (Throwable e) {
                LOGGER.warn("Failed to evaluate render controller {}", entityRenderController.identifier(), e);
            }
        }

        return List.copyOf(passes);
    }

    private static Scope withResourceBindings(Scope scope, BedrockEntityData entityData) {
        final Scope resourceScope = scope.copy();
        setResourceBinding(resourceScope, "geometry", entityData.getGeometries());
        setResourceBinding(resourceScope, "texture", entityData.getTextures());
        setResourceBinding(resourceScope, "material", entityData.getMaterials());
        return resourceScope;
    }

    private static void setResourceBinding(Scope scope, String name, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        final MutableObjectBinding binding = new MutableObjectBinding();
        values.forEach((shortName, value) -> binding.set(shortName, Value.of(value)));
        binding.block();
        scope.set(name, binding);
    }

    private static MutableObjectBinding getArrayBinding(
            Scope scope, MoLangEvaluationContext context,
            List<BedrockRenderController.Array> arrays) throws IOException {
        final MutableObjectBinding arrayBinding = new MutableObjectBinding();
        for (BedrockRenderController.Array array : arrays) {
            if (array.name().toLowerCase(Locale.ROOT).startsWith("array.")) {
                final String[] resolvedExpressions = new String[array.values().size()];
                for (int i = 0; i < array.values().size(); i++) {
                    resolvedExpressions[i] = MoLangEngine.eval(scope, context, array.values().get(i)).getAsString();
                }
                arrayBinding.set(array.name().substring(6), Value.of(resolvedExpressions));
            }
        }
        arrayBinding.block();
        return arrayBinding;
    }

    private static Map<String, String> inverse(Map<String, String> values) {
        final HashMap<String, String> inverse = new HashMap<>();
        values.forEach((key, value) -> inverse.put(value, key));
        return inverse;
    }

    private static int evaluateColor(Scope scope, MoLangEvaluationContext context,
                                     Map<String, String> expressions) {
        final float r = colorComponent(scope, context, expressions.get("r"), 1.0F);
        final float g = colorComponent(scope, context, expressions.get("g"), 1.0F);
        final float b = colorComponent(scope, context, expressions.get("b"), 1.0F);
        final float a = colorComponent(scope, context, expressions.get("a"), 1.0F);
        return Math.round(a * 255.0F) << 24
                | Math.round(r * 255.0F) << 16
                | Math.round(g * 255.0F) << 8
                | Math.round(b * 255.0F);
    }

    private static float colorComponent(Scope scope, MoLangEvaluationContext context,
                                        String expression, float fallback) {
        if (expression == null || expression.isBlank()) {
            return fallback;
        }
        String normalized = expression.trim();
        if (normalized.endsWith("f") || normalized.endsWith("F")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            return Math.max(0.0F, Math.min(1.0F,
                    (float) MoLangEngine.eval(scope, context, normalized).getAsNumber()));
        } catch (Throwable throwable) {
            LOGGER.warn("Failed to evaluate render controller color component '{}'", expression, throwable);
            return fallback;
        }
    }

    private record MaterialState(boolean cull, BlendMode blendMode, boolean emissive, boolean glint) {
        static MaterialState from(Collection<String> materialNames) {
            boolean cull = true;
            boolean emissive = false;
            boolean glint = false;
            BlendMode blendMode = BlendMode.OPAQUE;
            for (String materialName : materialNames) {
                final String name = materialName.toLowerCase(Locale.ROOT);
                // Bedrock's entity_alphatest material is defined as
                // entity_alphatest:entity_nocull. Keep the explicit one-sided
                // variants culled while preserving the base material's two-sided
                // texture-mesh behavior.
                cull &= materialCulls(name);
                emissive |= name.contains("emissive");
                glint |= name.contains("enchanted") || name.contains("glint");
                if (name.contains("alpha_blend") || name.contains("blend") || name.contains("spectator")) {
                    blendMode = BlendMode.ALPHA_BLEND;
                } else if (blendMode == BlendMode.OPAQUE &&
                        (name.contains("alpha_test") || name.contains("alphatest"))) {
                    blendMode = BlendMode.ALPHA_TEST;
                }
            }
            return new MaterialState(cull, blendMode, emissive, glint);
        }

        private static boolean materialCulls(String name) {
            if (name.contains("no_cull") || name.contains("double_sided")) {
                return false;
            }
            if (name.equals("entity_alphatest")
                    || name.equals("entity_alphatest_glint")
                    || name.equals("entity_alphatest_glint_item")
                    || name.equals("entity_alphatest_change_color")
                    || name.equals("entity_alphatest_change_color_glint")
                    || name.equals("item_in_hand_entity_alphatest")
                    || name.equals("item_in_hand_entity_alphatest_color")) {
                return false;
            }
            return true;
        }
    }
}
