#ifndef LITELIGHTS_NOISE_GLSL
#define LITELIGHTS_NOISE_GLSL

// Hashes and value noise shared by the sky, the fog wisps and the light rays. Water keeps its own
// integer hash (water/common.glsl), which needs its 1024-block wrap.

// The three hashes below are Dave Hoskins' "Hash without Sine",
// https://www.shadertoy.com/view/4djSRW, renamed with LiteLights' prefix. His notice:

/* Copyright (c)2014 David Hoskins.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.*/

float lite_hash11(float p) {
    p = fract(p * 0.1031);
    p *= p + 33.33;
    p *= p + p;
    return fract(p);
}

float lite_hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec3 lite_hash33(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.xxy + p.yxx) * p.zyx);
}

// Smooth noise 0..1 on a unit grid: a hash at each corner, blended with a smoothstep.
float lite_value_noise(vec2 p) {
    vec2 cell = floor(p);
    vec2 f = p - cell;
    vec2 blend = f * f * (3.0 - 2.0 * f);
    float a = lite_hash12(cell);
    float b = lite_hash12(cell + vec2(1.0, 0.0));
    float c = lite_hash12(cell + vec2(0.0, 1.0));
    float d = lite_hash12(cell + vec2(1.0, 1.0));
    return mix(mix(a, b, blend.x), mix(c, d, blend.x), blend.y);
}

#endif
