package net.easecation.bedrockmotion.render;

import net.easecation.bedrockmotion.mocha.MoLangEngine;
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

        final List<EvaluatedModel> models = new ArrayList<>();

        for (final BedrockEntityData.RenderController entityRenderController : entityData.getControllers()) {
            final BedrockRenderController renderController = rcDefs.getRenderControllers()
                    .get(entityRenderController.identifier());
            if (renderController == null) {
                continue;
            }

            // Evaluate render controller condition
            if (!entityRenderController.condition().isBlank()) {
                try {
                    final Value conditionResult = MoLangEngine.eval(scope, entityRenderController.condition());
                    if (!conditionResult.getAsBoolean()) {
                        continue;
                    }
                } catch (Throwable e) {
                    LOGGER.warn("Failed to evaluate render controller condition", e);
                    continue;
                }
            }

            try {
                final Scope geometryScope = scope.copy();
                geometryScope.set("array", getArrayBinding(scope, renderController.geometries()));
                final Scope textureScope = scope.copy();
                textureScope.set("array", getArrayBinding(scope, renderController.textures()));

                final String geometryValue = MoLangEngine.eval(geometryScope, renderController.geometryExpression()).getAsString();
                final String geometryName = inverseGeometryMap.get(geometryValue);

                for (String textureExpression : renderController.textureExpressions()) {
                    final String textureValue = MoLangEngine.eval(textureScope, textureExpression).getAsString();
                    final String textureName = inverseTextureMap.get(textureValue);
                    if (geometryName != null && textureName != null) {
                        models.add(new EvaluatedModel(
                                geometryName + "_" + textureName,
                                renderController, geometryValue, textureValue));
                    }
                }
            } catch (Throwable e) {
                return List.of();
            }
        }

        return models;
    }

    private static MutableObjectBinding getArrayBinding(
            Scope scope, List<BedrockRenderController.Array> arrays) throws IOException {
        final MutableObjectBinding arrayBinding = new MutableObjectBinding();
        for (BedrockRenderController.Array array : arrays) {
            if (array.name().toLowerCase(Locale.ROOT).startsWith("array.")) {
                final String[] resolvedExpressions = new String[array.values().size()];
                for (int i = 0; i < array.values().size(); i++) {
                    resolvedExpressions[i] = MoLangEngine.eval(scope, array.values().get(i)).getAsString();
                }
                arrayBinding.set(array.name().substring(6), Value.of(resolvedExpressions));
            }
        }
        arrayBinding.block();
        return arrayBinding;
    }
}
