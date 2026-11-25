# LiteLights

LiteLights is a client-side Fabric mod for Minecraft 26.3. It adds shadows, better water, sky, fog, weather and ambient particles, but keeps the vanilla look, and it's fast enough to run on a normal setup next to Sodium. Every effect has its own setting, and most of them are on by default. Since 1.1 it also has far terrain of its own, Dynamic clouds and visual snow, all off by default for now.

![LiteLights at default settings](docs/preview.jpg)

*LiteLights on Sodium, with no resource packs.*

## What it does

- **Shadows:** cached sun and moon shadows, creature shadows instead of vanilla's round blob, and five detail levels.
- **Lighting:** bloom, sunbeams (above and under water), glint on water, ice, metal and glass, colour grading by time of day, and enchantment outlines on held items and armour.
- **Water:** natural colours with depth, reflections, caustics, edge foam, large water textures, splashes, ocean waves and wakes behind boats and swimmers.
- **Sky and fog:** LiteLights' own sun, moon and stars, clouds in one or two layers, haze with mist banks and biome colours, the aurora borealis, meteor showers, distant fireworks and rainbows after the rain.
- **Dynamic clouds** (Experimental; Fancy clouds stay the default): big clouds of real height that form, grow and drift with the weather, reaching 8 km, with their shadows drifting over the land. Rain closes in under its own clouds, and every LiteLights player on a server sees the same sky.
- **Weather:** its own rain and snow, rolling mist along the ground (or soft 3D puffs if you prefer), storm fog for each kind of storm, sandstorms and haboobs, puddles in the rain, snow on leaves and under trees, rain drips and wall streaks, and frost, snow and sand on windows.
- **Visual snow** (off by default): snow drawn on the land as it lies, without placing a block: on stairs, slabs, fences and walls, over block edges, under trees and overhangs, white grass in snow, and on far terrain.
- **Particles and plants:** fireflies, dust, pollen, better fire and smoke, ember veins, and swaying grass, plants and leaves.
- **Far terrain** (Experimental, off by default): the land you have visited, drawn up to 16 km past the render distance with the game's own textures in LiteLights' light and haze, with far water, far lights at night and far shadows. Nothing is generated and nothing is asked of the server. **Seed generation** (also Experimental and off) adds land you haven't visited yet, worked out from the world's seed.
- **Far shadows:** the sun's shadows carry on past the shadow distance over the land beyond it.
- **Overlays:** softer blocks like grass and sand spill onto harder ones, with textures made from whatever resource packs you have loaded. This one needs Continuity.

The first time you start the game with LiteLights, it asks how powerful your computer is and sets itself up to suit, or keeps the creator's recommended settings. Everything is in LiteLights' settings screen (through Mod Menu or a keybind you set), and in Sodium's Video Settings if you have Sodium.

## Install

1. Install Fabric Loader 0.19.3 or newer and Fabric API 0.160.5 or newer for Minecraft 26.3, on Java 25.
2. Drop the jar from the [latest release](../../releases/latest) into your `mods` folder.
3. Optional: Sodium, Continuity (for overlays), Mod Menu, Almanac 1.0.0 or newer (seasons and regional weather). Recommended alongside: LambDynamicLights, LambdaBetterGrass, Visuality and Subtle Effects.
4. Optional, for server owners: the same jar in a dedicated Fabric server's `mods` folder (beside Fabric API) is the seed helper. It only sends players whose LiteLights asks the world's seed, so their seed generation works; none of the graphics loads on the server. You can narrow it to operators or named players, or switch it off, in `config/litelights-seed-helper.json`. A world's seed lets players work out where structures and ores are, so only install it if you're fine with that.

## Compatibility

- Works with Sodium 0.9.2 and 0.9.3 betas, or the vanilla renderer.
- Needs OpenGL 4.1 or newer, or Vulkan. Without Sodium, swaying plants need OpenGL 4.6 (or Vulkan); with Sodium they work on 4.1.
- Doesn't work with Iris. It's a different approach to the same problem, so pick one.
- If you have Particle Rain, Immersive Storms, Weather Glass, Wakes, Big Water or Streams Reflowing, LiteLights turns off its own version of whatever that mod does, and the settings screen tells you which.
- Voxy and Distant Horizons support was dropped in 1.0.8, as LiteLights has far terrain of its own: with either installed, LiteLights leaves their terrain as they draw it and keeps its own far terrain and far shadows off. Punchy! works, outlines included.
- With Almanac (on your game or only on the server), LiteLights' rain, snow, puddles, sandstorms and the rest follow the seasons: snow over winter plains, rain in a winter desert. The visual snow builds up in Almanac's layers and thaws with it, near and on far terrain. With Almanac's regional weather the Dynamic clouds draw its storms where they are, so you can watch one come and go, and far land shows the snow each place has. This needs **Almanac 1.0.0 or newer**; beside an older Almanac those parts are off, and the log and chat say so. Other seasons mods that change vanilla's own rain and snow should work the same way, but I haven't tested them.

