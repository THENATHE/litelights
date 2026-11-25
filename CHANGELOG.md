# Changelog

## 1.1.0+26.3

The first public release since 1.0.0. It brings together everything built since then (the local builds 1.0.2 to 1.0.15, each listed below) and goes out with **Almanac 1.0.0**. If you last played 1.0.0, this is what's different.

### Added
- **Far terrain of LiteLights' own** (Far Terrain page; Experimental, off by default): the land you have visited, drawn far past the render distance (1 to 16 km, 4 km by default) with the game's own block textures and biome colours, in LiteLights' light, haze and fog, with far shadows on it. Out to 512 blocks (at Detail Medium) slabs, stairs, fences, walls, panes, doors, torches, lanterns and chests keep their shapes, and grass, ferns and flowers stand as crossed cards out to 256 blocks. It is made from the chunks your game has already received, so nothing is generated and nothing is asked of the server, and it works in single player and on any server. It is kept inside the world's save in single player (`<world>/litelights/far/`) and under the game folder's `litelights/servers/` on a server, a few kilobytes a chunk. With it off nothing runs, nothing is stored and nothing is drawn.
  - **Far water:** **Full**, the default, carries the near water on past the render distance (the same colour and depth, the land and sky reflected, the sun's glint); **Plain** is a flat colour by depth, a little cheaper.
  - **Far lights:** at night villages, lava, lit caves and other strong lights far away glow as soft points.
- **Seed generation** (Far Terrain page; Experimental, off by default): far terrain also shows land you haven't visited yet, worked out on your computer from the world's seed with the game's own terrain maths: hills, seas, rivers and lakes, each biome's ground and colours, snow and ice, and stand-in trees. Land you visit always replaces it, and if what you visit turns out different (a wrong seed, other terrain mods), LiteLights stops predicting there and says so. In single player it uses the world's own seed; on a server, enter the seed on the page or run `/seed` if the server lets you.
- **The seed helper:** the same LiteLights jar in a dedicated Fabric server's `mods` folder (beside Fabric API) only sends players whose LiteLights asks the world's seed and what makes the server's terrain, so their seed generation works and can tell whether it matches. None of LiteLights' graphics loads on the server. Narrow it to operators or named players, or switch it off, in `config/litelights-seed-helper.json`.
- **Dynamic clouds** (Sky, Clouds, a new choice; Experimental; Fancy stays the default): big clouds of real height built from LiteLights' own cloud field: fair-weather cumulus, towering congestus, layers, a rain deck in rain and thunderheads in storms. They grow, shrink and drift smoothly, reach 8 km by default (Cloud reach, 384 to 16,384 blocks), sort against far terrain, and every LiteLights player on a server sees the same sky. The weather eases in with them: the sky closes over before the first drops, and flying over the clouds leaves the rain below them.
- **Cloud shadows** (Sky, Clouds; on with the Dynamic clouds): the clouds' shadows drift over the land, near and far.
- **Visual snow** (Weather page, a new section; off by default): snow LiteLights draws on the land as it lies, without placing a block. Snow on stairs, slabs, walls, fences, panes and trapdoors, with plants standing in it; snow over block edges; even snow under trees and snow under overhangs; snowy leaves whole or on top only; white grass in snow; more snow in snowstorms; and snow on far terrain. With Almanac it builds up to a second and third layer and thaws with Almanac's own snow; without it, vanilla's one layer.
- **With Almanac 1.0.0's regional weather, storms you can see coming:** the Dynamic clouds draw Almanac's storms where they are, each a dark rain deck growing, travelling with the wind and shrinking away, a thunderstorm towering through it. A storm's cloud arrives before its rain, the rain falls under it and nowhere else, and the sky clears behind it. The rain, snow and thunder you see and hear under them are Almanac's where you stand. With Fancy clouds the weather where you stand follows Almanac's storms too.
- **Far snow by place:** with Almanac's regional weather, far land shows the snow each place has (Almanac's layer rules, up to three layers by default), so a storm's snowfall shows where it fell, not wherever you happen to stand. Close to you far land still takes the snow where you stand, as the near snow does, so the two meet without a line.
- **Mist distance and Mist layers** (Weather, Rain and snow; for Mist style: Mist field): how far the rolling mist reaches (32 to 256 blocks) and up to four layers of it.
- **Cloud height and Cloud distance** on the Sky page (Cloud height is Sodium Extra's when that mod is installed).
- **A Far Terrain page** with every far terrain setting in one place.

### Changed
- **Needs Almanac 1.0.0 or newer for the shared features** (the weather following Almanac's seasons, the visual snow's layers, far snow and the storms). Beside an older Almanac the game starts, those parts are off, and the log and the chat say why. Without Almanac everything else works as before.
- **Snowy leaves whiten the whole block** nearby, and plants standing in snow turn white.
- **Far shadows** stay, on LiteLights' own land past the shadow distance.
- **The Mac troubleshooting switches are gone** from General > Compatibility; every terrain part is always in. Use vanilla terrain shading and the offer after a frozen launch stay.
- The mod is now built from modules (an internal API, the core, far terrain and the clouds), all in the one jar. Nothing you see changes.

### Removed
- **Voxy and Distant Horizons support was dropped in 1.0.8.** LiteLights has far terrain of its own now. With either installed, LiteLights leaves their terrain exactly as they draw it, keeps Far shadows off, and says so on the Far Terrain page, the Far shadows row and the About page. The rows that only acted on their terrain are gone; their saved values stay in the settings file.

### Fixed
- **Rain cutting holes in the mist** (Rain and snow: Enhanced), and **smoke cutting holes in the clouds**.
- **A square shadow under you when flying high** over the clouds in daylight.
- **The whole Mist field brightening and dimming** with the light where you stand, and **haze and ground mist dimming** under a tree or an overhang.
- **Puddles on slopes and edges:** a puddle only forms where none of the columns around it is lower.
- **An older Almanac no longer stops the game.**

### Experimental and off by default
Far terrain, seed generation and the Dynamic clouds are Experimental. Far terrain off, seed generation off, Fancy clouds and Visual snow off stay the defaults, so a game that doesn't turn them on looks and runs as before.

### Known gaps
- **Far terrain, found just before release and not fixed yet:** the coarsest, furthest level can show holes, with the land there drawn as separate flat planes; and with seed generation on, the coarsest level's predicted land can be drawn over land that is loaded right now.
- **Far terrain:** a thin pale straight line can lie on far sea some hundreds of blocks out; near water has fine grain and the floor of deep water under it where far water is smoother; far plants past the Detail distance are left out; far lights are faint at a few hundred blocks; far ice has no reflections; lakes away from sea level can sit up to a voxel off where two levels meet; no cloud shadows on near or far water.
- **Seed generation:** no ravines, no features other than trees (ice spikes, boulders, flowers), no structures (villages appear when visited); trees are stand-ins placed where LiteLights chooses and move when the real chunks arrive; coasts are the least accurate, and filling all 4 km to full detail takes several minutes.
- **The Dynamic clouds:** voxel steps on the heads seen at a low angle; bands on a thunderstorm's tower; a rain deck seen from just over its top at noon is a bright field of steps; cloud shadows darken what is already in shade.
- **Almanac's storms in the Dynamic clouds:** a storm seen from outside is a grey slab with white towers more than a dark mass; no rain curtains under a distant storm; a thunderstorm's anvil is a ragged star rather than a smooth shelf; LiteLights' fair-weather layers aren't thinned near storms; between Almanac's storm top and the drawn cloud's top you are inside cloud with nothing falling.
- **Visual snow:** on snowy ground the snow on blocks can't be told from the snow behind it; far snow has no height (it is a look on the far land), and far stairs and slabs whiten flat; far snow by place is worked out every 512 blocks, so its edges are soft.

### Upgrading from 1.0.0
Every saved setting carries over. Far terrain and its store are new; a far terrain store saved by one of the local builds (1.0.11 to 1.0.15) is read as it is.

## 1.0.15+26.3

A local build on top of 1.0.14, for the author's own testing. It goes with Almanac 0.4.0, now the oldest Almanac it works with.

### Added
- **The Dynamic clouds draw Almanac's storms** (with Almanac 0.4.0's regional weather on, its default): each storm is a dark rain deck where Almanac says it is, growing, travelling with the wind and shrinking away with it, and a thunderstorm towers through it with its anvil spreading on top. You can see a storm coming from kilometres off, its cloud arrives before its rain or snow, the rain falls under it and nowhere else, and the sky clears behind it as it goes. Between the storms are LiteLights' own fair-weather clouds, and everyone on the server sees the same sky: the same storms in the same places. The clouds ride Almanac's wind and stand still when its weather does. A storm an operator places with `/weather` closes in over half a minute, and one cleared away breaks up, instead of popping.

### Changed
- **Under Almanac's storms, the rain, snow and thunder you see and hear are Almanac's where you stand**, under clouds that are really there, so they begin when the storm's rain reaches you and stop when it has passed, with no waiting for a deck to close.
- **Needs Almanac 0.4.0** for everything the two share (0.4.1 recommended: with the weather standing still, a storm placed with `/weather` gets its clouds only from 0.4.1); beside an older Almanac those parts are off and the log and chat say so. With Almanac's regional weather off, on a server without Almanac, or without Almanac, the clouds are as in 1.0.14.

Experimental: far terrain with seed generation, and the Dynamic clouds. Fancy clouds, far terrain off and Visual snow off stay the defaults. With Fancy clouds the weather where you stand follows Almanac's storms as before.

## 1.0.14+26.3

A local build on top of 1.0.13, for the author's own testing. It goes with Almanac 0.3.0, now the oldest Almanac it works with.

### Added
- **Seed generation** (Far Terrain page, a section of its own under the switch; Experimental, off by default): far terrain shows land you haven't visited yet, worked out on your computer from the world's seed with the game's own terrain maths: hills, seas, rivers and lakes, each biome's ground and colours, snow and ice where it is cold, and stand-in trees. No chunk is generated and nothing is asked of the server beyond the seed. Land you visit always replaces it, and if what you visit turns out different (a wrong seed, other terrain mods) LiteLights stops predicting there and says so in the chat. In single player it uses the world's own seed and slows down by itself whenever the game's own server is busy. Not shown yet: ravines, features other than trees (ice spikes, boulders, flowers) and structures; the trees are stand-ins placed where LiteLights chooses. Its rows: the switch, Predicted here, Seed, Server seed and Clear predicted land.
- **The seed helper:** the same LiteLights jar in a dedicated Fabric server's `mods` folder (beside Fabric API) does only one thing there: it sends players whose LiteLights asks the world's seed and a list of what makes the server's terrain, so their seed generation works and can tell whether it matches. None of LiteLights' graphics loads on the server. Sharing the seed is the server owner's choice: narrow it to operators or named players, or switch it off, in `config/litelights-seed-helper.json`; offline-mode servers don't share it unless allowed. On a server without it, enter the seed on the Far Terrain page or run `/seed` if the server lets you; before generating, a notice says what can't be checked or doesn't match.
- **Far water** (Far Terrain page, a new row): **Full**, the default, is the near water carried on as before; **Plain** is a flat colour by depth with the sky's sheen, easing in from the near water over 96 blocks past the render distance. Plain costs a little less (15 to 20 % of far water's own share); both follow the Water page's colours and reflections.
- **Visual snow** (Weather page, a new section; off by default): snow LiteLights draws on the land as it lies, without placing a block, with a row for each part.
  - **Snow on blocks:** stairs, bottom slabs, walls, fences, panes and closed trapdoors carry snow on top, lying on each step and along each top; flowers, grass, bushes, saplings, fences and the like stand in it. As deep as the snow around them, and on as many of them as the snow covers of the ground around. With Almanac it builds up to a second and third layer and thaws with Almanac's own snow; without it, vanilla's one layer. Idea by LambdAurora (LambdaBetterGrass); with its better snow on, the blocks it covers keep its snow.
  - **Snow over edges:** snow weeps a little way down the side of a block under any snow's edge, in a ragged line with short drips. Idea by Snownee (Snow! Real Magic).
  - **Even snow under trees** and **Snow under overhangs:** the dusting fades evenly inward from the snow around a tree, and snow blows a little way in under roofs and eaves, never through walls or doors.
  - **Snowy leaves:** whole block (as before) or tops only, near and far. **White grass in snow:** grass and ferns standing in snow turn white; flowers keep their colours above it.
  - **More snow in snowstorms:** more flakes at every Weather density, most in a blizzard.
  - **Snow on far terrain:** with Almanac, far land, visited or predicted, shows the season's snow as it lies now, in the same layers as the snow near you, and clears with the thaw. It is a look on the far land, without height.

