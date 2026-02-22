package net.easecation.bedrockmotion.pack.definitions.controller;

import lombok.Getter;
import org.cube.converter.data.bedrock.controller.BedrockRenderController;
import org.cube.converter.parser.bedrock.controller.BedrockControllerParser;
import net.easecation.bedrockmotion.pack.PackManager;
import net.easecation.bedrockmotion.pack.content.Content;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

// https://wiki.bedrock.dev/entities/render-controllers
@Getter
public class RenderControllerDefinitions {
    private static final Logger LOGGER = LoggerFactory.getLogger(RenderControllerDefinitions.class);

    private final Map<String, BedrockRenderController> renderControllers = new HashMap<>();

    public RenderControllerDefinitions(final PackManager packManager) {
        for (Content content : packManager.getPacks()) {
            for (String controllerPath : content.getFilesDeep("render_controllers/", ".json")) {
                try {
                    for (BedrockRenderController bedrockRenderController : BedrockControllerParser.parse(content.getString(controllerPath))) {
                        this.renderControllers.put(bedrockRenderController.identifier(), bedrockRenderController);
                    }
                } catch (Throwable e) {
                    LOGGER.warn("Failed to parse render controller definition {}", controllerPath);
                }
            }
        }
    }
}
