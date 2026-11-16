#ifndef LITELIGHTS_HOOKS_GLSL
#define LITELIGHTS_HOOKS_GLSL

// Feature hooks. Each phase-2 stream owns one file under hooks/ and fills in its functions; this
// file only wires them in, so streams never edit the same file. The call order in the terrain and
// entity shaders is:
//   vertex:   lite_vegetation_offset          (vegetation)
//   fragment: lite_glass_surface              (particles-weather: weather on glass, on the texture's
//                                             colour before the cut-out test and lighting)
//             lite_water_surface              (water: colour, reflections, foam, caustics)
//             lite_water_oit_alpha            (water: in the Improved Transparency alpha-only stages
//                                             instead, the alpha the colour stage gives water)
//             lite_light_surface              (light-post: enhanced light, glint)
//             lite_fog                        (sky-fog: replaces vanilla's fog when it returns true)
#include <litelights:surface.glsl>
#include <litelights:hooks/vegetation.glsl>
#include <litelights:hooks/water.glsl>
#include <litelights:hooks/glass.glsl>
#include <litelights:hooks/light_post.glsl>
#include <litelights:hooks/sky_fog.glsl>
#include <litelights:near_mark.glsl>

#endif
