#ifndef LITELIGHTS_LOOP_CAPS_GLSL
#define LITELIGHTS_LOOP_CAPS_GLSL

// Shader loop safety caps (General > Compatibility). A loop whose count comes from uniform data
// stops at a fixed number of passes, set beside the loop well above anything LiteLights sends, so
// data that arrives corrupted can't keep the GPU looping until the driver gives up. On drivers
// without persistent mapping (Apple's OpenGL) renderpearl can refill per-draw data while the GPU
// still reads it (DECISIONS, compat095 and caps097). Every other loop in LiteLights' shaders has a
// constant in its own condition and needs no switch; a float condition or a break can only end
// one of those sooner.
//
// The switch is the define below. Each resource reload reads this file, and with the setting off
// LiteLights leaves the line out (render.pipeline.ReloadDefines), so it is fixed when the shaders
// compile and no uniform can change it.
#define LITE_LOOP_CAPS

#ifdef LITE_LOOP_CAPS
// A count from uniform data, as a whole number from 0 to cap. The float clamp keeps int() within
// range for ordinary values, infinity included. Under fast maths (Metal's default) a NaN may pass
// through it, as isnan() would be optimised away too, so the integer clamp after the conversion is
// what holds: whatever int() makes of the value, the count ends up within 0 to cap.
int lite_loop_count(float count, int cap) {
    return clamp(int(clamp(count, 0.0, float(cap))), 0, cap);
}

int lite_loop_cap(int count, int cap) {
    return clamp(count, 0, cap);
}
#else
int lite_loop_count(float count, int cap) {
    return int(count);
}

int lite_loop_cap(int count, int cap) {
    return count;
}
#endif

#endif
