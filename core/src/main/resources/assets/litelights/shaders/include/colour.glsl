#ifndef LITELIGHTS_COLOUR_GLSL
#define LITELIGHTS_COLOUR_GLSL

// Colour helpers. The main target holds sRGB-encoded colour, so anything that adds light or grades
// works on linear values and encodes again at the end.

// Rec. 709 luminance of a linear colour.
float lite_luma(vec3 color) {
    return dot(color, vec3(0.2126, 0.7152, 0.0722));
}

float lite_srgb_to_linear(float value) {
    return value <= 0.04045 ? value / 12.92 : pow((value + 0.055) / 1.055, 2.4);
}

float lite_linear_to_srgb(float value) {
    value = max(value, 0.0);
    return value <= 0.0031308 ? value * 12.92 : 1.055 * pow(value, 1.0 / 2.4) - 0.055;
}

vec3 lite_srgb_to_linear(vec3 color) {
    vec3 low = color / 12.92;
    vec3 high = pow((color + 0.055) / 1.055, vec3(2.4));
    return mix(high, low, lessThanEqual(color, vec3(0.04045)));
}

vec3 lite_linear_to_srgb(vec3 color) {
    color = max(color, vec3(0.0));
    vec3 low = color * 12.92;
    vec3 high = 1.055 * pow(color, vec3(1.0 / 2.4)) - 0.055;
    return mix(high, low, lessThanEqual(color, vec3(0.0031308)));
}

#endif
