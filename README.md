# BedrockMotion

Platform-independent Bedrock animation engine library. Extracted from [ViaBedrockUtility](https://github.com/EaseCation/ViaBedrockUtility) for shared use between client-side mods and server-side proxies.

## Overview

BedrockMotion provides a complete Bedrock entity animation pipeline with zero Minecraft client dependencies:

- **Animation Engine** — Keyframe interpolation, blend weights, MoLang-driven conditional playback
- **Animation Controllers** — State machine with transitions, blend curves, shortest-path blending
- **Render Controllers** — MoLang-based geometry/texture/material selection
- **Resource Pack Parsing** — Load animations, controllers, models, and render controllers from Bedrock `.mcpack` files
- **MoLang Engine** — Cached parsing and evaluation with `LayeredScope` / `OverlayBinding` for performance

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                   BedrockMotion                      │
│              net.easecation.bedrockmotion            │
│                                                      │
│  model/        IBoneTarget, IBoneModel interfaces    │
│  animator/     Animation execution (Animator)        │
│  controller/   Animation controller state machine    │
│  render/       Render controller evaluator           │
│  mocha/        MoLang engine wrapper                 │
│  pack/         Resource pack parsing (PackManager)   │
│  animation/    Data models + keyframe interpolation  │
└────────────┬────────────────────────┬────────────────┘
             │                        │
   ┌─────────▼─────────┐   ┌─────────▼─────────┐
   │ ViaBedrockUtility  │   │ ViaBedrock         │
   │ (Client Mod)       │   │ (Proxy)            │
   │                    │   │                    │
   │ MC ModelPart       │   │ SimpleBone         │
   │ adapter            │   │ adapter            │
   └────────────────────┘   └────────────────────┘
```

## Usage

### Dependency (Gradle)

```groovy
repositories {
    mavenLocal()
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'net.easecation:bedrock-motion:1.0.0'
}
```

### Implement bone interfaces

```java
public class MyBone implements IBoneTarget {
    // Implement rotation, offset, scale storage
}

public class MyBoneModel implements IBoneModel {
    // Implement bone index and reset
}
```

### Load resource packs and run animations

```java
// Load packs
Content content = new Content(zipBytes);
PackManager packManager = new PackManager(List.of(content));

// Create animator
AnimationDefinitions.AnimationData animData = packManager.getAnimationDefinitions()
    .getAnimations().get("animation.entity.idle");
Animator animator = new Animator(eventListener, animData);

// Each tick
animator.setBaseScope(frameScope);
boneModel.resetAllBones();
animator.animate(boneModel);
```

## Building

```bash
./gradlew build
./gradlew publishToMavenLocal
```

Requires Java 17+.

## Dependencies

| Library | Purpose |
|---------|---------|
| [mocha](https://github.com/unnamed/mocha) | MoLang parser and runtime |
| [CubeConverter](https://github.com/EaseCation/CubeConverter) | Bedrock geometry/entity data models |
| [JOML](https://github.com/JOML-CI/JOML) | Math (vectors, matrices, quaternions) |
| [Gson](https://github.com/google/gson) | JSON parsing |

## License

GPL-3.0 — see [ViaBedrock](https://github.com/RaphiMC/ViaBedrock) for upstream license.
