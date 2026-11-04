package net.easecation.bedrockmotion.render;

import net.easecation.bedrockmotion.mocha.MoLangEvaluationContext;
import net.easecation.bedrockmotion.pack.definitions.controller.RenderControllerDefinitions;
import org.cube.converter.data.bedrock.BedrockAttachableData;
import org.cube.converter.data.bedrock.BedrockEntityData;
import org.cube.converter.data.bedrock.controller.BedrockRenderController;
import org.junit.jupiter.api.Test;
import team.unnamed.mocha.runtime.Scope;
import team.unnamed.mocha.runtime.value.Function;
import team.unnamed.mocha.runtime.value.MutableObjectBinding;
import team.unnamed.mocha.runtime.value.NumberValue;
import team.unnamed.mocha.runtime.value.Value;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderControllerEvaluatorTest {
    @Test
    void resolvesAttachableResourceSymbolsWithoutMutatingCallerScope() {
        final BedrockAttachableData definition = definition();
        final BedrockRenderController controller = new BedrockRenderController(
                "controller.render.ec_gun",
                Map.of("*", "variable.is_enchanted ? Material.enchanted : Material.blend"),
                "Geometry.default",
                List.of("Texture.default", "Texture.enchanted"),
                List.of(), List.of(), List.of(), Map.of(), Map.of(), false, 1.0F);
        final Scope scope = Scope.create();
        final MutableObjectBinding variables = new MutableObjectBinding();
        variables.set("is_enchanted", Value.of(false));
        scope.set("variable", variables);

        final List<RenderControllerEvaluator.EvaluatedRenderPass> passes = evaluate(
                definition, controller, scope);

        assertEquals(1, passes.size());
        assertEquals("geometry.gun_dwp_grace_blue", passes.getFirst().geometryValue());
        assertEquals("textures/entity/gun_dwp_grace_blue", passes.getFirst().textureValue());
        assertEquals(List.of(
                "textures/entity/gun_dwp_grace_blue",
                "textures/misc/enchanted_item_glint"), passes.getFirst().textureValues());
        assertEquals("blend", passes.getFirst().perBoneMaterial().get("*"));
        assertEquals(RenderControllerEvaluator.BlendMode.ALPHA_BLEND, passes.getFirst().blendMode());
        assertFalse(scope.entries().containsKey("geometry"));
        assertFalse(scope.entries().containsKey("texture"));
        assertFalse(scope.entries().containsKey("material"));
    }

    @Test
    void legacyModelAdapterStillExpandsTextureSlots() {
        final BedrockAttachableData definition = definition();
        final BedrockRenderController controller = new BedrockRenderController(
                "controller.render.ec_gun", Map.of("*", "Material.blend"),
                "Geometry.default", List.of("Texture.default", "Texture.enchanted"),
                List.of(), List.of(), List.of(), Map.of(), Map.of(), false, 1.0F);

        final List<RenderControllerEvaluator.EvaluatedModel> models = RenderControllerEvaluator.evaluate(
                definition, Scope.create(),
                new RenderControllerDefinitions(Map.of(controller.identifier(), controller)),
                Map.of("geometry.gun_dwp_grace_blue", "default"),
                Map.of(
                        "textures/entity/gun_dwp_grace_blue", "default",
                        "textures/misc/enchanted_item_glint", "enchanted"));

        assertEquals(List.of("default_default", "default_enchanted"),
                models.stream().map(RenderControllerEvaluator.EvaluatedModel::key).toList());
    }

    @Test
    void legacyModelAdapterKeepsKnownLaterSlotWhenPrimaryIsLiteral() {
        final BedrockAttachableData definition = definition();
        final BedrockRenderController controller = new BedrockRenderController(
                "controller.render.ec_gun", Map.of("*", "Material.blend"),
                "Geometry.default", List.of("'textures/entity/unregistered'", "Texture.enchanted"),
                List.of(), List.of(), List.of(), Map.of(), Map.of(), false, 1.0F);

        final List<RenderControllerEvaluator.EvaluatedModel> models = RenderControllerEvaluator.evaluate(
                definition, Scope.create(),
                new RenderControllerDefinitions(Map.of(controller.identifier(), controller)),
                Map.of("geometry.gun_dwp_grace_blue", "default"),
                Map.of("textures/misc/enchanted_item_glint", "enchanted"));

        assertEquals(List.of("default_enchanted"),
                models.stream().map(RenderControllerEvaluator.EvaluatedModel::key).toList());
    }

    @Test
    void resolvesRenderControllerArraysAgainstResourceSymbols() {
        final BedrockAttachableData definition = definition();
        final BedrockRenderController controller = new BedrockRenderController(
                "controller.render.ec_gun",
                Map.of("*", "Array.materials[0]"),
                "Array.geometries[0]",
                List.of("Array.textures[0]"),
                List.of(new BedrockRenderController.Array("Array.materials", List.of("Material.blend"))),
                List.of(new BedrockRenderController.Array("Array.textures", List.of("Texture.default"))),
                List.of(new BedrockRenderController.Array("Array.geometries", List.of("Geometry.default"))),
                Map.of(), Map.of(), false, 1.0F);

        final List<RenderControllerEvaluator.EvaluatedRenderPass> passes = evaluate(
                definition, controller, Scope.create());

        assertEquals(1, passes.size());
        assertEquals("geometry.gun_dwp_grace_blue", passes.getFirst().geometryValue());
        assertEquals("textures/entity/gun_dwp_grace_blue", passes.getFirst().textureValue());
        assertEquals("blend", passes.getFirst().perBoneMaterial().get("*"));
    }

    @Test
    void selectsNegatedFunctionConditionFromAttachableRenderControllers() {
        final BedrockAttachableData resources = definition();
        final BedrockAttachableData definition = new BedrockAttachableData(
                resources.getIdentifier(), resources.getScripts(),
                List.of(
                        new BedrockEntityData.RenderController("controller.render.ec_gun",
                                "!query.property('easecation:custom_spectator')"),
                        new BedrockEntityData.RenderController("controller.render.ec_gun_spectator",
                                "query.property('easecation:custom_spectator')")),
                resources.getMaterials(), resources.getAnimations(), resources.getTextures(),
                resources.getGeometries(), resources.getParticleEffects(), resources.getItemConditions());
        final BedrockRenderController normal = new BedrockRenderController(
                "controller.render.ec_gun", Map.of("*", "Material.blend"),
                "Geometry.default", List.of("Texture.default"),
                List.of(), List.of(), List.of(), Map.of(), Map.of(), false, 1.0F);
        final BedrockRenderController spectator = new BedrockRenderController(
                "controller.render.ec_gun_spectator", Map.of("*", "Material.spectator"),
                "Geometry.default", List.of("Texture.default"),
                List.of(), List.of(), List.of(), Map.of(), Map.of(), false, 1.0F);
        final MutableObjectBinding query = new MutableObjectBinding();
        query.set("property", (Function<Object>) (execution, arguments) -> NumberValue.zero());
        final Scope scope = Scope.create();
        scope.set("query", query);

        final List<RenderControllerEvaluator.EvaluatedRenderPass> passes =
                RenderControllerEvaluator.evaluatePasses(
                        definition, scope, MoLangEvaluationContext.EMPTY,
                        new RenderControllerDefinitions(Map.of(
                                normal.identifier(), normal,
                                spectator.identifier(), spectator)),
                        Map.of("geometry.gun_dwp_grace_blue", "default"),
                        Map.of("textures/entity/gun_dwp_grace_blue", "default"));

        assertEquals(1, passes.size());
        assertEquals("controller.render.ec_gun", passes.getFirst().controller().identifier());
    }

    @Test
    void entityAlphaTestUsesBedrockTwoSidedMaterialSemantics() {
        final BedrockRenderController controller = new BedrockRenderController(
                "controller.render.ec_gun", Map.of("*", "Material.default"),
                "Geometry.default", List.of("Texture.default"),
                List.of(), List.of(), List.of(), Map.of(), Map.of(), false, 1.0F);

        final List<RenderControllerEvaluator.EvaluatedRenderPass> doubleSided =
                evaluate(definition(), controller, Scope.create());
        assertEquals(1, doubleSided.size());
        assertFalse(doubleSided.getFirst().cull());
        assertEquals(RenderControllerEvaluator.BlendMode.ALPHA_TEST,
                doubleSided.getFirst().blendMode());

        final BedrockAttachableData resources = definition();
        final Map<String, String> oneSidedMaterials = new LinkedHashMap<>(resources.getMaterials());
        oneSidedMaterials.put("default", "entity_alphatest_one_sided");
        final BedrockAttachableData oneSidedDefinition = new BedrockAttachableData(
                resources.getIdentifier(), resources.getScripts(), resources.getControllers(),
                oneSidedMaterials, resources.getAnimations(), resources.getTextures(),
                resources.getGeometries(), resources.getParticleEffects(), resources.getItemConditions());
        final List<RenderControllerEvaluator.EvaluatedRenderPass> oneSided =
                evaluate(oneSidedDefinition, controller, Scope.create());
        assertEquals(1, oneSided.size());
        assertTrue(oneSided.getFirst().cull());
    }

    private static List<RenderControllerEvaluator.EvaluatedRenderPass> evaluate(
            BedrockAttachableData definition, BedrockRenderController controller, Scope scope) {
        return RenderControllerEvaluator.evaluatePasses(
                definition,
                scope,
                MoLangEvaluationContext.EMPTY,
                new RenderControllerDefinitions(Map.of(controller.identifier(), controller)),
                Map.of("geometry.gun_dwp_grace_blue", "default"),
                Map.of(
                        "textures/entity/gun_dwp_grace_blue", "default",
                        "textures/misc/enchanted_item_glint", "enchanted"));
    }

    @Test
    void brokenPartVisibilityAndMaterialExpressionsDegradeWithoutDroppingPass() {
        final BedrockAttachableData definition = definition();
        final BedrockRenderController controller = new BedrockRenderController(
                "controller.render.ec_gun", Map.of("*", "(1 +"),
                "Geometry.default", List.of("Texture.default"),
                List.of(), List.of(), List.of(), Map.of("root", "(1 +"), Map.of(), false, 1.0F);

        final List<RenderControllerEvaluator.EvaluatedRenderPass> passes = evaluate(
                definition, controller, Scope.create());

        assertEquals(1, passes.size());
        assertEquals("(1 +", passes.getFirst().perBoneMaterial().get("*"));
        assertTrue(passes.getFirst().partVisibility().get("root"));
    }

    @Test
    void brokenControllerConditionSkipsOnlyThatController() {
        final BedrockAttachableData resources = definition();
        final BedrockAttachableData definition = new BedrockAttachableData(
                resources.getIdentifier(), resources.getScripts(),
                List.of(
                        new BedrockEntityData.RenderController("controller.render.broken", "(1 +"),
                        new BedrockEntityData.RenderController("controller.render.ec_gun", "")),
                resources.getMaterials(), resources.getAnimations(), resources.getTextures(),
                resources.getGeometries(), resources.getParticleEffects(), resources.getItemConditions());
        final BedrockRenderController broken = new BedrockRenderController(
                "controller.render.broken", Map.of("*", "Material.blend"),
                "Geometry.default", List.of("Texture.default"),
                List.of(), List.of(), List.of(), Map.of(), Map.of(), false, 1.0F);
        final BedrockRenderController normal = new BedrockRenderController(
                "controller.render.ec_gun", Map.of("*", "Material.blend"),
                "Geometry.default", List.of("Texture.default"),
                List.of(), List.of(), List.of(), Map.of(), Map.of(), false, 1.0F);

        final List<RenderControllerEvaluator.EvaluatedRenderPass> passes =
                RenderControllerEvaluator.evaluatePasses(
                        definition, Scope.create(), MoLangEvaluationContext.EMPTY,
                        new RenderControllerDefinitions(Map.of(
                                broken.identifier(), broken,
                                normal.identifier(), normal)),
                        Map.of("geometry.gun_dwp_grace_blue", "default"),
                        Map.of("textures/entity/gun_dwp_grace_blue", "default"));

        assertEquals(1, passes.size());
        assertEquals("controller.render.ec_gun", passes.getFirst().controller().identifier());
    }

    private static BedrockAttachableData definition() {
        final Map<String, String> materials = new LinkedHashMap<>();
        materials.put("default", "entity_alphatest");
        materials.put("blend", "entity_alphablend");
        materials.put("enchanted", "entity_alphatest_glint");

        final Map<String, String> textures = new LinkedHashMap<>();
        textures.put("default", "textures/entity/gun_dwp_grace_blue");
        textures.put("enchanted", "textures/misc/enchanted_item_glint");

        return new BedrockAttachableData(
                "easecation:gun_dwp_grace_blue",
                BedrockEntityData.Scripts.emptyScript(),
                List.of(new BedrockEntityData.RenderController("controller.render.ec_gun", "")),
                materials,
                Map.of(),
                textures,
                Map.of("default", "geometry.gun_dwp_grace_blue"),
                Map.of());
    }
}
