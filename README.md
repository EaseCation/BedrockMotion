# BedrockMotion

Platform-independent Bedrock animation engine library. Extracted from [ViaBedrockUtility](https://github.com/EaseCation/ViaBedrockUtility) for shared use between client-side mods and server-side proxies.

## Overview

BedrockMotion provides a complete Bedrock entity animation pipeline with zero Minecraft client dependencies:

- **Animation Engine** — Keyframe interpolation, blend weights, MoLang-driven conditional playback
- **Animation Controllers** — State machine with transitions, blend curves, shortest-path blending
- **Render Controllers** — MoLang-based geometry/texture/material selection
- **Resource Pack Parsing** — Load animations, controllers, models, and render controllers from Bedrock `.mcpack` files
- **MoLang Engine** — Prepared Mocha JVM programs with current `LayeredScope` / `OverlayBinding` host values

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

## Rotation-space crossfades

Author sampling preserves fractional seconds from `anim_time_update`; do not round that value
through a millisecond clock before sampling bones or dispatching events. Host clocks retain their
own resolution. Explicit equal constant `pre`/`post` values form a continuous Catmull key, so its
neighbouring controls remain available. Constant equivalence is resolved at installation, including
constant expressions; dynamic expressions are not compared by their current sampled value.

`relative_to.rotation=entity` is pose metadata, captured and restored with the numeric channels.
A shortest-path state crossfade retains the rotation-space metadata of the lower-layer pose while
blending numeric channels. It does not propagate the sampled endpoints' new space markers, including
when those endpoints override earlier animation. Once fading finishes, normal target sampling applies
the target space. Ordinary weighted animation layering continues to retain contributing relative
tracks. Keep these paths distinct; choosing the heavier endpoint's space changes native behavior.

## License

GPL-3.0 — see [ViaBedrock](https://github.com/RaphiMC/ViaBedrock) for upstream license.

Render-pass material selection retains **resolved material identifiers** in `perBoneMaterial`, with selector order unchanged. Entity-local aliases such as `default`, `glow` or `emissive` exist only in the Molang resource-binding scope. Converting the evaluated value back to an alias would make the renderer infer transparency and lighting from arbitrary author labels and lose `entity_emissive_alpha` semantics. `MaterialState` and per-bone selection consume the same resolved values. Geometry/texture preview identities remain unchanged; this contract has no Minecraft/Iris dependency.

MoLang execution uses Mocha `3.0.3-ec.3` ProgramCompiler for keyframes, controllers, attachables and render expressions. Programs are compiled during preparation and retained by resource objects; literal values bypass compilation. The shared source lookup is an LRU bounded by 256 entries and 16 MiB estimated weight. Reentrant evaluations get separate temporary bindings; scopes are unbound on exit. Mutable parser ASTs remain inspection data, not the execution backend. Official language coverage and native precision/host-query limits are documented in `Mocha/docs/molang-coverage.md` in the workspace.