### Changed
- **Needs Almanac 0.3.0** for everything the two share (the visual snow follows its snow layers); beside an older Almanac those parts are off and the log and chat say so.
- **The Dynamic clouds' fields** thin out a little more toward a formation's edge.
- **Credits:** the Credits page, the Clouds and Cloud shadows rows and the Far Terrain page credit the ideas behind the Dynamic clouds (Simple Clouds, ProtoManly's Weather, Create: Sky), far terrain (Voxy, Distant Horizons) and seed generation (Voxy SeedGen).

### Fixed
- **Far water under near water:** from just over the sea a line, and patches from the air, where far water showed under the near water at the render distance; and a brighter band of near water all round the render distance. Far water now steps aside wherever the near renderer really draws water, by vanilla's or Sodium's own rule, and hands over to it without a step.
- **Far water over deep sea** no longer shows the sky or distant land through it where the near water has its dark floor, and the land's reflection in it fades out past the render distance instead of stopping at 256 blocks.
- **Far land read its blocks' materials from the wrong place** (since far terrain came in 1.0.11): every far block counted as plain ground, so far ice took the season's snow and far leaves ignored the Snowy leaves choice. Far ice now stays clear of snow, far leaves whiten whole or on top as chosen, and far land is lit by each block's own material as near land is.

Experimental: far terrain with seed generation, and the Dynamic clouds. Fancy clouds, far terrain off and Visual snow off stay the defaults.

## 1.0.13+26.3

A local build on top of 1.0.12, for the author's own testing. It goes with Almanac 0.2.0.

### Added
- **Cloud shadows** (Sky, Clouds, a new row; on with the Dynamic clouds): the clouds' shadows drift over the land, near and far. Not on water yet: lakes and seas stay sunlit under a cloud, with only the floor of shallow water darkening.

### Changed
- **The Dynamic clouds** (still Experimental; Fancy stays the default) look like clouds rather than stacks of blocks: billowing heads on their tops, rounded sides that curve under at the base, smooth shading over the cubes' steps, and in rain a deck with heads on its top and an uneven underside. Storm anvils are grey underneath rather than black, and night clouds are moonlit blue-grey.
- **They reach 8 km by default**, in wider cubes further out, so from a mountain or a plane the sky goes on to the horizon, over far terrain too, and they sort against far land by depth both ways. Cloud reach now goes from 384 to 16,384 blocks. A Cloud reach of 1,024 saved by 1.0.10 to 1.0.12 is kept as it is; set it higher on the Sky page to see the full sky.
- **Flying over the clouds you leave the rain below them:** no rain or snow falls over the top of the cloud it falls from.
- **Inside a cloud with vanilla's fog** (Fog look set to vanilla's) the view whites out as it does with LiteLights' fog.
- **Thunderheads catch up with real lightning:** a bolt seen while the towers are still building makes them build faster.

## 1.0.12+26.3

A local build on top of 1.0.11, for the author's own testing. It goes with Almanac 0.2.0.

### Added
- **Far water** is LiteLights' near water carried on past the render distance: the same colour, darkening with depth, the sea floor showing through where it is shallow, the sky and the land around reflected, the sun's glint. It used to be an opaque, purple surface drawn over the near water.
- **Blocks in their own shapes far away:** out to 512 blocks at Detail Medium, slabs, stairs, paths, farmland, fences, walls, panes, doors, trapdoors, torches, lanterns and chests keep their own shapes, and grass, ferns, flowers, saplings and bushes stand as crossed cards out to 256 blocks. Past those distances slabs and stairs are whole blocks again and thin blocks and plants are left out.
- **Far lights** (Far Terrain page, on): at night, villages, lava, lit caves and other strong lights far away glow as soft points, hazed by the air. Off, far lights show only as the light on the blocks around them.
- **Stored here** and **Video memory in use** on the Far Terrain page, kept up to date while the page is open.

### Changed
- **The edge of visited land** carries its wall of rock on down past any lit cave, so lava-lit caves no longer show under it from outside.
- **Far terrain fills in faster:** after a jump or on joining a world the land round you is at full detail first, coarse stand-ins elsewhere; a world opened again is complete in about 4.5 seconds, and chunks the game sends again unchanged are no longer stored and meshed again.
- **Clear far terrain for this world** is now **Clear far terrain** (its description says it is for this world and dimension); with no world's far terrain open it does nothing.

### Fixed
- **Far land was black on Vulkan from about y 700 up.** The haze's height formula overflowed looking far down from high up; it is now written so nothing overflows, and gives the same haze everywhere else.
- **Coarse far tiles no longer flash for a frame** over far and near land while chunks round you are stored.
- **The page's status after Clear** showed the old numbers until the page was opened again.
- **Far water over open sea** was drawn over the near water out to the coast; the near water now reaches the render distance and far water starts past it.
- On OpenGL with an AMD card the game could stop a few seconds into a far view with far water and lights (`command stream overflowed`).

## 1.0.11+26.3

A local build on top of 1.0.10, for the author's own testing. It goes with Almanac 0.2.0.

### Added
- **Far terrain** (Far Terrain page; Experimental, off by default). LiteLights' own far terrain: the land you have visited, drawn far past the render distance with the game's own block textures and biome colours, in LiteLights' light, haze and fog, with far shadows on it. It is made from the chunks the game has already received, so nothing is generated and nothing is asked of the server; it works in single player and on any server, and fills in as you travel. Land visited while it is off isn't recorded. Snow layers and carpets show on the block under them, and glowing blocks glow.
- **Its rows:** Distance (1 to 16 km, 4 km), Detail (Low to Ultra, Medium), Video memory (Auto or a fixed amount), Background threads (Auto or a number), a status line with what is stored for the world, and **Clear far terrain for this world**. In single player it is kept inside the world's save (`<world>/litelights/far/`), so it goes with the world's backups; on a server, under the game folder's `litelights/servers/`. A few kilobytes a chunk.
- **Not there yet:** plants, fences, torches, panes and other thin blocks far away (slabs and stairs are whole blocks, so far forests are leaf blobs on bare ground); far water is an opaque surface, not see-through; far lights glow on their blocks but aren't points of light. Those are the next rounds.

### Changed
- With far terrain off nothing runs, nothing is stored and nothing is drawn: the game looks and runs as in 1.0.10.

## 1.0.10+26.3

A local build on top of 1.0.9, for the author's own testing. It goes with Almanac 0.2.0.

### Added
- **Dynamic clouds** (Sky, Clouds, a new choice; Experimental). Big clouds of real height, built from LiteLights' own cloud field: fair-weather cumulus of every size, towering congestus, layers, and in rain a deck closing over the whole sky, with thunderheads in storms. They grow, shrink and drift smoothly, rising and widening out of the cloud already there instead of popping in, and every LiteLights player on a server sees the same sky. No cloud comes lower than vanilla's cloud height. Fancy stays the default.
- **The weather eases in with the Dynamic clouds:** while they are chosen, the rain, snow and thunder LiteLights shows and plays ease in and out with their clouds, and so do sandstorms, haboobs and the snow gathering on leaves: the sky closes over in about half a minute, the first drops fall under a darkening sky, and in single player the clouds gather before the rain. With Almanac they gather only once the rain has begun, as on a server, because Almanac's rain reaches some climates and not others: where it doesn't reach you, the sky stays clear. The server's weather is untouched, so with Almanac the server can lay snow on the ground a few seconds before the first flake LiteLights draws. With any other cloud choice the weather is shown exactly as in 1.0.9.
- **Cloud reach** (Sky, Clouds): how far the Dynamic clouds reach, 384 to 1,024 blocks.

### Changed
- Cloud layers is greyed out with the Dynamic clouds, which have no layers.

## 1.0.9+26.3

A local build on top of 1.0.8, for the author's own testing. It goes with Almanac 0.2.0.

### Changed
- **Works with Almanac 0.2.0, and needs it:** Almanac 0.2.0 replaced its seasonal biome copies with a climate table, and LiteLights now reads the season's climate from it. Rain and snow by season, snowy land in winter, the aurora over cold land, dry places and their sandstorms, and weather on glass follow Almanac's seasons again. Almanac 0.2.0 is the oldest LiteLights works with.
- **An older Almanac no longer stops the game:** beside one, LiteLights' weather goes by each biome's own climate, as without a seasons mod. The chat says so once when you join a world, and the log as the game starts. Update Almanac to turn the seasons back on.

### Removed
- Support for Almanac before 0.2.0, including far snow's timed thaw and forming (nothing has drawn far snow since 1.0.8).

### For developers
- An interleaved A/B timing tool (`-Dlitelights.ab=…`). It flips one option every few frames in the same scene and reports frame time and the GPU timers for each side. Off, it costs nothing.

## 1.0.8+26.3

A local build on top of 1.0.7, for the author's own testing.

### Removed
- **Voxy and Distant Horizons support was dropped in 1.0.8.** LiteLights is getting far terrain of its own, and keeping up two other far terrain mods beside it would cost every later change. The code is in LiteLights' history up to 1.0.7, so either could come back later. With Voxy or Distant Horizons installed, LiteLights now leaves their terrain exactly as they draw it (their own fog and light), keeps Far shadows off, and says so on the Far Terrain page, the Far shadows row and the About page. If you used either for far land, it is unshaded by LiteLights until LiteLights' own far terrain arrives.
- The rows that only acted on their terrain: Far terrain and Seasonal snow on far terrain (Fog), Water on far terrain, Far caustics, Far depth colour and Far edge foam (Water), and Calculate distance (Shadows). Saved settings load as before; the values of the removed rows stay in the file.

### Changed
- **Far shadows** (Shadows and Far Terrain pages) stay, on LiteLights' own land past the shadow distance, unchanged.
- The mod is now built from modules (an internal API, the core and a far terrain module), all in the one jar. Nothing you see changes.

## 1.0.7+26.3

A local patch on top of 1.0.6, for the author's own testing. It goes with Almanac 0.1.6.

### Added
- **Mist distance and Mist layers** (Weather, Rain and snow; for Mist style: Mist field). Mist distance sets how far the rolling mist reaches over the ground, 32 to 256 blocks (112 as before). Mist layers stacks up to four layers of it, the first always on the ground, each further one on the one below and a little thinner.
- **Plants standing in snow turn white** (part of Snowy leaves and ground): grass, ferns and flowers with snow layers on two or more sides, as snowy grass, whether LambdaBetterGrass draws its snow through them or not.

### Changed
- **Snowy leaves whiten the whole block** nearby: sides and underside with the top, where the snow used to creep down from the top edge. Leaf blocks one to three under the top of a canopy take more of it than before.
- **Far snow takes leaves and plants whole:** where snow lies on a far column, the sides of its leaf blocks, and of grass and flowers standing in it, turn white with their tops.
- **Far snow melts as it forms, by Almanac's cover (with Almanac 0.1.6):** it shows the season's snow between Almanac's bare lines and its snow lines on the share of columns the cover says, scattered column by column, and animates nothing of its own. The thaw is no longer a timed change in patches, so far land, loaded land and land that loads lose their snow together. With an older Almanac nothing changes.

### Fixed
- **Snow on far land under water** (Voxy): no snow is painted where Voxy's water lies over the land, or anywhere below the sea's surface.
- **Far lamps turning green in rain:** with Almanac, far ground lit by a lamp was read as brightly orange in the dim light of rain (and tinted like a leaf, green in most seasons), because its colour was taken against the sky's light alone. It is now taken against the light the far mod lit it with, block light included.
- **The whole Mist field brightening and dimming with the light where you stand:** it was lit by the light level at the camera, so a torch or a tree overhead changed all of it at once. Its light now comes from the open sky around the camera and eases over a few seconds.
- **Haze and ground mist dimming under a tree or an overhang:** how much sky the camera sees is now the most found at the camera and a few blocks around and above it, so only a cave or a closed room darkens them.

## 1.0.6+26.3

A local patch on top of 1.0.5 for far snow, for the author's own testing. It goes with Almanac 0.1.5.

### Changed
- **Far snow forms on scattered columns, the way vanilla's snowfall lays it,** instead of in spreading blobs. Each column has an evenly spread threshold of its own, so the share of white columns is Almanac's snow cover, the same share the land near the player holds (with Almanac 0.1.5). Further out than a column can be told apart, the land whitens evenly by that share. The thaw still goes in patches.
- The far snow's progress moves smoothly between the sixteenths of a layer Almanac sends, instead of in steps.

### Fixed
- **Far snow starting again just as it finished.** When a snowfall covered its ground, the cover stopped counting as part way and the far snow fell back to a timed guess (about 40 %), then filled in a second time. It now goes to full cover and stays.

## 1.0.5+26.3

A one-fix local patch on top of 1.0.4, for the author's own testing.

### Fixed
- **Rain cutting holes in the mist (Rain and snow: Enhanced):** LiteLights' own rain, splashes, drips, smoke and other plain particles were drawn in vanilla's translucent particle layer, which writes depth, so the mist behind each raindrop was cut out round it, as smoke used to do to the clouds. They now have a layer of their own with vanilla's look and no depth writes, and blend over whatever is behind them.

## 1.0.4+26.3

A third local patch for the author's own testing: unit tests, the offline shader compile, and a look at the game (far lamps and far depth colour on Voxy and Distant Horizons, the settings screens). It includes 1.0.2 and 1.0.3.

### Added
- **A Far Terrain page:** every far terrain setting in one place, under headings named after the pages they live on (Fog, Shadows, Water). They are the same settings: a change on either page shows on the other. LiteLights' own screen only; Sodium's Video Settings keeps them on their pages.
- **Cloud height and Cloud distance** on the Sky page. Cloud distance is Minecraft's own setting (the one Sodium's Video Settings shows). Cloud height is Sodium Extra's when that mod is installed, read and written in place, and otherwise LiteLights' own (64 to 512 blocks, 192 by default).
- **Far snow follows Almanac 0.1.4's cover:** with Almanac 0.1.4, far snow that is forming shows on the share of the land the snowfall has reached, the same share as near land, and stays there while the snowfall pauses or after rejoining, instead of running on a clock.

