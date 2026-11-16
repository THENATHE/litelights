# LiteLights

LiteLights is a client-side Fabric mod for Minecraft 26.3. It adds shadows, better water, sky, fog, weather and ambient particles, but keeps the vanilla look, and it's fast enough to run on a normal setup next to Sodium. There are no presets: every effect has its own setting, and most of them are on by default.

![LiteLights at default settings](docs/preview.jpg)

*LiteLights at its default settings, on Sodium, with no resource packs.*

## What it does

- **Shadows:** cached sun and moon shadows, creature shadows instead of vanilla's round blob, and five detail levels.
- **Lighting:** bloom, sunbeams (above and under water), glint on water, ice, metal and glass, colour grading by time of day, and enchantment outlines on held items and armour.
- **Water:** natural colours with depth, reflections, caustics, edge foam, large water textures, splashes, ocean waves (Experimental) and wakes behind boats and swimmers.
- **Sky and fog:** LiteLights' own sun, moon and stars, clouds in one or two layers, haze with mist banks and biome colours, the aurora borealis, meteor showers and distant fireworks.
- **Weather:** its own rain and snow, storm fog for each kind of storm, sandstorms and haboobs, rain drips and wall streaks, and frost, snow and sand on windows.
- **Particles and plants:** fireflies, dust, pollen, better fire and smoke, ember veins, and swaying grass, plants and leaves.
- **Far terrain:** Voxy and Distant Horizons land gets the same haze, light and water.
- **Overlays:** softer blocks like grass and sand spill onto harder ones, with textures made from whatever resource packs you have loaded. This one needs Continuity.

Everything is in LiteLights' settings screen (through Mod Menu or a keybind you set), and in Sodium's Video Settings if you have Sodium.

## Install

1. Install Fabric Loader 0.19.5 or newer and Fabric API 0.166.0 or newer for Minecraft 26.3, on Java 25.
2. Drop the jar from the [latest release](../../releases/latest) into your `mods` folder.
3. Optional: Sodium, Continuity (for overlays), Mod Menu.

## Compatibility

- Works with Sodium 0.9.2 and 0.9.3 betas, or the vanilla renderer.
- Doesn't work with Iris. It's a different approach to the same problem, so pick one.
- If you have Particle Rain, Immersive Storms, Weather Glass, Wakes, Big Water or Streams Reflowing, LiteLights turns off its own version of whatever that mod does, and the settings screen tells you which.
- Voxy and Distant Horizons both work (one at a time). Punchy! works too, outlines included.

If something breaks, please open an issue with your log and mod list and I'll take a look.

## Credits

LiteLights is written from scratch. A lot of the ideas came from other mods though, and they deserve the credit for those:

- **ImmersiveVFX** by Shinyflvres: the cached shadow map, forward-shaded effects and biome atmosphere.
- **Big Water** by Tec: large water textures. LiteLights ships Big Water's CC0 water and lava textures.
- **Particular** by Chai: the jump splash.
- **Immersive Storms** by TheDeathlyCow: storm atmosphere.
- **Weather Glass** by duffopuffo: weather on glass.
- **Particle Rain** by pigcart: rain drips and wall streaks.
- **Wakes** by Goby56: wakes behind boats and swimmers.
- **Continuity** by Pepper_Bell, which the overlays draw through.
- **LambDynamicLights** and **LambdaBetterGrass** by LambdAurora, which I recommend running alongside it.

None of those mods' code, shaders or textures were used, apart from Big Water's CC0 textures. The full list with links is in [CREDITS.md](CREDITS.md).

## License

MIT, see [LICENSE](LICENSE). A few things in here aren't mine and keep their own terms, and `LICENSE` lists them:

- the shaders in `shaders/core/` are modified copies of Minecraft's and still belong to Mojang;
- Big Water's textures are CC0;
- the hash functions in `noise.glsl` are Dave Hoskins' (MIT);
- the rain splash sprites and the icon are made from vanilla textures.

## Building

```
JAVA_HOME=/path/to/jdk-25 ./gradlew build
```

Gradle has to run on Java 25 (newer JDKs can't load the build script yet). The jar ends up in `build/libs/`.
