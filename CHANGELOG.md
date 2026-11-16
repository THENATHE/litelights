# Changelog

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