### Fixed
- **Far lava, glowstone and lanterns looked dull and blue at night:** blocks that give off light are left out of shadows and the night tone nearby, glow hotter and bloom. Far ones were shaded and night-toned like the ground around them. With the far mods' own data (1.0.3), a far block at light level 15 is now treated the same way, and on Voxy it gets bloom.
- **Far depth colour on Voxy was full of streaks and blotches:** Voxy draws water without depth, so its surface was guessed from the shore around it. Voxy's water pass now records its surface's distance, and far water is found and measured from that. It also replaces the before-and-after colour comparison that found the water.
- **Smoke cutting holes in the clouds:** vanilla draws clouds after particles, which have already written their depth, so a puff of smoke in front of a cloud cut its whole square out of it. LiteLights' clouds are now drawn before the particles (classic transparency only).
- **Puddles on slopes and edges:** a puddle only forms where none of the eight columns around it is lower.

### Changed
- **The Mac troubleshooting switches are gone from General > Compatibility.** They are hidden on both screens and no longer read, so every terrain part is always in. Saved values stay in the file. The safety net that offers vanilla terrain shading after a frozen launch still works.

### Not found
- **Voxy's far grass colour differing with Far terrain on:** not reproduced in the test scene (no Almanac there). With Almanac, far grass is tinted toward the season's colour by a rule that guesses grass from a pixel's colour, which is the likely cause.
- **Lamp-lit far ground** is a little bluer at night with Far terrain on than as the far mod draws it. That is the night tone, by the same rule as near ground at the same light levels; only the light-giving blocks themselves were treated differently.

## 1.0.3+26.3

A second local far terrain patch for the author's own testing: unit tests, the offline shader compile and a look at the game on the Voxy and Distant Horizons stacks, no full test matrix. It includes everything in 1.0.2.

### Added
- **Voxy's and Distant Horizons' own face and light for each far pixel:** LiteLights now changes the text of both mods' terrain shaders as they load them, so each opaque far pixel also says which way its face looks and how much block light it had when the far mod saw it. Voxy writes them into a texture of LiteLights' beside its colours (with its sky light too); Distant Horizons carries them in the alpha of its opaque colours, which LiteLights puts back to 1 before Distant Horizons uses them. Far terrain is then lit with a real face, where LiteLights used to work one out from the depths around the pixel, and with its real block light, so torch-lit and lava-lit far land keeps its light under the sky's shadows and the night tone. Far snow lands on real top faces.
- Each change looks for the exact shader line it replaces. With another Voxy or Distant Horizons version whose shaders differ, nothing is changed and far terrain is lit as in 1.0.2. `-Dlitelights.farData.voxy=false` and `-Dlitelights.farData.dh=false` switch each off.

### Fixed
- **Far shadows marched from the sea floor:** before translucent terrain the depth under water is the floor's, so far shadows for water at sea level were worked out from down there. They are now worked out from the water's surface.

### Known, not fixed here
- With Voxy, LiteLights' own water turns see-through toward the edge of the loaded land (to meet Voxy's water), so the sea floor and the shadows on it show through there, most with a low sun.
- Distant Horizons has room for the face and the block light only; its sky light is still taken as full. With LiteLights' fog set to Vanilla, Distant Horizons' own fog blends into that alpha and the data isn't read.
- Everything listed under 1.0.2 still stands.

## 1.0.2+26.3

A local patch for far terrain, built for the author's own testing: unit tests, the offline shader compile and smoke runs only, no full test matrix.

### Fixed
- **Far water flickering as you move (Voxy and Distant Horizons):** with Water on far terrain on, the whole far water surface could flicker while walking or looking around. Far water is now a calm mirror: its waves, which are smaller than a pixel out there, fade out between 96 and 256 blocks, the sun's glint widens with distance, and far shadows no longer shade the reflection. Land is no longer reflected in Voxy's far water (the trace that found it flickered); the sky and the sun still are.
- **Dark patches under Voxy's water with Far terrain on:** Voxy draws its water without depth, so its water was lit with the sea floor's slopes and the sea floor's far shadows, which showed through as patches that looked half drawn, or like cloud shadows on the sea. Water is now found first and lit as flat water under the open sky.
- **Bloom showing through blocks with Distant Horizons:** Distant Horizons' fade pass clears the picture's alpha to 0 wherever its own clouds lie, even behind blocks, and LiteLights read 0 as its brightest glow mark. Glow marks no longer use 0, and 0 is never read as a glow.
- **Distant Horizons' clouds casting far shadows:** its own clouds are drawn into the textures LiteLights shades, so they were lit, fogged and marched as land. Everything above the top of the world now casts no far shadow and gets none, and is never taken for snow or water; it keeps LiteLights' light and haze.
- **A pale ring where the sun's shadows hand over to far shadows:** over the last tenth of Reach the sun map's shadows thin out while far shadows fade in, and the two were multiplied, so halfway through the band a shadow was lighter than on either side of it. The far shadow now only adds what the thinned-out map left out.
- **Natural and Enhanced water against Voxy's water:** near the border of the loaded land, LiteLights' water colour now hands over to vanilla's (the texture times the biome's tint), which is what Voxy draws beyond it, as its depth look already did.

### Added
- **Lights on far terrain keep their light at night:** Voxy and Distant Horizons keep each block's light from when they saw it, so torches, lava and lit windows are already in their terrain. LiteLights used to shade and night-tone all far terrain as if only the sky lit it, which dimmed them. Whatever is brighter than the sky alone could make it is now left as the far mod drew it.

### Known, not fixed here
- Far shadows on Voxy's and Distant Horizons' terrain still come from what is on screen, so they fade at the screen's edges while turning, in Advanced too, and trees and buildings out there cast none. That needs the far terrain's own geometry.
- With Distant Horizons, its fade pass also sets alpha to 1 on nearby ground, which may take the glow off torches and lava there. Not checked.

## 1.0.1+26.3

### Changed
- **Far snow forms as fast as the snow near you, with Almanac 0.1.3.2:** when a snowfall whitens the land, the far terrain now takes Almanac's own snow-forming time (about ten minutes at the default random tick speed, and quicker or slower with the `randomTickSpeed` game rule), less the minute or two of snowfall before the far land starts to change, so near and far land finish whitening together. Before, far land took Almanac's whole thaw time to whiten (two in-game days by default) and lagged far behind. Melting still takes the thaw time. With an Almanac 0.1.3 or 0.1.3.1 server, far snow forms over Almanac's suggested 12,000 ticks; with 0.1.2 nothing changes. Almanac 0.1.2 and later all still work.

### Fixed
- **A square shadow under you when flying high:** flying over the clouds in daylight, a large dark square could lie on the land below, off toward where your shadow would fall, and follow you around. It came from Creature shadows, whose shadow map covers 48 blocks round you and reaches only so far below: land deeper than that, from about 70 blocks under you early in the morning to about 190 at noon, was counted as in shadow. That land is now lit unless a creature in the map really shades it. It showed most with a long render distance or Voxy and Distant Horizons, where the land that far down is in view.

## 1.0.0+26.3

### Added
- **A safety net for the Mac freeze:** if the game ever stops responding for over 10 seconds while it draws the world's blocks (which happens on some Macs), the next launch asks on the title screen whether to use Minecraft's own block shading for that session. Everything else in LiteLights stays on, and your saved settings don't change; the launch after that starts as usual. To keep it, turn on Use vanilla terrain shading (General page, Mac troubleshooting).

### Changed
- **Turning shadows off gives their video memory back:** after Shadows (or Creature shadows) have been off for a few seconds, the shadow maps and shadow shapes are freed: about 200 MB at Sharpest and 590 MB at Ultra. Turning them back on fills the shadows in again over a moment. Shadows paused under water keep theirs.
- **Sodium's pages grey rows at once:** in Sodium's Video Settings, a row that depends on another setting now greys the moment you change that setting, as on LiteLights' own screen, instead of after Apply. Turning particles off greys Enhanced fire, its rows and every storm's cloud particles; turning Enhanced fire off greys Ember veins and Burn marks.
- **Overlay exports keep the next reload quick:** exporting an overlay pack no longer throws away the overlays already built, so the next resource reload reuses them (about 35 ms at 256x instead of about 3 s).
- **Overlay builds use less memory with very large packs:** textures larger than your Largest overlay size are scaled down as they load instead of being held at full size for the whole build, and identical overlay sets in one build are drawn once. The overlays look the same.
- **Weather particles take much less of each frame.** LiteLights now works out ahead of time, between ticks, which particles could be on screen, and builds the rest of each frame's particles on one or two helper threads while the game draws the world, instead of all on the main thread. In a blizzard at the highest particle settings the main thread's share drops from about 8 ms a frame to about 2, and at the defaults from about 0.7 ms to about 0.3; a thunderstorm at the highest settings from about 5 ms to under 1.5. Nothing looks different: the same particles are drawn in the same order, also while you fly fast and turn quickly. Uses two helper threads on CPUs with 8 or more threads, one with 6 or 7, none on smaller CPUs.
- **The particles no longer make a hitch worse.** After a hitch (loading chunks, joining a world), and on slower CPUs at the highest settings, the game used to wait for the particle thread on every catch-up tick; now the particles skip that tick instead (they pause for a moment) and the game carries on.
- **Snow costs less to spawn:** in a blizzard at the highest settings the main thread spends about a third of what it did placing new flakes, with the same number of flakes.
- **Hanging lanterns swing in blizzards and sandstorms** as hard as the wind there blows, not only in rain and thunderstorms.
- **Snowy leaves follow the world's snow with Almanac:** standing in mild land next to a snowing taiga, the taiga's canopies now frost while it snows there, as Almanac's own snow does, instead of starting from nothing when you walk in.
- **Smoother far snow when the season turns:** the far-terrain snow's work on a change of season now runs in the background; the game no longer pauses for a few milliseconds (up to about ten on a busy computer) each time the season changes or you travel a kilometre. While a world's far map arrives, the far grass and leaf colours are worked out at most four times a second instead of for every piece of the map.
- **Weather in modded dimensions:** rain and snow, their fog and storms, puddles, weather on glass, snowy leaves and lightning in the clouds now all agree on whether a dimension has weather (where the game has weather, under an overworld-style sky). The overworld, the Nether and the End are unchanged.

### Fixed
- **The material texture now really retries after a failed build:** the log said "retrying in 5 s" but nothing did, so water, ice, glint, ores, puddles, sway and weather on glass could stay without their materials until the next resource reload or world change.
- **Creature shadows that can't get their video memory now switch off with a note and a Retry on their row,** instead of stopping the game.
- **A stray shadow after changing dimension:** a shadow shape built for the dimension you just left could, rarely, land in the new one.
- **Translucent blocks are always drawn,** even if one of LiteLights' steps before them fails.
- **One broken block model in a resource pack no longer turns every overlay off.** A model the game can't load either (an upper-case name, a face without a texture, a value of the wrong kind) leaves out only that block's overlays, which the Overlays page lists under Left out; the game draws that block with the missing texture as before. The log shows the problem once, with the details.
- **One broken LambdaBetterGrass file in a resource pack no longer turns edge variety off for the session:** that file is skipped, as LambdaBetterGrass itself skips it.
- **An overlay build that takes too long now says so:** the Overlays page shows it failed, with Retry, instead of claiming overlays that weren't loaded, and a build that finishes after the reload stopped waiting isn't reported as built.
- **A failed overlay export is logged** and leaves no half-written `.zip.part` file in your resource pack folder.
- **Your settings file is never replaced by the defaults after a failed read.** If `litelights.json` can't be read (locked by a virus scanner or a sync tool, say), or is broken and can't be moved aside, LiteLights runs on its defaults for that session without saving, and the log says why, so the file keeps your settings for the next start.
- **Unreadable overlay settings are never lost:** if your overlay settings can't be read (a typo in a hand edit), the defaults are used, the Overlays page now says so and where the unreadable copy is kept in `litelights.json`, and a second unreadable load keeps its copy beside the first instead of replacing it.
- A failed overlay build is logged once instead of twice.
- **Far depth colour on Voxy** (Water page, Experimental): far water now looks like a deeper version of the same water, darkening toward the same deep blue as LiteLights' water nearby, instead of turning a dull grey with the sea floor showing through. It grows in gradually past the edge of the loaded land, so the stepped band that followed the loaded chunks at grazing angles is gone.
- **Far caustics on Voxy:** the rippling light on shallow far water no longer breaks up into scattered bright pixels far away; it softens and fades out before it gets smaller than a pixel.
- **Far edge foam on Voxy:** foam no longer outlines trees, trunks and banks that stand in front of the water or above it on screen, especially when looking across water at a low angle; it only draws where far land meets the water at the waterline. It no longer draws a thin line along the edge of the loaded land over the sea either.
- **The world you left no longer stays in memory:** leaving a world for the title screen kept parts of it (its chunks, entities and biomes) in memory until the next world was drawn, and far snow kept the overworld while you were in the Nether. All of it is now let go at once, and the particle helper threads stop at the title screen.
- **A change of season could pop on far terrain** instead of melting or snowing in in patches, when it came while far snow was still filling in the map.
- **Weather before Almanac's first season sync:** while Almanac had nothing to say yet (just after joining, outside the overworld, or with Almanac only on your game), LiteLights asked the wrong place and could get the weather wrong at biome borders and at the edge of the loaded land, and could keep that answer for hours. It now uses each biome's own weather until Almanac answers, and picks Almanac's answer up as soon as it comes.
- **Snowy leaves far away** could get a faint speckle of snow the moment snow started to settle, and didn't fade out fully where the snowy leaves end about 128 blocks away: snow now comes in smoothly from none at every distance.
- **Weather on glass and the desert heat shimmer with Almanac** now tell the kind of place by the season's version of the biome, like the rain and snow do.
- Far snow no longer runs in modded dimensions, where Almanac has no map.

