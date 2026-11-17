#ifndef LITELIGHTS_TERRAIN_PARTS_GLSL
#define LITELIGHTS_TERRAIN_PARTS_GLSL

// Mac troubleshooting (General > Compatibility). A friend's Mac freezes in the first terrain draw,
// on OpenGL and Vulkan alike, and Apple's OpenGL says it couldn't build a Metal pipeline. These
// switches compile parts of LiteLights' terrain and block-model shaders out, so a Mac player can
// find the part Metal can't handle by turning them off one at a time. Every part is in by default.
//
// Each define below is a reload switch (render.pipeline.TerrainParts, through ReloadDefines): with
// its row off, the reload that reads this file leaves its line out. The lines must stay as they
// are, "#define NAME" alone on a line. Only the terrain and block-model shaders (vanilla's and
// Sodium's) test these, at their own call sites; the hook files are shared with entities and items,
// which keep every part.
//
// LITE_TERRAIN_LITELIGHTS is the master: "Use vanilla terrain shading" leaves it out, and the hosts
// then compile their vanilla main() and skip LiteLights' includes altogether.
#define LITE_TERRAIN_LITELIGHTS

// Water, lava and ice surfaces: the water hook's shading, reflections and their screen-space trace,
// large textures, foam, waves, wakes, falling water, lava crust and ice reflections, and the
// Improved Transparency alpha for water.
#define LITE_TERRAIN_WATER

// Ground and shore details: caustics on blocks under water, the swash on beaches, the leaves lying
// under trees, puddles in the rain and snow on leaves and under trees.
#define LITE_TERRAIN_CAUSTICS

// Weather on glass: frost, snow and sand on windows.
#define LITE_TERRAIN_GLASS

// Sun and mob shadows and LiteLights' light model. Out, blocks take vanilla's lightmap as it is.
#define LITE_TERRAIN_SHADOWS

// The light-post surface hook: enhanced glow, night tone, glint, sunbeam landing and bloom's mask.
#define LITE_TERRAIN_LIGHT

// LiteLights' fog and End tint on blocks. Out, blocks take vanilla's (or Sodium's) fog.
#define LITE_TERRAIN_FOG

// Sway of plants and leaves, and swinging lanterns, chains and hanging signs, in the vertex stage.
#define LITE_TERRAIN_SWAY

#ifndef LITE_TERRAIN_LITELIGHTS
#undef LITE_TERRAIN_WATER
#undef LITE_TERRAIN_CAUSTICS
#undef LITE_TERRAIN_GLASS
#undef LITE_TERRAIN_SHADOWS
#undef LITE_TERRAIN_LIGHT
#undef LITE_TERRAIN_FOG
#undef LITE_TERRAIN_SWAY
#endif

#endif
