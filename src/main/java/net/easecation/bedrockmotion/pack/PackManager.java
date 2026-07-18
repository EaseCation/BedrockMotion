package net.easecation.bedrockmotion.pack;

import lombok.Getter;
import net.easecation.bedrockmotion.pack.content.Content;
import net.easecation.bedrockmotion.pack.definitions.*;
import net.easecation.bedrockmotion.pack.definitions.controller.RenderControllerDefinitions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Getter
public class PackManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(PackManager.class);

    private List<Content> packs;
    private final Profile profile;
    private final RenderControllerDefinitions renderControllerDefinitions;
    private final EntityDefinitions entityDefinitions;
    private final ModelDefinitions modelDefinitions;
    private final MaterialDefinitions materialDefinitions;
    private final AnimationDefinitions animationDefinitions;
    private final AnimationControllerDefinitions animationControllerDefinitions;

    public PackManager(final List<Content> customPacks) {
        this(customPacks, Profile.FULL, true);
    }

    /**
     * Builds a manager for the requested consumer profile. The server-animation profile releases all
     * input pack contents after its definitions have been built.
     */
    public PackManager(final List<Content> customPacks, final Profile profile) {
        this(customPacks, profile, profile == Profile.FULL);
    }

    /**
     * Builds a manager with explicit control over whether expanded pack contents remain reachable in
     * the full compatibility profile. Server-animation managers always release them.
     */
    public PackManager(final List<Content> customPacks, final Profile profile, final boolean retainPacksAfterBuild) {
        this.profile = Objects.requireNonNull(profile, "profile");
        final List<Content> allPacks = new ArrayList<>();

        // Load vanilla resource pack as base layer
        try (InputStream is = PackManager.class.getResourceAsStream("/libs/vanilla_packs/vanilla.mcpack")) {
            if (is != null) {
                allPacks.add(new Content(is.readAllBytes()));
                LOGGER.info("[PackManager] Loaded vanilla resource pack");
            } else {
                LOGGER.warn("[PackManager] Vanilla resource pack not found in library resources");
            }
        } catch (IOException e) {
            LOGGER.warn("[PackManager] Failed to load vanilla resource pack", e);
        }

        // Custom packs on top (can override vanilla definitions)
        allPacks.addAll(customPacks);
        this.packs = allPacks;

        if (profile == Profile.FULL) {
            this.renderControllerDefinitions = new RenderControllerDefinitions(this);
            this.entityDefinitions = new EntityDefinitions(this);
            this.modelDefinitions = new ModelDefinitions(this);
            this.materialDefinitions = new MaterialDefinitions(this);
            this.animationDefinitions = new AnimationDefinitions(this);
            this.animationControllerDefinitions = new AnimationControllerDefinitions(this);
        } else {
            final ServerAnimationLayer layer = ServerAnimationLayer.foldBottomToTop(
                    allPacks.stream().map(ServerAnimationLayer::fromContent).toList());
            this.renderControllerDefinitions = new RenderControllerDefinitions(layer.renderControllers());
            this.entityDefinitions = null;
            this.modelDefinitions = null;
            this.materialDefinitions = null;
            this.animationDefinitions = new AnimationDefinitions(layer.animations());
            this.animationControllerDefinitions = new AnimationControllerDefinitions(layer.animationControllers());
        }

        if (profile == Profile.SERVER_ANIMATION || !retainPacksAfterBuild) {
            this.packs = List.of();
        }
    }

    private PackManager(final ServerAnimationLayer definitions) {
        this.packs = List.of();
        this.profile = Profile.SERVER_ANIMATION;
        this.renderControllerDefinitions = new RenderControllerDefinitions(definitions.renderControllers());
        this.entityDefinitions = null;
        this.modelDefinitions = null;
        this.materialDefinitions = null;
        this.animationDefinitions = new AnimationDefinitions(definitions.animations());
        this.animationControllerDefinitions = new AnimationControllerDefinitions(definitions.animationControllers());
    }

    /** Builds a runtime from already parsed, immutable per-pack layers. */
    public static PackManager fromServerAnimationLayers(final List<ServerAnimationLayer> customLayersBottomToTop) {
        final List<ServerAnimationLayer> layers = new ArrayList<>(customLayersBottomToTop.size() + 1);
        if (VanillaServerAnimation.LAYER != null) {
            layers.add(VanillaServerAnimation.LAYER);
        }
        layers.addAll(customLayersBottomToTop);
        return new PackManager(ServerAnimationLayer.foldBottomToTop(layers));
    }

    /** Fingerprint of the bundled vanilla definitions and the parsed-layer schema. */
    public static String serverAnimationFingerprint() {
        return VanillaServerAnimation.FINGERPRINT;
    }

    private static final class VanillaServerAnimation {
        private static final ServerAnimationLayer LAYER;
        private static final String FINGERPRINT;

        static {
            ServerAnimationLayer layer = null;
            byte[] bytes = new byte[0];
            try (InputStream input = PackManager.class.getResourceAsStream("/libs/vanilla_packs/vanilla.mcpack")) {
                if (input != null) {
                    bytes = input.readAllBytes();
                    layer = ServerAnimationLayer.fromContent(new Content(bytes));
                }
            } catch (IOException e) {
                LOGGER.warn("[PackManager] Failed to prepare shared vanilla animation definitions", e);
            }
            LAYER = layer;
            try {
                final MessageDigest digest = MessageDigest.getInstance("SHA-256");
                digest.update("BedrockMotion-ServerAnimation-v1\0".getBytes(StandardCharsets.US_ASCII));
                digest.update(bytes);
                FINGERPRINT = java.util.HexFormat.of().formatHex(digest.digest());
            } catch (NoSuchAlgorithmException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    public enum Profile {
        FULL,
        /**
         * Definitions used by ViaBedrock's server-side animation runtime. Entity, model, and material
         * definitions are supplied by ViaBedrock itself and their getters return {@code null}.
         */
        SERVER_ANIMATION
    }
}