**On a Mac:** LiteLights only asks for OpenGL 4.1, so it should run, but I haven't been able to test it on real Apple hardware yet. An earlier version froze on a friend's M4 Max as the world loaded, on both OpenGL and Vulkan. My best guess is the Mac's graphics driver choking on LiteLights' big terrain shaders, but I haven't been able to confirm it. LiteLights now also caps the few shader loops that could run away on bad data (Shader loop safety caps, on by default under General > Compatibility; I don't recommend turning them off), but that isn't confirmed to fix the freeze either. If it freezes for you, these steps help me find the part that breaks:

1. Load a world with the default settings. If it loads, you're all set.
2. If it freezes, quit the game (Force Quit if you have to) and start it again. If the freeze happened while the world's blocks were being drawn, the title screen asks whether to use Minecraft's own block shading for that session: say yes to play straight away (your saved settings don't change). To keep it that way, open LiteLights' settings from the title screen (through Mod Menu, or Video Settings with Sodium), go to General > Compatibility > Mac troubleshooting and turn on **Use vanilla terrain shading**. It takes effect when you close the settings.
3. If it still freezes with that on, the problem isn't in LiteLights' block shading.
4. Either way, send me the log from the run that froze.

For the log, send me the lines in `logs/latest.log` that start with `LiteLights:`, which say how far it got and which parts were in (if one of them is a "slow start-up step" warning, send the lines under it too), along with the macOS hang report from `~/Library/Logs/DiagnosticReports` and any `hs_err_pid*.log` from the game folder.

If something breaks, please open an issue with your log and mod list and I'll take a look.

## Credits

LiteLights is written from scratch. A lot of the ideas came from other mods though, and they deserve the credit for those:

- **ImmersiveVFX** by Shinyflvres: the cached shadow map, forward-shaded effects and biome atmosphere.
- **Big Water** by Tec: large water textures. LiteLights ships Big Water's CC0 water and lava textures.
- **Particular** by Chai: the jump splash.
- **Immersive Storms** by TheDeathlyCow: storm atmosphere.
- **Squall** by comedy: lightning flickering inside the clouds.
- **Tempestra** by Moth_SKW: branching lightning bolts.
- **Weather Glass** by duffopuffo: weather on glass.
- **Particle Rain** by pigcart: rain drips and wall streaks.
- **Wakes** by Goby56: wakes behind boats and swimmers.
- **Falling Leaves** by Fourmisain: leaves falling from trees on the wind.
- **You Must Have The Rain** by AppleNButter: rainbows after the rain.
- **Snow** by IMB11: leaves turning white while it snows, and snow under trees.
- **Swinging Lanterns** by Rilith: lanterns and their chains swinging in the wind.
- **Lava Particles | More** by stepqq: the heat shimmer.
- **LambdaBetterGrass** by LambdAurora: snow on blocks that can't hold it (the visual snow).
- **Snow! Real Magic** by Snownee: snow over block edges.
- **Simple Clouds** by nonamecrackers2: the Dynamic clouds.
- **ProtoManly's Weather** by ProtoManly: the clouds and the weather under them worked out alike for everyone.
- **Create: Sky** by LockBoss: no rain over the clouds' tops, and drifting cloud shadows.
- **Voxy** by MCRcortex and **Distant Horizons** by the Distant Horizons team: far terrain.
- **Voxy SeedGen** by Möbius: far terrain predicted from the seed.
- **Continuity** by Pepper_Bell, which the overlays draw through.
- **LambDynamicLights** and **LambdaBetterGrass** by LambdAurora, **Visuality** by PinkGoosik and **Subtle Effects** by MincraftEinstein, which I recommend running alongside it.

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

Gradle has to run on Java 25 (newer JDKs can't load the build script yet). The build downloads Almanac's jar from its GitHub release to compile against (Almanac stays optional when you play). The jar ends up in `build/libs/`.
