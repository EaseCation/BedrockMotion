package net.easecation.bedrockmotion.attachable;

import net.easecation.bedrockmotion.mocha.MoLangEngine;
import org.cube.converter.data.bedrock.BedrockAttachableData;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Immutable scripts held by the pack generation, independently of the global parse cache. */
public record AttachableProgram(List<MoLangEngine.CompiledExpression> initialize,
                                List<MoLangEngine.CompiledExpression> preAnimation,
                                List<Animate> animate,
                                List<MoLangEngine.CompiledExpression> scale) {
    public AttachableProgram {
        initialize = List.copyOf(initialize);
        preAnimation = List.copyOf(preAnimation);
        animate = List.copyOf(animate);
        scale = List.copyOf(scale);
    }

    public static AttachableProgram compile(BedrockAttachableData definition) {
        try {
            var scripts = definition.getScripts();
            List<Animate> animate = new ArrayList<>();
            for (var entry : scripts.animates()) {
                animate.add(new Animate(entry.name(), MoLangEngine.compile(
                        entry.expression().isBlank() ? "1" : entry.expression())));
            }
            var scale = scripts.scale();
            return new AttachableProgram(compileAll(scripts.initialize()), compileAll(scripts.pre_animation()),
                    animate, compileAll(List.of(scale.scale(), scale.scaleX(), scale.scaleY(), scale.scaleZ())));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid attachable scripts: " + definition.getIdentifier(), exception);
        }
    }

    private static List<MoLangEngine.CompiledExpression> compileAll(List<String> sources) throws IOException {
        List<MoLangEngine.CompiledExpression> result = new ArrayList<>(sources.size());
        for (String source : sources) result.add(MoLangEngine.compile(source));
        return result;
    }

    public record Animate(String name, MoLangEngine.CompiledExpression condition) {}
}
