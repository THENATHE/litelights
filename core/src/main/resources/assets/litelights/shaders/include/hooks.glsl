#ifndef LITELIGHTS_HOOKS_GLSL
#define LITELIGHTS_HOOKS_GLSL

// Feature hooks. Each phase-2 stream owns one file under hooks/ and fills in its functions; this
// file only wires them in, so streams never edit the same file. The call order in the terrain and
// entity shaders is:
//   vertex:   lite_vegetation_offset          (vegetation)
//             lite_hanging_offset             (vegetation: swinging lanterns, chains and hanging signs)
//   fragment: lite_glass_surface              (particles-weather: weather on glass, on the texture's
//                                             colour before the cut-out test and lighting)
//             lite_ground_leaves              (vegetation: leaves under trees, on the texture's colour
//                                             after the cut-out test, before lighting)
//             lite_snow_cover                 (snowleaves099: snow on leaves and under trees, on the
//                                             texture's colour after the leaves under trees, terrain only)
//             lite_water_surface              (water: colour, reflections, foam, caustics)
//             lite_water_oit_alpha            (water: in the Improved Transparency alpha-only stages
//                                             instead, the alpha the colour stage gives water)
//             lite_puddles                    (rain099: puddles in the rain, terrain only)
//             lite_light_surface              (light-post: enhanced light, glint)
//             lite_fog                        (sky-fog: replaces vanilla's fog when it returns true)
#include <litelights:surface.glsl>
#include <litelights:hooks/vegetation.glsl>
#include <litelights:hooks/water.glsl>
#include <litelights:hooks/puddles.glsl>
#include <litelights:hooks/snow.glsl>
#include <litelights:hooks/glass.glsl>
#include <litelights:hooks/light_post.glsl>
#include <litelights:hooks/sky_fog.glsl>
#include <litelights:hand_alpha.glsl>

#endif