## 0.9.10+26.3

### Added
- **Seasonal snow on far terrain** (Fog page, Far terrain, on by default; shown only with Almanac installed, and needs Almanac 0.1.2 or later): with Almanac on your game, Voxy's and Distant Horizons' far terrain follows Almanac's season instead of keeping the snow it had when the far mod last saw the land. Far plains turn white in winter once it has actually snowed, and green again in spring and summer even where the far mod saw them under snow; deserts never get snow, mountain tops keep theirs all year, and snowy biomes lose theirs in summer when Almanac's server lets them. When the season turns, the far snow comes and goes in block-shaped patches that spread and join, over as long as the snow near you takes to thaw, and a new snowfall whitens the far land as it falls. Land Almanac's map hasn't reached yet stays as the far mod drew it. Without Almanac nothing changes.
- **Far grass and leaves follow the season** (part of Seasonal snow on far terrain, with Almanac): Voxy and Distant Horizons keep the grass and leaf colours of the day they last saw the land, so far forests stayed summer green into the fall and far meadows kept fall's olive in spring. Far grass and leaves are now tinted toward Almanac's colours for the season wherever Almanac's map reaches, so the far land meets the land near you without a seam of colour. Sand, dirt, stone, wood, spruce forests and the deserts and badlands are left as the far mod drew them.
- **Ore sparkle motion** (Lighting page, Glint section, right under Glint on ores and crystals, also in Sodium's Video Settings): what makes the sparkles on ores and crystals twinkle, worded like Water sparkle motion. **Gentle** (the default): they twinkle slowly by themselves and stay as they are while you look around or walk. **Still**: the sparkles shift only as you move, as they did in 0.9.9, now slowly, and now and then one flares by itself. Amethyst, diamond and emerald blocks follow it too. Greyed out while Glint on ores and crystals is off.

### Changed
- **Almanac 0.1.3 support** (Almanac 0.1.2 still works): with Almanac 0.1.3 on the server, far snow forms in patches over the same time as the snow near you does (Almanac 0.1.3 builds new snow up over its thaw time instead of laying it at once), and leaving and joining a world, or coming back from the Nether, part way through a thaw or a snowfall picks the far land's change up where it is, the same for every player, instead of jumping to its end. LiteLights asks Almanac 0.1.3 itself what falls where, rather than working it out from Almanac's rules. Almanac older than 0.1.2 is no longer accepted: the game says so at startup.
- **More snowflakes:** snow falls about half as thick again at every Weather density and particle amount, with Enhanced and Vanilla+ alike, and blizzards (thunder where it snows) show about 1.7 times the flakes they did. Blizzards had been held back by the particle limit and now show every flake they ask for; the limit for weather particles is higher at every setting, so a thunderstorm now gets all the rain it asks for too (about a fifth more drops, more at the highest settings). Snowflakes no longer fill part of the view as big pale squares when one drifts right past your eyes: they fade out within the last block or two. Snow mist, the storm fog, the Mist field, sandstorms and ordinary rain are unchanged.
- **Ore and crystal sparkles are about ten times calmer:** a sparkle now takes a couple of seconds to come and go, and each one flares by itself every 20 to 50 seconds instead of every 2 to 5. With Still, moving the view swaps the sparkles a tenth as fast. They still stop with the pause screen.

### Fixed
- **Sandstorms in summer deserts with Almanac:** while it rains elsewhere, a desert or badlands in its dry season (spring to fall) now has its sandstorm, and a haboob in a thunderstorm, with Almanac on your game or only on the server (Almanac 0.1.2 or later). Plains, beaches and oceans in their dry mid summer get the darker sky but never sand.
- **Weather follows the seasons with a seasons mod.** With Almanac, LiteLights drew rain over snowy plains in winter and a sandstorm over a desert's winter rain, because it asked each biome for its all-year climate. Rain and snow, their mist and splashes, weather on glass, puddles, rainbows, storm and snow fog, blizzards, sandstorms, drifting sand, the desert's heat shimmer, snowy leaves and the aurora's strength now follow the season and the region: snow in winter plains, rain in a winter desert and no sandstorm there, rain on snowy plains in summer. This works with Almanac on your game or only on the server, and with other seasons mods that change vanilla's own rain and snow (such as Serene Seasons and Fabric Seasons, which haven't been tested). Without a seasons mod nothing changes.

## 0.9.9+26.3

### Added
- **Falling leaves on the wind** (Particles page, Falling leaves, on): falling leaves now ride LiteLights' wind, the same one the rain and the swaying grass follow. On a calm day they fall nearly straight down with their gentle sway; when the wind picks up, and in storms, more of them let go, in gusts you can see roll through the trees, and they are carried sideways. Cherry petals drift with the wind instead of always the same way. Spruce trees, which never dropped any, now drop leaves in their own colour too, and so do modded trees built on plain leaves. The extra leaves follow your particle amount. With the Falling Leaves mod installed, LiteLights leaves falling leaves to it. Idea from Falling Leaves by Fourmisain.
- **Leaves under trees** (Vegetation page, Forest floor, on): the small leaves that float on water near trees now lie on the ground under and around trees too, on grass, dirt, podzol, moss and paths, coloured like the tree above them: green under an oak, pink under a cherry, a few of them yellowing or brown. Ground that already has leaf litter, snow, sand, water or a roof over it is left as it is. It has its own switch; leaves on water keep theirs (Water page, Floating leaves).
- **Puddles** (Weather page, Weather details; on by default): puddles form on open ground in the rain, as thin sheets of water like the film the ocean waves leave on the beach, in the colour of your Water colours setting, catching the sky, with small rings where the drops land. They lie in the same low patches every time, on grass, dirt, paths, stone, gravel, bricks, planks and the like, never under a roof, an overhang or a tree, and never on sand, snow or farmland. They show within the first minute of rain and are full after two and a half, bigger and sooner in a thunderstorm; after the rain they stay for a minute, then shrink from their edges and dry up over about four. None form where it snows or never rains. Joining a world in the rain, they are already there. The weather preview shows a rain's puddles at once.
- **Rainbows** (Weather page, Sky events; on by default): now and then, when rain or a thunderstorm clears during the day, a rainbow appears opposite the sun, with a fainter second bow outside it and a darker band between, grows in, stays a couple of minutes and fades. It is likelier after a thunderstorm; about a third of the rains that clear by day bring one you can see. Like real ones, they show best in the morning and late afternoon: with the sun high, most of the bow is below the horizon. Everyone on the world sees the same rainbow at the same time. Behind clouds, not in the Nether or the End, and not where it snows or never rains. The Sky event preview has a Rainbow too (it previews noon with it). Idea by AppleNButter (You Must Have The Rain).
- **Snowy leaves and ground** (Weather page, Weather details; on by default): in biomes where it snows, the leaves of trees open to the sky frost over while it snows: the brighter parts of the leaves turn white first, on the tops of the leaf blocks, then down their sides, with the gaps between the leaves left as they are, like snow caught in the canopy. Spruce and other conifers hold more snow than oak and birch, and the leaves under the top of a tree take less. The bare grass and dirt under the trees, where vanilla leaves no snow, takes a light, patchy dusting, thinner near the trunk; it covers the leaves under trees there. The snow builds up over the first couple of minutes of snowfall, stays for half a day after, and thaws over about ten minutes of a long dry spell. None under roofs, outside snowy biomes or in the Nether and the End. The weather preview's Snow shows it at once on any trees. Idea by IMB11 (Snow).
- **Shore water** (Water page, with the Ocean waves rows after Shore run-up, also in Sodium's Video Settings): how the water the ocean waves run up the beach looks. **Water** is the clear sheet of sea water it has always been (the default, so nothing changes unless you pick another). **Foam** turns it into foam all the way up the sand and all the way back, thinning into patches as it drains. **Foam in, water out** runs up as foam and, as the wave turns to slide back, the foam clears over a fraction of a second into the clear sheet, which drains as before. The foam follows Wave foam's thickness, stays on the wet sand the wave has reached and costs nothing measurable.
- **Lightning in the clouds** (Weather page, Storms; on by default): in a thunderstorm, flashes flicker inside LiteLights' clouds, lighting a patch of both cloud layers from within, often twice or three times in quick succession and now and then crawling across the sky as they flicker. Most come with no bolt at all, and the far ones glow low over the horizon. Each flash lights the fog, the storm haze, the sky and the land a little, from its direction, and vanilla's own bolts light the clouds above them. The flashes are worked out from the world's seed and the game time, so everyone on a world sees the same flash at the same moment. With vanilla's **Hide Lightning Flashes** on (Accessibility), nothing brightens the screen or the land and only a faint glow is left in the clouds. Idea by comedy (Squall).
- **Branching lightning** (Weather page, Storms; on by default): vanilla's lightning bolts are drawn as jagged strikes from the cloud base to the ground, with forks that thin and fade, a bright core and a soft glow, flaring at each strike and dimming between strikes. Same place and timing as vanilla's bolt, but seen up to 256 blocks away (vanilla's stop at 64) and no longer hidden when its foot is behind a hill; off draws vanilla's. Idea by Moth_SKW (Tempestra).
- **Swaying lanterns** (Vegetation page, Sway, on): hanging lanterns swing gently in LiteLights' wind, barely on a still day, harder and gustier in rain and thunderstorms, each a little out of step with its neighbours. A lantern on a chain swings with its whole chain from the top, so the two never come apart, and a long chain swings slower and wider than a short one. Soul and copper lanterns and modded lanterns swing too; standing lanterns stay put. Hanging signs swing with their text, on ceilings, on chains and on walls, and loose chain ends sway. The light stays where the lantern hangs. It works on Sodium and without it, also on Macs, and stops on the pause screen. Idea by Rilith (Swinging Lanterns).
- **Heat shimmer** (Lighting page, on by default): the air wavers gently over lava, fire, lit campfires, furnaces and magma blocks, and over open desert and badlands ground around noon in clear weather. It is strongest just above the heat and fades a few blocks up, with slow rising ripples; over a Nether lava sea it shows most. Nothing past about 48 blocks, and never on your hand or the HUD. It stops with the pause screen. Idea from Lava Particles | More's heat waves.
- **Glint on ores and crystals** (Lighting page, Glint section, on by default): the ore in ore blocks twinkles in its own colour (gold, diamond cyan, emerald green, redstone red, lapis blue, copper, iron, a faint grey-white for coal, white quartz, Nether gold) while the rock around it stays as it is. It needs no sunlight: the sparkles catch a light from your side and flare now and then on their own, so ores are easier to spot in a dark cave, and they show from most angles. Amethyst (blocks, budding amethyst, clusters and buds) and diamond and emerald blocks sparkle too, more sparsely. Only the faces you can see sparkle, and it fades out from about 24 to 48 blocks. Which part of a texture is ore is worked out from your resource packs each time they load, so it follows a pack's own ores; modded ores in the `c:ores` tag drawn on stone, deepslate or netherrack count too. The Ground glint sliders tune it.

### Changed
- **Two more recommended mods:** Visuality by PinkGoosik and Subtle Effects by MincraftEinstein, for more small particle details alongside LiteLights'. They're listed on the About page (with whether they're installed), in Getting started, on the Particles page and in the credits.
- **Glint on sand and stone no longer covers ores,** which have their own row now; it is unchanged otherwise and stays off by default.
- **A dimmer aurora over spruce forests:** the dappled forest, taigas and other spruce and pine forests too mild for snow now get the aurora too, at about half the strength it has over snowy land (as a taiga already had). The old growth pine taiga goes up from a quarter to half; modded biomes in the Conventional coniferous tag count as well.

### Fixed
- **The aurora fades in and out at biome boundaries** instead of vanishing the moment you step out of a cold biome: how strongly it shows now follows the land around you, changing over about 30 blocks either side of the boundary, and eases over a few seconds, so flying or teleporting across fades too.
- **Ocean waves go round the corners of blocks.** The water running up over a low island or beach stopped short of each outer corner, leaving a small dry triangle on every corner, and in inner corners the surf and the wash up the sand fell short or came out weaker. The waves, their foam and the wet sheet now follow the blocks' outline round outer and inner corners just as they do along a straight edge, on islands, stepped beaches and beside walls. Straight shores look as before.

## 0.9.8+26.3

### Added
- **A rare big meteor on meteor shower nights:** now and then (about once in two or three shower nights) a meteor twice the size of the others crosses the sky, slower and with a trail three times as long, its hot white head fading into red or blue. The Meteor shower preview shows one every twenty seconds or so.
- **First-time setup:** the first time the game starts with LiteLights, a small panel over the title screen asks how powerful your computer is: Integrated graphics / Old computer, New non-gaming PC, Older or budget gaming PC, or Top of the line gaming PC. Point at an answer to see who it's for, with example processors and graphics cards. Each answer sets LiteLights' quality and distance settings to suit (shadow detail and reach, far shadows, clouds, sunbeam quality, reflection detail, anti-aliasing, particle and weather amounts and distances, and a few more); colours and other looks stay as they are. The large **Use the creator's recommended** button keeps LiteLights' own defaults, and so does closing the panel. The lowest answer keeps every effect, each at its lightest; the top one turns nearly everything up as far as it goes, except Mist style, which stays Mist field. It is asked once. To ask again, use **Run first-time setup again** on the General page.
- **Calculate distance** (Shadows page, under Reach, also in Sodium's Video Settings; only while Voxy or Distant Horizons is installed): sets Reach to your render distance in blocks (render distance 12 gives 192 blocks), which is where the far terrain begins, so the sharp shadows nearby fade into the far terrain's shadows right there and the two read as one set. A longer Reach left a ring of far terrain just past the loaded land with no shadows at all; a shorter one ends the sharp shadows early. Press it again after changing the render distance. Reach goes up to 512 blocks, so past 32 chunks it stops there.

### Changed
- **New defaults** (for new installs; see Upgrading): the sky's colours are Computed (was Vanilla); grass, plants and leaves sway (was still); water colours are Enhanced (was Vanilla); clouds have two layers (was one); rain, snow and sandstorms have Light fog, while thunderstorms, blizzards and haboobs keep Normal; Hide shimmer on outlined items is on; Foam motion is Drifting (was Stationary) and Ocean waves is on (was off), and neither is marked Experimental any more; Shadow reach is 192 blocks (was 256).
- **The Mist field lies on the land and reaches further.** The mist, dust and haze layer now follows the ground wherever you look instead of lying as one flat sheet at the height of the ground you stand on: it fills valleys, climbs hills, gathers thicker and higher in hollows, rises in among trees and in slow, drifting banks, and no longer lags behind while you sprint over uneven land. It no longer switches off when you fly more than about 24 blocks up: from the air you look down on the mist lying in the land. It reaches more than twice as far (about 110 blocks, was 48) and fades smoothly into the fog and haze in the distance. Rooms and caves stay clear as before. It costs a little more on the graphics card (under a third of a millisecond at 1080p on a fast card) and a little on the processor while it is drawn.
- **3D puffs and Classic billboards keep up when you sprint or fly:** the faster you move, the more of the mist and dust is spawned ahead of you, and flying high the mist below spreads wider instead of stopping. Standing still nothing changes.
- **Heavy storms look heavier.** Thunderstorms, blizzards and haboobs used to read calmer than their light versions, because their thicker fog and mist hid the rain, snow and sand. The storm fog and the Mist field now stay thinner in the first dozen blocks or so around you while the distance closes in as before, so the storm's own particles show in front of it; mist the fog already hides no longer draws a pale band over the distance. A blizzard's flakes are a little larger, a shade greyer against the white-out and gather closer around you, and thunderstorm rain falls harder.
- **Much less dust in calm deserts:** with no sandstorm or haboob, the drifting sand, the wind's grit, the low dust drifts and the dust specks in a desert's open sun are cut to about a fifth of what they were. Sandstorms and haboobs are unchanged.
- **A quarter less everyday dust:** dust specks (in sunbeams, by windows, in caves) and the drifting sand and grit outside deserts spawn 25 % less.
- **More vibrant sunsets and sunrises with Computed sky colours,** and no more brown at the end: a little more saturated and warmer while the sun is low, then, as it goes down, an afterglow of deep reds, magentas and purples along the horizon fading up into a clear twilight blue, with clouds catching the pink from below, instead of an orange that dimmed into brown before night. Sunrise does the same in reverse. Noon and night look as before, and so do the Vanilla colours.
- **Sunbeams reach as far as the sun's shadows** (the Shadow reach, 192 blocks by default) instead of stopping at 72 blocks, and fade out gently with distance, so walking toward a beam it grows nearer instead of a wall of light sliding along ahead of you. They cost a little more on the graphics card (about 0.02 ms at the default Medium quality and 0.1 ms at Ultra, at 1080p on a fast card).
- **Wakes:** boats leave a little less foam again (about a fifth less at full speed, a tenth to a fifth less when slower) and players swimming a little more (about a quarter more at a surface swim, a little more when sprint-swimming). The waves themselves are unchanged.
- **Distant fireworks are smaller, quieter and come with pauses.** The bursts are about 60 % of their old size, with smaller sparks and flash. Instead of a burst almost every second, a fireworks night now sends one up every 4 to 12 seconds or so, usually alone, now and then a quick pair or three: about 9 a minute instead of 45. Every player on the world still sees the same show, at the same moments. The far-off bang is about a third as loud. The settings preview is a little busier (a burst every few seconds) and still shows its first burst half a second after you choose it.
- **Meteor showers look like falling stars.** A meteor shower night (still one night in five, the same nights as before) now fills the sky with the falling stars' streaks: about 27 a minute across the whole sky, on top of the night's own falling stars. The radiant showers and the green fireballs are gone. The Meteor showers setting, its place on the Weather page and the Meteor shower preview stay as they were, and so does your saved setting; Falling stars hasn't changed.
- **A more natural aurora.** The curtains fold in broad, uneven sweeps instead of rows of small, regular humps along their lower edge, and the lower edge and the rays are a little softer. It is just as bright overall.

### Fixed
- **The water running up the beach comes with the wave** (Ocean waves): it starts up the sand as the front of the wave's foam reaches the waterline and runs up with it, then slides back after it, instead of following a beat behind the foam. At every Shore run-up and in storms.
- **The aurora and distant fireworks no longer bob with the camera.** With View Bobbing on they moved against the stars and the sun with every step, as the sun did before 0.1.3; now they stay put with the rest of the sky. Falling stars and meteor showers were already steady.

### Upgrading
- **Your saved settings don't change.** The new defaults only apply to a fresh install, and to a setting your `litelights.json` doesn't have yet. LiteLights writes every setting into that file, so if you played an earlier version you keep exactly what you had, old defaults included; nothing is migrated or overwritten. To try the new defaults, change those settings yourself, or use Reset on their rows.
- The first-time setup isn't shown to players upgrading, since they already have a settings file. It can be run from the General page at any time.

## 0.9.7+26.3

### Added
- **Shader loop safety caps, aimed at the Mac freeze:** the few loops in LiteLights' shaders whose length comes from data sent each frame (water and ice reflections, and the blur of enchantment outlines on items in the world) now stop at a fixed limit well above anything a setting asks for. If that data ever arrives corrupted, which the game's renderer can let happen on drivers without persistent mapping such as Apple's OpenGL, a shader can no longer loop until the graphics driver gives up. Nothing looks different and nothing gets slower. This isn't confirmed to fix the freeze on the friend's M4 Max, and it froze there with reflections off too, so it most likely isn't the whole story. The caps are on by default, with a switch on the General page under Compatibility (Shader loop safety caps), also in Sodium's Video Settings; we do not recommend disabling them. Changing it reloads resources once you close the settings. The log's "shaders ready" line now says whether the caps are on.
- **Burn marks has its own switch** on the Particles page, beside Ember veins (also on Sodium's Particles page). It is on by default and, like Ember veins, needs Enhanced fire. Before, the soot came and went with Enhanced fire itself.
- **Mac troubleshooting switches:** for tracking down the freeze on some Macs, the General page's Compatibility section ends with a "Mac troubleshooting" group (also in Sodium's Video Settings). "Use vanilla terrain shading" draws blocks with vanilla's own shading, as if LiteLights' block shaders weren't there; seven more switches each leave one part of those shaders out: water, lava and ice surfaces; caustics and shore swash; weather on glass; shadows and light on blocks; glow, glint and night tone on blocks; fog on blocks; and sway. They are for finding which part a Mac's graphics driver can't build, and players whose game runs should leave them alone: by default every part is in and nothing looks or runs differently. A change reloads resources once you close the settings, and the log's "shaders ready" line now ends with what the shaders left out ("all terrain parts in" by default). The README has the steps to follow on a Mac.
- **Shore run-up** (Water page, with Ocean waves): how far the waves run up the beach after they break, from 1 block (the run-up so far, and the default) to 2 blocks, in quarter blocks. It sets a fair day's run-up; rough seas still run further, up to about three blocks in a thunderstorm, as before. The water never runs over a wall, up a step or onto low ground behind a sea wall. Synced with Sodium's Water page; greyed out while Ocean waves is off.
- **Wave foam** (Water page, with Ocean waves): the ocean waves' foam thinner or thicker, from 25 % to 200 % (100 %, the look so far, is the default): the breaking crests, the whitecaps, the band of surf and the foam on the water running up the beach. Edge foam and the foam on wakes keep their own width. Synced with Sodium's Water page; greyed out while Ocean waves is off.
- **Foam only against blocks** (Water page, with Edge foam, on by default): edge foam now lines the water only where a block stands beside it, a shore, a wall or an island. Thin flowing water that simply runs out on flat ground, such as a spring spreading over a shelf or the fringe of a pool, no longer gets pale foam lines along its edges. Turn it off for the edge foam as before. Synced with Sodium's Water page; greyed out while Edge foam is off.
- **3D puffs** (Mist style): the drifting puffs of rain and snow mist, sandstorm and haboob dust, Nether ash haze and End mist can be drawn as rounded clouds instead of the flat pictures turned to face you that they used to be: each one a rounded cloud with real depth, worked out for every pixel. They melt into the ground and walls they touch instead of being cut off by a hard line, leave no line where they cross water, keep still as you turn and look around, fade in and out where they are instead of popping up, look right from above, and you can walk or fly through one without it sliding past or vanishing. Their edges break up into wisps that drift with the wind, the puffs gather into thicker and thinner patches instead of one even sheet, and each one takes the wind a little differently. Storms read the same at a glance: the colours and how much air the haze fills are matched to before. They cost more to draw than the old flat puffs in heavy rain and blizzards, where there are hundreds of large ones (a few milliseconds a frame at 1080p on a fast card; sandstorms cost about the same as before). Mist style also has **Classic billboards**, the old puffs, which now also fade into what they touch and no longer roll with the camera.

### Changed
- **Shadows and weather keep up with `/time set` and fast time:** after a jump in the time of day (`/time set`, `/time add`) the sun's shadows are redrawn in one frame and snap to the new sun, instead of being redrawn over a few frames and gliding in with Gliding shadows. While time races (`/tick rate` above 20, `/tick sprint`) they are redrawn every time the sun moves, and frost, snow and sand on glass, the storm fog and the sunbeams' thunder fade move on in game time, so they keep up. Sky colours and colour grading already follow the time at once. In normal play nothing changes.
- **Far shadows default to Advanced** (was Screen space): far shadows on Voxy's and Distant Horizons' land, and past the shadow distance, come from the loaded land instead of only what's on screen. New installs start at Advanced; a settings file that already holds Screen space or Off keeps it.
- **Ocean waves seen from high up or far off:** the open sea's swell no longer shows regular stripes. The swell now rises and falls in patches that drift with the wind, like gusts across grass or cloud shadows, with calmer stretches and higher groups between them, and its crests wander a little so no two run straight and parallel for long. Waves too small to see from where you are fade out instead of beating against the screen's pixels into stripes, and the pixel-art steps of the swell's light and foam give way to their average where a texel is smaller than a pixel. Close up the sea looks as it did. Ocean waves stays Experimental and off by default.
- **Foam and waves as one surface:** a passing wave's light and shading now lie over the foam, the surf's and the edge foam alike, instead of the foam pasting over the wave.
- **Textured foam:** the waves, their foam, edge foam and the foam on wakes have a faint pixel-art grain on the 16-per-block grid, so they no longer read as flat colour.
- **The water running up the beach** takes the sea's own colour: the Water colours palette you chose (Vanilla, Enhanced or Natural) and the biome's water tint, instead of one fixed blue-green.
- **Wakes:** a boat's foam trail and foam patches are about a fifth smaller (its waves are unchanged), and swimming leaves a clear wake: a player swimming at the surface now makes a small V with some foam, and sprint-swimming a larger one, still well under a boat's. Wake strength still scales them all.
- **Glint has four sliders instead of one:** on the Lighting page's Glint section (and Sodium's Lighting page), Glint softness is replaced by Water glint softness and Water glint intensity, and Ground glint softness and Ground glint intensity, where "ground" is everything else that glints: ice and snow, metal and glass, sand and stone. Glint under water follows the water sliders. Each starts at 50%. Water at 50% looks as the old Glint softness did at 80%, as bright as before; ground at 50% is the old 0% softness, small sharp sparkles, at half the old brightness. Each slider has room both ways: water goes from the old sharpest to softer than the old slider went, ground from crisper than anything before (rarer, sharper sparkles, and smaller ones with pixel-locked glint off) to the old softest, and both intensities from none to twice their default.
- **Mist and haze are now a Mist field by default** (new setting: Weather, Rain and snow, Mist style; also in Sodium's Video Settings). Instead of separate flat puffs, one low, rolling layer of mist hugs the ground, drawn along with the fog: rain and snow mist, sandstorm and haboob dust, the Nether's ash haze in basalt deltas and the End's mist over the islands. It drifts with the wind, gathers thick in some places and thins in others, stays out of rooms and caves, and is lit and fogged like the puffs it replaces. In heavy rain and blizzards it is the lightest of the three styles on the graphics card (about half a millisecond at 1080p on a fast card).

### Fixed
- **Ember veins could be hidden under the soot** on the blocks right next to a fire: the top of the block a fire burns on, and the wall a fire clings to. Which of the two was drawn last changed from one game start to the next, so in some games the soot covered the veins there. The soot is now always laid first and the veins glow over it. Improved Transparency made no difference either way.
- **Burn marks and ember veins were drawn over the flames** of fire burning against a wall: the flames there stand a hundredth of a pixel out from the block, and the soot and the veins lay further out. Both now lie on the block face itself, under the flames, with Improved Transparency on or off, with Sodium and without. The fire looks as before.
- **Frost, snow and sand on windows could darken the glass** where the glass is partly see-through: stained and tinted glass, glass drawn blended (Improved Transparency), and the half-clear pixels along a window's frame that mipmaps and resource packs make (Continuity's connected glass keeps its clear pixels black). The colour of the clear part, never meant to be seen, was mixed into the weather and darkened it, with a dark fringe where the weather thins out. The weather is now laid over the glass as one layer over another, so it covers or lightens the glass as intended. Ordinary clear glass looks as before.
- **Everything stands still while the game is paused.** Water, waves and foam, wakes, caustics, sway, glint sparkles, the sky's events and the aurora, fog drift, the storm fog's easing and weather on glass kept moving behind the pause menu in singleplayer. They now freeze and carry on from where they stopped when the game resumes. Multiplayer never pauses, so nothing changes there.
- **The player cast screen-space far shadows:** in third person, with Far shadows on Screen space and the sun low behind and to one side, the player's own body and held item threw a shadow over the far land. The player is now left out of the trace; other mobs are unchanged. Advanced, the default, never had this.
- **Screen space far shadows could look as if the sun cast a shadow:** the land at the very edge of the render distance, which the distance fade turns the colour of the sky (brightest round a low sun), was taken as something that blocks the sun. Only land that still reads as land casts far shadows now.
- **The last row of a settings page could be nearly impossible to click** when its description was long: pointing at it made the description box under the list grow, the list moved the row up out from under the pointer, and the row kept jumping away. Pointing at a row never moves it now. The box grows only as far as the room below the row it describes, and a description that doesn't fit there scrolls in the box. Move the pointer into the box to read it in full: the box grows and the row stays just above it. The list also scrolls a little further than before, so a page's last row can always be brought up clear of the largest box. Docked, full screen and in small windows.
- **Foam on and around ice:** ice is no longer treated as a shore. No edge foam lines an ice sheet or an ice roof over water, no surf breaks against it, and the water running up the beach no longer draws on ice or reaches the beach behind an ice sheet.

### Upgrading
- Every saved setting carries over. The new switches start at their defaults: the loop caps, Burn marks, Foam only against blocks and every terrain part on, vanilla terrain shading off. Shore run-up and Wave foam start at today's look.
- **A Glint softness you changed carries over to Water glint softness**, at the place on the new slider that looks the same (your 80% becomes the new 50%, 40% becomes 25%, 100% becomes 64%). If you never changed it (15%), you get the new defaults. The other three glint sliders start at 50%. The old value stays in litelights.json, and the file is backed up first as `litelights.json.schema2.bak`.

## 0.9.6+26.3

### Added
- **Start-up notes in the log, for the Mac freeze:** LiteLights now writes about ten lines starting "LiteLights:" between start-up and the first frames of a world: the graphics driver, how long the shaders took, and each step of the first world frame (the sun shadow map, the post pipelines, LiteLights' set-up, drawing the level, the mob shadow map, the post chain) with its time. Each comes once per game session (the shader line once per resource reload), and nothing is written per frame. If one frame takes over 10 s in that time, one warning names the step and shows where the game is stuck. A log from a game that freezes now says how far it got (see Known limitations in the README).

### Changed
- **Faster:** LiteLights makes about a quarter less garbage for Java to clean up, most of it while chunks load: shadow meshes reuse their memory, and each chunk section is copied once per frame instead of up to seven times. Nothing looks or behaves differently. On a fast graphics card frame times stay the same; slower processors keep the saving.

## 0.9.5+26.3

### Compatibility
- **Older Fabric versions:** LiteLights now needs Fabric Loader 0.19.3 or newer (was 0.19.5) and Fabric API 0.160.5 or newer (was 0.166.0). 0.160.5 is the first Fabric API for the 26.3 release, and every Fabric API for 26.3 needs Loader 0.19.3 itself, so these are as low as 26.3 allows. LiteLights is now built against them, and the oldest pair passed the in-game checks.
- **OpenGL 4.1 (macOS):** everything LiteLights draws was checked against OpenGL 4.1 core, the newest macOS has. Every shader reaches the driver as plain GLSL 3.30 with no extensions, and nothing LiteLights calls needs more than 4.1. The full game checks passed on a Linux driver made to look like a Mac's (GL 4.1, the missing extensions taken away, no clip control, Apple's depth workaround on). This build hasn't been tried on a real Mac. 0.1.7 froze on one (an M4 Max) as the world appeared, with nothing in its log; these checks found no cause for that, so it may still happen (see Known limitations in the README).
- Sway on the vanilla renderer (without Sodium) still needs OpenGL 4.6's draw parameters, which macOS never has; its row now says so plainly: "Needs OpenGL 4.6 (draw parameters) or Sodium". With Sodium, sway works on OpenGL 4.1.

### Fixed
- **False shadows on drivers without persistent mapping** (macOS's OpenGL 4.1 among them): blocky dark patches could appear on the ground and on water and stay until the next shadow redraw. The game's fallback for such drivers can hand a draw another draw's data, and the shadow map kept the wrong result. LiteLights now waits for the GPU after drawing shadow casters on those drivers, only in the few frames of each shadow redraw. Other drivers draw exactly as before.
- **A failing effect could flood the log.** An effect that failed after its own check each frame (the water scene copy is the one seen) was cleared by the next frame's skipped run, so it failed again at "attempt 1" every other frame with a full stack trace: over 100,000 errors in five minutes, a log of hundreds of megabytes and a slow game. A success reported while an effect waits to retry no longer clears its failure, water's scene copy counts as the success it is, and the waits grow again as intended (5 s, 10 s, 20 s, up to 5 min).
- The water scene copy no longer breaks for good if only its second texture failed to allocate.
- Far water on Voxy checks that the driver has the OpenGL 4.3 and 4.5 functions it calls before calling them; without them it finds the sea by its height, as when Voxy's pass can't be read.

### Upgrading
- Nothing in the settings changes: every saved setting carries over.

## 0.9.0+26.3

The first public release, on GitHub. The mod is the same as 0.1.8+26.3; only the version changed. Every saved setting carries over.

## 0.1.8+26.3

### New
- **Wakes** (Water page, its own Wakes section, on by default): boats and swimmers leave wakes behind them. A boat at speed draws a V of crests that spread out behind it, cross other wakes, bounce off the shore and islands and fade over a few seconds, with churned foam along its trail; a turning boat leaves a curling trail, and its paddles send out small rings. Players and mobs swimming at the surface leave a smaller V, and anything falling into the water (players, mobs, items, arrows) sends out a ring. A fast boat throws spray from its bow (it follows the Particles amount). The wake tilts the reflections and the sun's glare and lights its crests on the 16-per-block grid, with pixel-art foam, in the style of the edge foam and Ocean waves. Drawn within about 30 blocks of you. **Wake strength** (25 to 200 %) and **Foam on wakes** go with it. Synced with Sodium's Water page. With Wakes (or Wakes Reforged) installed, the rows say "Handled by Wakes" and LiteLights' wakes step aside. Wake idea by Goby56 (Wakes).

### Licence
- **LiteLights is now MIT licensed** (it was All Rights Reserved), the overlays module included. `LICENSE` holds the MIT text and ships in the jar's `META-INF` beside `CREDITS.md`, and `fabric.mod.json` says `MIT`.
- `LICENSE` also has a Third-party content section for the few things that aren't THENATHE's own: the core shaders in `shaders/core/` (modified from Minecraft's, still Mojang's and not covered by the MIT grant), the soft rain splash sprites and the mod icon (both from vanilla's textures, used as free use), Big Water's CC0 textures and Dave Hoskins' "Hash without Sine".
- Dave Hoskins' full MIT copyright and permission notice now sits above his three hash functions in `shaders/include/noise.glsl`, as his licence asks.
- Nothing changes in game, and every saved setting carries over.

### Fixed
- **Deep water with Improved Transparency** no longer shows a faint purple tint. Seen from above, deep water over a pale bottom (sand, dirt, white blocks) let the bottom show through too strongly in red, most with the Natural and Enhanced palettes and in the 0.1.7 release too. It now looks the same as with classic transparency, at any depth, day and night, with or without Large water textures, on Sodium and the vanilla renderer.
- **Enchantment outlines in first person with Punchy!** (and other first-person animation mods that draw their own arm and held items). Punchy! holds items in third-person poses in place of vanilla's hand, which the first-person outline didn't count as held, so items in hand had no outline at all. The outline now follows Punchy!'s items through swings, sprinting and idle animations, in both hands. Without such a mod nothing changes. Punchy! is listed under Works with on the About page.

## 0.1.7+26.3

### Upgrading
- Every saved setting carries over from 0.1.6. The Weather page was regrouped and Polar lights renamed, but every setting keeps its id, so nothing on that page is reset or migrated. The new storm rows start at Normal and Follow density, which look exactly like 0.1.6.
- Overlay settings carry over. Masonry blocks that still had 0.1.6's default, Receive only, move to the new Bricks style (the brick and tile blocks only; smooth, cut and polished stone keep receiving only). A masonry block you set to anything else, or added yourself, stays as you set it. The cracked bricks and tiles, the nether, red nether, quartz and resin bricks join the Masonry group as Bricks.
- Rain on glass (Drops or Streaks) is gone with the shader-drawn drops. A saved choice still loads; the value stays in the settings file, unread. Every other setting carries over.

### New
- **Bricks edge style** (overlays module) for brick and tile blocks: the overlay is cut out of the block's own texture along its mortar lines, so whole bricks reach into the neighbour in a stepped, running-bond edge, some courses one brick further than others. The brick size is read from each texture (a resource pack's own bricks are followed); vanilla's courses are a quarter or half a block high, and a texture whose pattern can't be read uses the nearer of the two. It's the default for brick and tile masonry and can be picked per block in the hierarchy editor.
  - Brick and tile blocks of one group merge into each other both ways (stone bricks and bricks, deepslate bricks and tiles) and spill onto the group's smooth, cut and polished blocks.
  - Where brick masonry meets rock (stone, deepslate, blackstone, netherrack, end stone, sandstone, terracotta and the rest of the rock groups), the bricks merge into the rock instead of the rock spilling over them, so a wall looks built into the stone around it. Grass, soil, sand and snow still spill onto bricks.
- **Fog and cloud particles for each storm** (Weather page, Storms): Rain, Thunderstorm, Snow, Blizzard, Sandstorm and Haboob each have their own two rows under a sub-heading with the storm's icon.
  - **Fog density:** Light, Normal (the default, the fog as before), Dense or Very dense. The denser levels close the view in much further, in the storm's own colour: grey-blue in rain, darker in a thunderstorm, white in snow and blizzards, sand-coloured in sandstorms and haboobs. Very dense is a true white-out or brown-out.
  - **Cloud particles:** Follow density (the default, as thick as Weather density says) or Off, which leaves out the storm's large soft particles (the mist puffs of rain, thunderstorms, snow and blizzards, the dust clouds of sandstorms and haboobs) and keeps its raindrops, snowflakes and sand grains. Turning them off doesn't thicken the fog.
  - Mist in rain and snow stays: it is still the switch for all of the mist in rain and snow (the thicker haze, the mist along the ground and the puffs), and the four mist rows under Storms need it on. Sandstorm and Haboob rows need Sandstorms. With Immersive Storms installed, the Sandstorm and Haboob fog rows say "Handled by Immersive Storms"; with Particle Rain or Immersive Storms, their cloud rows say which mod draws the sandstorms.
- **Falling stars** in the settings screen's sky event preview, after Distant fireworks.
- **Ocean waves** (Water page, Experimental, off by default): long, gentle swell lines roll across the sea in the direction the wind blows. Within about twenty blocks of the shore the waves turn to come straight in, as real waves do, sharpen, break into a band of foam that rushes in, and run a little way up the beach as a thin sheet of water and foam before sliding back, leaving the sand dark for a moment. It works on sand, gravel, stone or any other shore at the water's level. The swell's shape tilts the reflections and the sun's glitter, so it reads as the surface moving. Rain makes the sea rougher and faster, thunderstorms roughest, with whitecaps out at sea and surf breaking further out; a still dawn is nearly calm. Only oceans and other large open water get waves: ponds and rivers stay calm, and so does flowing water (Streams Reflowing's currents too). Drawn in the shader on the 16-per-block grid; the water itself doesn't move. Synced with Sodium's Water page.

### Changed
- Overlays no longer draw a lone corner where a softer block touches only at a diagonal. Bands and the corners where two bands meet are unchanged.
- **The Weather page is regrouped:** Rain and snow (Rain and snow, Weather density, Weather distance, Spread or fill, Mist in rain and snow), Storms (Storm atmosphere, then the six storms), Weather details (Rain drips, Wall streaks, Drifting sand, Sandstorms), Weather on glass, and Sky events. Sodium's Video Settings has the same groups, a group per storm ("Storms: Rain" and so on).
- **"Thunder sandstorm" is now "Haboob"** in the weather preview and the new storm rows. Hover over it for a word on the name.
- **"Polar lights" is now "Aurora borealis"**, in the Weather page and the sky event preview.
- **Rain on glass is the wall streaks now.** The drops, runners and trails drawn on glass are gone, and the wall streaks that run down stone and brick walls in the rain run down glass blocks and panes too, half as often again as on a wall, on both faces of free-standing glass and on a pane's centred faces. A window under its roof stays dry inside. They follow the Wall streaks row; with Particle Rain installed its streaks take over as before, and with Weather Glass installed the streaks leave glass to it. Rain drips are unchanged.
- **Frost, snow and sand gather along the edges of each window.** A window is all the glass joined in one plane (blocks of any colour side by side, or panes that connect), not each block, with or without Continuity's connected glass. The band is thin on a single block and wider on larger windows, up to about ten pixels at 16x, and fills in the corners in a rounded curve. Frost grows from the corners first; snow lies twice as deep along the bottom and piles higher where there is ground or a ledge below; sand is a little deeper at the bottom. Flat tops still fill with snow and sand. The stuck snowflakes, sand specks and the light rime over the middle of a pane are gone.
- **Snow and sand on glass have texture and stay outside.** Snow and sand along a window's edges are drawn in the snow and sand blocks' own pixels (a resource pack's too), fixed to the glass: snow a shade cool, bluer toward its inner edge, with the odd sparkling pixel, and sand grainy, with lighter and darker grains, a broken inner edge and loose grains just past it. They land only on the side of the glass that is outdoors: the outside of a window, also under an eave, awning or porch roof up to three blocks deep, and both sides of free-standing glass. The inside of a room gets none of its own; from inside you see the snow on the outside through the glass. Frost now keeps to the outdoor side by the same rule: thinner under an eave or a porch roof, on both sides of free-standing glass, none on the inside of a room, where you see the outside's frost through the glass instead. How fast it grows is unchanged, and glass with water against it still gets none. Frost is now crystalline: slightly uneven pixels, the odd brighter crystal, and feathery diagonal crystals growing in from its inner edge. Frost and sand bands are a little thinner than snow's (about a fifth thinner than before), still fuller in the corners.
- **A grander aurora.** The aurora is now several tall curtains of light hanging where a real one would, a few hundred kilometres to the north between about 100 and 300 km up: arcs low along the horizon, nearer bands standing high, and on strong nights bands overhead whose rays meet near the top of the sky. The curtains fold back and forth, brightest where a fold turns edge-on, are made of fine vertical rays gathered into bundles, drift and ripple slowly, and now and then a brightening runs along a band. Colours follow photographs of strong displays: a magenta lower fringe, a touch of yellow-green where it meets the green, the green body fading upward, and faint red-violet tops. Brighter than before, still a soft glow that the stars shine through.
- **Meteor showers are easier to see:** more meteors (about 27 a minute instead of 19), brighter, wider, with trails nearly twice as long and a bright head; one in ten is a fireball with a green head. Showers come one night in five instead of one in seven.
- **Distant fireworks are easier to see:** bigger bursts with more and larger sparks, longer trails, a bigger flash and a soft glow in the burst's colour, a little higher over the horizon. Fireworks nights come one in six instead of one in ten, and the show runs a little later into the night.
- **Falling stars are more common and brighter:** every night now has some (about 2.5 a minute), and busy nights (two in five) about 5 a minute; before, only two nights in five had any, about 3.3 a minute. Streaks are brighter, a little wider and longer.

### Fixed
- **Overlays beside LambdaBetterGrass follow what it draws on each side.** Where it draws a grass, podzol or mycelium side with the block's top on a slope, sand, stone, diorite and other sides beside it now get a band of that top only, with no strip of dirt, and so do connected podzol and mycelium sides beside grass; dirt, coarse dirt and other soil beside it get the grass's fringe over their top corners, as a grass block's side shows. Its partial blends in fancy mode are left as it draws them, and spread nothing. Sides it changed never get the grass's own dirt-and-fringe band, but moss, pale moss and snow still spread over them, blended or not. Grass it draws snowy under a fern or short grass among snow no longer spreads green, and no grass step continues below a blended corner. Its mode and better layers switch are followed as they change, with no reload. Without LambdaBetterGrass, or with it off, the overlays are exactly as before. With plain overlays (and exported packs) only the moss and snow change applies: Continuity's own method can't tell what a neighbouring side looks like.
- Overlays on a face drawn in two layers, such as a grass block's side, were emitted twice; they are drawn once now.
- **Overlays no longer vanish with Sodium's block face culling.** On Sodium 0.9.2, Continuity's overlays (grass spilling onto sand, and the like) on blocks with an open side could disappear for a whole chunk section at once, usually from about 20 blocks away, and come back when you moved or turned block face culling off. That's a bug in Sodium 0.9.2 (fixed in its 0.9.3 betas) that files those overlays under the wrong side of the block. LiteLights now files each overlay under the side it's drawn on. Nothing extra is drawn, and there is nothing to set: the fix runs by itself on Sodium 0.9.2 with Continuity installed. The new **Compatibility** section at the end of the General page shows it as **Overlay face culling fix**, marked Temporary: Active where it runs, otherwise Not needed with the reason (no Sodium, no Continuity, or a Sodium that is already fixed), or Switched off if Sodium's internals ever don't match or the fix hits an error.
- **Crash with Wakes and Improved Transparency.** With Wakes 1.4.1 installed and Improved Transparency on, the game crashed a few seconds after a boat or a swimmer made a wake near you ("Close the existing render pass before creating a new one!"). Wakes draws its wakes with render types that have no Improved Transparency pipelines, which vanilla 26.3 can't draw in its transparency passes. LiteLights' entity shadows no longer ask for those pipelines, and the wakes are now drawn after the transparency passes, as they would be with Improved Transparency off, so they show and nothing crashes. This also stops the same crash in vanilla's own passes, which happened with Wakes and Improved Transparency even without LiteLights. General's Compatibility section shows this as **Wakes transparency fix**, marked Temporary: Active while Wakes is installed, Not needed without it, and Switched off if the wakes' pass ever fails (the wakes then stay hidden instead of crashing). There is nothing to set.
- A LiteLights effect that fails while drawing now only switches itself off and tries again a few seconds later, as it was meant to: before, a failure in the middle of a draw could leave the game's rendering in a broken state and crash the game on the next frame. Its settings row shows the problem and a Retry button meanwhile.
- The far-off bang of distant fireworks was never heard: it was played too far away for its own volume. It now sounds from the burst's direction, at about half the volume of a near one.
- The settings screen's sky event preview showed its event where tonight's schedule put it, often behind you or low behind the trees, so meteors and fireworks could look as if nothing happened. A previewed event now shows straight away in the part of the sky you're looking at (beside the docked panel), and also while it really rains.

### Testing
- New QA modes `wakes17`, `failpass17` and `crash17-costs` sail boats with Wakes 1.4.1 under Improved Transparency, force a failure inside the entity shadow pass to check the game goes on and the feature comes back, and time the wakes' extra pass (within noise).
- New sky QA modes (`sky17-*`) take before and after pictures of the aurora, meteors, fireworks and falling stars, press every sky event preview choice, listen for the far-off bangs and time the aurora's GPU cost (about 0.04 ms, up from 0.01 ms).

## 0.1.6+26.3

### Upgrading
- Every saved setting carries over from 0.1.5, with nothing reset or migrated. The new options start at their defaults, and Foam motion starts at Stationary, the foam as before.
- Breath on glass was only in 0.1.6 test builds and was never released. A settings file from such a build still loads; the value stays in the file, unread.

### New
- **Weather on glass** (Weather page, on by default; idea by duffopuffo, Weather Glass): rain lands on the outside of glass blocks and panes, stained ones included, gathers into drops that now and then run down and leave a wet trail, and dries about 30 seconds after the rain stops. Glass under a one-block overhang gets a little wet, the inside of a window stays dry, and from inside you see the drops on the outside, where they really are. The drops are a few pixels to a block in the colour of the rain that falls (vanilla's rain blue under Vanilla and Vanilla+, LiteLights' paler rain under Enhanced), fixed to the glass, and running drops leave an unbroken trail like a rain streak, so the glass still reads as clear.
  - **Rain on glass:** Drops (default) or Streaks, thin trickles running down like falling rain.
  - **Glass weather detail:** 16x (default, vanilla's pixel size) or 32x.
  - **Frost and snow on glass** (on): where it is cold enough to snow, frost grows in from the glass's frame (with Continuity's connected glass, from the frame of the whole window), snowflakes stick, and in snowfall snow piles up along the bottom outside wherever there is ground or a ledge below.
  - **Dust on glass** (on): after a sandstorm, sand settles on windows in deserts, and red sand in the badlands, until rain washes it off.
  - Weather on glass costs nothing while no glass near you is wet, frosted, snowy or dusty. It works with every Rain and snow setting, Vanilla included. With Weather Glass installed, LiteLights' weather on glass steps aside and its rows say "Handled by Weather Glass".
- **Rain drips** (Weather page, on; drip and streak ideas by pigcart, Particle Rain): water drips from wet roof edges, ledges and leaves and splashes where it lands, and keeps dripping for a while after the rain, tapering off as things dry. They stay with Particle Rain installed, which has no drips.
- **Wall streaks** (Weather page, on): where rain hits a wall of stone, bricks, concrete and the like, short streaks of water run down it, more on the side the wind drives the rain against. With Particle Rain installed its own water streaks take over and the row says so. Like rain drips, they work with every Rain and snow setting, Vanilla included, and need particles on.
- **Foam motion** (Water page, Experimental): Stationary (the default, foam as before) or Drifting, where foam on flowing water (streams, rapids, the water below a waterfall) slowly travels downstream with the current, faster where the water runs faster, and the foam where a waterfall lands trails a little way downstream. Foam on still water, and where the foam lies along the waterline, stay as they are. It follows the game's own flow, so Streams Reflowing's currents carry it too.
- Weather Glass is listed under Works with on the About page, and Weather Glass and Particle Rain are on the Credits page.

### Changed
- Continuity's connected glass now counts as glass for LiteLights everywhere (glint on metal and glass, weather on glass).
- **Softer rain splashes:** under Vanilla+ and Enhanced, vanilla's saturated blue rain splashes are drawn as LiteLights' soft splashes: the same shapes, a little smaller and see-through, tinted to the rain (vanilla's rain blue under Vanilla+, where they stay more opaque so they read against the ground, LiteLights' paler rain under Enhanced). Under Vanilla, and while Particle Rain draws the rain, vanilla's splashes stay as they are.

### Testing
- The test tools (not part of the mod) now resend the test lab's chunks once their light is done, so the first views of a test session no longer show the lab partly dark. New checks cover weather on glass, drips and streaks in each Rain and snow mode, the soft splashes, and foam motion.

## 0.1.5+26.3

### Upgrading
- **Jump splash** is now a choice: a saved Off stays Off, and On becomes Advanced, the new default. The settings file moves to schema 2 (a copy of the old file is kept beside it).
- **Glint on land** is replaced by a switch per material; its old value stays in the settings file but is no longer read, so the new defaults apply: glint on water, ice and snow, metal and glass, and not on sand and stone.
- Every other setting carries over from 0.1.4, including the pixel lock switches that moved to other pages.

### New
- **Jump splash: Off, Basic or Advanced** (Water page, default Advanced). Basic is the old splash. Advanced throws a pixel-style square ring of water-blue and white that spreads from exactly where you land, and water that shoots up and falls back, both larger for harder landings. With Wakes installed, only your own splash is LiteLights' as before.
- **Water colours: Enhanced** (Water page, between Vanilla and Natural; Vanilla stays the default): Natural's colour, depth and light over the water texture's own pixels. It uses the texture actually drawn, so a resource pack's water, Big Water's and Streams Reflowing's keep their look; LiteLights' large water textures only stand in for vanilla's own water there.
- **Glint by material** (Lighting page): Glint on water, on ice and snow, and on metal and glass (on), and on sand and stone (off; when on, only a rare, tiny glimmer with the sun low). **Water sparkle motion**: Still (sparkles shift only as you move), Gentle (default, they also drift slowly with the waves) or Lively.
- **Preview buttons for weather and sky events.** The settings screen's footer now has three buttons, each stepping through its choices with a click: time of day (Dawn, Noon, Dusk, Night), weather (Rain, Thunderstorm, Snow, Blizzard, Sandstorm, Thunder sandstorm, on any biome) and a sky event (Aurora, Meteor shower, Distant fireworks, which bring the night with them). They only change what LiteLights draws: your world's time and weather stay as they are, no real rain or thunder starts, and everything goes back when the screen closes.
- **Tone curve** (Colour Grading page): Vanilla-friendly (the default, as before), ACES-style, AgX-style or Hable filmic. Works with grading off too.
- **Grade templates** (Colour Grading page): fill the grade sliders in one click with LiteLights default, Film print, Portra-style, Teal & orange, Bleach bypass, Technicolor-style, Golden hour or Neutral, for all four times of day or the whole-day grade. Nothing locks: move any slider afterwards and the row says Your own.
- **Biome haze tint** (Fog page): from a neutral haze in every biome (0 %) to each biome's own colour, such as the desert's orange (100 %, the default).
- **Sunset glow** (Fog page): how much the haze warms and glows toward a low sun, from none to twice the built-in look.
- **Getting started** at the top of the General page: what LiteLights is, four mods that go well with it, and a few tips.
- **Weather distance** (Weather page): how far around you rain, snow and blowing sand reach, Near (the usual distance, default), Medium, Far or Very far. **Spread or fill** beside it: Spread keeps the same amount and spreads it over the wider area, Fill keeps it as thick as at Near all the way out, which draws more and can lower your frame rate.
- **Particle distance** (Particles page): the same steps and the same Spread or fill choice for fireflies, dust specks, pollen and spores, and the Nether's and the End's floating particles.
- **Enchantment outlines on held weapons and items in third person and in other players' and mobs' hands** (up to 64 blocks away). Dropped items and item frames still have none.
- **Trim-coloured outlines** (Outlines page, on by default): enchanted armour with a trim glows in the trim material's colour, piece by piece, also for trims from mods and data packs. Untrimmed armour and weapons keep the glow colour.
- **Hide shimmer on outlined items** (Outlines page, off by default): hides the game's enchantment shimmer on the items and armour that get an outline; the outline stays.
- **TAA is ready** (no longer Experimental or Coming soon): Anti-aliasing on the General page offers Off, FXAA or TAA. TAA blends each frame with the last few, which calms shimmering on distant detail and thin edges and keeps block textures crisp. Nothing seen through water or glass doubles while you turn or move, mobs and particles don't leave trails, and moving pictures keep most of their detail. It works with Voxy and Distant Horizons, on OpenGL and Vulkan, with Improved Transparency, view bobbing, the time-of-day preview, screenshots and window resizing. It costs about 0.16 ms a frame at 1920x1080 on a fast graphics card (Medium).

### Changed
- **Shade shows on water from every angle.** A shaded patch of water now reflects a dimmer, cooler sky and no sun, the same way shade darkens land (Darkness and Extra darkness included), so shade no longer nearly vanishes when you look across the water from near its surface. Reflections of trees and buildings keep their own light. Ice and the water on far terrain do the same.
- **Glint reworked:** water's sparkles follow where you look from and drift slowly with the waves, each fading in and out over about a third of a second, instead of flickering white static across the whole lake; far water gets a soft sheen around the sun's reflection instead of tiny dots; no material glints on faces turned away from the sun.
- **Streams Reflowing:** while its waterfall spray is on, LiteLights' waterfall mist, foam and droplets step aside (the white falling water and the foam where it lands stay). It is listed under Works with on the About page.
- The water surface's pixel lock follows the water texture's own pixels, also for resource packs with 32 or more pixels to a block.
- The pixel lock switches moved to the pages of the effects they lock: shadows to Shadows, caustics and the water surface to Water, glint and sunbeams to Lighting. The Pixel lock master switch stays on General. Your settings carry over.
- In the docked settings panel, Experimental settings now show a small flask tag (its tooltip says Experimental) where the full word doesn't fit.
- **Fewer fireflies:** about half as many on a warm night, and Sparse keeps more of them than of the other particles, so Full no longer swarms.
- Weather density's row says "Handled by Particle Rain and Immersive Storms" when both are installed, since they then draw everything it would thicken.
- Blizzards and sandstorms are lighter on the graphics card: mist, dust clouds and snowflakes are drawn only as large as their picture (about half the pixels they used to ask for), and puffs the storm fog hides completely aren't drawn.
- Outlines in the world (worn armour, items held in third person) are as thick as the first-person outline would look from that far away, so distant ones are thin instead of as wide as the one in your hand.

### Fixed
- A held sword or other item no longer looks see-through in front of lava, fire, glowstone and other glowing blocks: their glow stays behind it.
- Ember veins disappear the moment their block burns away or is broken, instead of glowing in empty air for a few seconds; they still fade slowly on a block that is left when its fire goes out.
- **Shore foam only where water meets a solid block at the waterline.** It no longer traces the outline of overhangs, docks, bridges, roofs or branches above the water; the water under them is water, and foam shows where it really meets a wall or a post. Far edge foam follows the same rule.
- Sky events (polar lights, meteors, far-off fireworks) no longer get painted over by the sky with Computed sky colours.

### Testing
- The test tools (not part of the mod) run a release's checks faster and the same way every time: one pre-generated test world, several checks in one game launch, shared shader caches, and one command for the seven standard mod setups, with extra Sodium beta runs when the beta or the code that steps aside for other mods changed, and a search for the mod that makes a setup fail.

## 0.1.4+26.3

### Upgrading
- **Settings renamed in LiteLights' own wording**, in the settings file and often on screen too. On screen, for example: Light rays are now Sunbeams, Water reflections Reflections on water, Shore foam Edge foam, Boost Extra darkness, Sky style Sky colours (Vanilla or Computed), Distance Reach, Resolution Detail (Fastest, Fast, Sharp, Sharpest, Ultra; was Potato, Low, Medium, High), Shadow mode Light model (Relit or Shadows only), Weather visuals Rain and snow (Vanilla, Vanilla+ for the old Enhanced, or Enhanced for the old Custom, still the default), and particle Amount Density (Full, Sparse, Off). Another 26 renamed settings, the colour grade's sliders among them, keep their labels and only have new ids in `config/litelights.json`. The full list is in the README.
- **Upgrading keeps every setting that wasn't renamed. A renamed setting starts at its default** and isn't carried over: its old value stays in `config/litelights.json` under the old name but is no longer read.
- Because of the renames, the new defaults apply to anyone upgrading: shadow Detail Sharpest (was Low, now called Fast), Colour grading By time of day (a gentle orange-and-blue grade at dawn and dusk), and the new Weather density at Medium.

### New
- **Shadow detail Ultra** (8192, about 530 MB of video memory) for graphics cards with memory to spare. If a card can't hold the chosen detail, a smaller one is used and the Shadows page says so.
- **Shadow offset** (Shadows page, Experimental): Contact, Detailed, Balanced (default, the old look) or Clean, trading the shadows of small things at the ground against speckles on slopes.
- **Sunbeam quality** (Lighting page): Low, Medium (default), High or Ultra. Sunbeams no longer smear or halo across leaves and edges, even at Low.
- **Far water by translucency:** water on Voxy's and Distant Horizons' far terrain is found by what they draw over the ground in their translucent pass, not by sea level, so lakes and rivers at any height get the far water look. Distant stained glass or ice can count as water. If the far mod changes so the pass can't be read, the sea is found by its height as before.
- **Three Experimental far water extras** (Water page, all off by default): **Far caustics** on the bottom of shallow far water, **Far depth colour** (far water darkens and takes on its tint with depth, like the water nearby) and **Far edge foam** along far shores. Each is its own switch; with all three off nothing of theirs runs.
- **Ember veins** (Particles page, under Enhanced fire, on by default): wood, leaves, wool and other burnable blocks joined to a fire glow with slowly pulsing orange cracks, most often next to the fire and rarely four blocks away, and fade a few seconds after the fire goes out. They glow through the real bloom.
- **Weather density** (Weather page): Light (the old amount), Medium (default, twice as many rain drops, snowflakes and grains of blowing sand), Heavy or Dense. Mist, dust clouds and snow and sand fog only thicken a little. Rain and snow follow it with Enhanced rain and snow; blowing sand always does.
- **Enchantment outlines on worn armour** (Outlines page, On worn armour, on by default): enchanted armour worn by you in third person, other players and mobs within 64 blocks. A **Diffusion** slider goes from a crisp line to twice as soft (50 % is the old look).
- **About page:** a status overview of every mod LiteLights works with, recommends or can't run beside (installed version, what LiteLights does with it, a Modrinth link when it's missing), and a View the credits button in place of the credit text.
- Settings rows can carry a small tag such as "Experimental" beside the cost badge.

### Changed
- **Extra darkness** (was Boost) now reaches nearly black shadows in full sun at 100 %. 0 % looks as before, and torches and other block light stay as bright.
- Vanilla's round shadow under mobs and players is hidden while LiteLights draws their real shadows. "Mob and player shadows" is now called Creature shadows.
- Dawn and dusk no longer turn brownish: cleaner haze, low-sun light and a later night tone, plus the new default colour grade by time of day (set any slider back to neutral by hand).
- **Fire:** smoke lasts about twice as long, leaning and spreading as it rises and thinning out instead of stacking into a column; with many fires, smoke and embers share the fire particles fairly. Fire's own fake glow (the soft warm halo around flames and campfires) is gone; fire glows through the real bloom alone. Burn marks only lie on the full, solid face under a fire (or the wall it clings to), exactly covering it: never in the air, on plants, carpets, snow layers, slabs or leaves, never over an edge. A mark goes the moment its block is removed, and fades slowly after its fire goes out.
- **Weather:** rain, snow and blowing sand now fill the whole circle around you, not mostly the view. Sandstorms blow much faster, their grains streaking past, and fill the air above and around you instead of mostly skimming the ground. Distant snowflakes melt into a blizzard's air.
- The Credits page's entries wrap and grow to fit at every GUI scale and window size, and the description box under the settings list grows to fit the whole description (scrolling only past a maximum height).
- The Dynamic Overlays credit is removed everywhere; Continuity (Pepper_Bell) is still credited for drawing the overlays. Dave Hoskins' "Hash without Sine" (MIT) is credited in the noise shader and CREDITS.md.

### Fixed
- Grass, flower and fern shadows now sit under their plants instead of a fraction of a block to the side.
- The settings page follows option changes at once: rows that show only for a choice (Edit time of day under By time of day) appear and go, and sub-switches grey out, whether the change comes from the page, a reset or Sodium's Video Settings. Before, the row only appeared after opening and closing a dropdown.
- Sunbeams, above and under water, fade out slowly as thunder builds (storms, blizzards, sandstorms) over about 25 seconds and come back as slowly after it clears, instead of popping.
- Shore foam no longer draws a straight line (sometimes with a right-angle corner) across open water about 100 to 130 blocks away, where the water's column map ends. Foam only ever draws against land that is really there, and foam, floating leaves and caustics fade out at the map's edge instead of stopping in a line.
- Rain, snow and blowing sand no longer look thin for a moment after turning quickly.
- Blizzards show their snowflakes again: their mist no longer uses up the room the flakes need.
### Compatibility
- Big Water 1.2.0 doesn't run on Sodium 0.9.3-beta.5 (Big Water calls a Sodium method the beta renamed; this happens without LiteLights too). Use Sodium 0.9.2 with Big Water. The About page says so on Big Water's line.

## 0.1.3+26.3

### Fixed
- The sun, moon, stars, the Realistic sky and the light rays no longer jump about while walking with View Bobbing on. Water reflections and glints follow the bobbing view too, so a tight glint no longer flickers with each step.
- With Voxy, trees, leaves and plants near the border of the loaded land no longer turn the colour of the fog: LiteLights' own land was being hazed a second time as if it were Voxy's.
- Overlays: grass spilling onto a block's side no longer shows a few pixels of bright default green in dark biomes; every overlay from a tinted source takes the biome's colour.

Upgrading from 0.1.2 or earlier keeps every setting.

## 0.1.2+26.3

### Fixed
- With Voxy, a dark band stood along the border where LiteLights' land meets Voxy's sea, with Far terrain on or off: LiteLights' deep-water look (darker and less see-through with depth) ended at the border against Voxy's lighter, see-through water. LiteLights' water now hands over to Voxy's look over the last part of its own land.
- Shore foam no longer draws along the edge of the loaded land, where water meets chunks that aren't loaded (it showed as a thin bright line at the border, and at the render distance's edge without far-terrain mods).
- Water on far terrain no longer darkens Voxy's far sea (the near water now matches Voxy's instead); it keeps the sky and land reflections and the sun's glint.

## 0.1.1+26.3

### New
- **Far terrain** (Fog page, on): Voxy's and Distant Horizons' far terrain gets LiteLights' haze with biome colour and height falloff, ground mist, storm fog, End tint, light model and night tone, so it meets the nearby land without a seam in haze or brightness. Distant Horizons' own fog steps aside while LiteLights' applies.
- **Far shadows** (Shadows page): Off, Screen space (default) or Advanced (heavy). Shadows past the shadow distance, on LiteLights' own far land and on Voxy's and Distant Horizons' terrain. Screen space finds hills that are on screen; Advanced also finds off-screen ones, such as a mountain behind you, from an 8 MB map of the loaded land's heights.
- **Water on far terrain** (Water page, on): the sea on Voxy's and Distant Horizons' terrain gets LiteLights' depth colour (Voxy) and sky reflections with the sun's glint.

### Fixed
- With Sodium, newly loaded chunks fade in again under LiteLights' fog, using Sodium's own fade timing.
- Newly loaded chunks fade in from the colour LiteLights' fog fades the land into, on the vanilla renderer too, instead of from vanilla's fog colour.
- On the vanilla renderer, crops at the edge of a run of crop sprites in the texture atlas sway evenly (their mirrored faces read the wrong sprite).

### Compatibility
- Voxy 0.2.20-beta and Distant Horizons 3.3.4 are supported; both turn LiteLights' far-terrain work off with a reason on the Far terrain row if they change underneath it.

Upgrading from 0.1.0 keeps every setting; the three new options start at their defaults.

## 0.1.0+26.3

First release.
