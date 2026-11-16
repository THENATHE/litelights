# Credits

LiteLights is written from scratch. Where an idea came from another project, it is credited here as "idea by". No code, shaders or data were copied from those projects, with one exception noted below (Big Water's textures, which are CC0).

- **Rendering ideas** (cached shadow map, forward-shaded effects, biome atmosphere): idea by Shinyflvres (ImmersiveVFX), whose atmosphere effects were in turn inspired by Oreville Studios' Realistic Biomes.
- **Large water textures:** idea by Tec (Big Water). Vanilla-style water and lava textures by Tec, from Big Water, CC0-1.0.
- **Jump splash:** idea by Chai (Particular).
- **Storm atmosphere** (sandstorm fog, blizzards, swamp and pale garden fog, ambient wind, dark pale-garden rain): ideas by TheDeathlyCow (Immersive Storms).
- **Weather on glass** (frost, stuck snow, dust after sandstorms): glass weather idea by duffopuffo (Weather Glass).
- **Rain drips and wall streaks** (on walls and glass): drip and streak ideas by pigcart (Particle Rain).
- **Wakes** (behind boats and swimmers, rings where things fall in, bow spray): wake idea by Goby56 (Wakes). Written from scratch from Wakes' public description and the textbook wave equation; no code, shaders or textures used.
- **Overlays module:** draws through Continuity (Pepper_Bell).
- **Recommended:** LambDynamicLights and LambdaBetterGrass by LambdAurora.

## Links

| Project | Author | Used | Link |
|---|---|---|---|
| ImmersiveVFX | Shinyflvres | Ideas: cached shadow map, forward-shaded effects, biome atmosphere | https://www.curseforge.com/minecraft/mc-mods/immersivevfx |
| Realistic Biomes | Oreville Studios | Inspiration behind ImmersiveVFX's atmosphere effects | https://www.minecraft.net/en-us/article/lets-play-realistic-biomes |
| Big Water | Tec | Idea (large water and lava textures) and its vanilla-style textures (CC0-1.0) | https://modrinth.com/mod/big-water |
| Particular | Chai | Idea (jump splash) | https://modrinth.com/mod/particular |
| Immersive Storms | TheDeathlyCow | Ideas (storm atmosphere) | https://modrinth.com/mod/immersive-storms |
| Weather Glass | duffopuffo | Idea (weather on glass) | https://www.curseforge.com/minecraft/mc-mods/weather-glass |
| Particle Rain | pigcart | Ideas (rain drips, water streaks on walls) | https://modrinth.com/mod/particle-rain |
| Wakes | Goby56 | Idea (wakes behind boats and swimmers) | https://modrinth.com/mod/wakes |
| Continuity | Pepper_Bell | Draws the overlays | https://modrinth.com/mod/continuity |
| LambDynamicLights | LambdAurora | Recommended companion | https://modrinth.com/mod/lambdynamiclights |
| LambdaBetterGrass | LambdAurora | Recommended companion | https://modrinth.com/mod/lambdabettergrass |

## Licences

- **LiteLights**, including the overlays module: MIT licence, THENATHE. Its full text, and a Third-party content section with the notices for the Big Water textures, the hash, the core shaders, the splash sprites and the icon, are in `LICENSE`, which ships in the jar's `META-INF` beside this file.
- **Big Water's vanilla-style water and lava textures** (`assets/litelights/textures/fluid/`): CC0 1.0 Universal, by Tec. What was changed (lossless recompression, and only the shown frames of `lava_flow.png` kept) is in `assets/litelights/textures/fluid/license-bigwater-textures.txt`, which ships in the jar beside them.
- **Weather Glass** (PolyForm Shield) and **Particle Rain** are not included, and none of their code, shaders or textures were opened: weather on glass, drips and wall streaks were written from their public descriptions only.
- **Wakes** (Goby56's, and its port Wakes Reforged; both GPL-3.0) is not included, and none of its code, shaders, textures or design notes were opened: LiteLights' wakes were written from its public description, changelog and pictures only.
- **"Hash without Sine"** by Dave Hoskins (MIT licence, Copyright (c)2014 David Hoskins, https://www.shadertoy.com/view/4djSRW): the three small hash functions in `shaders/include/noise.glsl` are his widely shared snippet. His copyright and permission notice is above them there, and in `LICENSE`.
- **Minecraft's core shaders:** the files in `shaders/core/` are modified copies of Mojang's vanilla shaders. They are still Mojang's and aren't covered by LiteLights' MIT licence; only LiteLights' own changes are. `shaders/core/readme.txt` says the same.
- **Soft rain splash sprites** (`textures/particle/rain_splash_soft_0` to `_3`): the same shapes as vanilla's rain splashes, recoloured white. They are a few pixels each, and we treat them as free use.
- **Mod icon:** an oak tree made from vanilla's oak log and leaf textures (`tools/render_icon.py`). Our position is that it's a transparent, actively artistic free use of them.
- **Sodium** (PolyForm Shield 1.0.0) is not included and none of its code is copied. When Sodium is installed, LiteLights' Sodium terrain shader includes Sodium's own shader includes from Sodium's jar at run time, and LiteLights' pages in Sodium's Video Settings use Sodium's public config API.
- **Continuity** is not included; the overlays module calls its public API when it is installed.
