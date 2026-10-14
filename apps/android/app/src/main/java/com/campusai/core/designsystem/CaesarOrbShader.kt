package com.campusai.core.designsystem

/**
 * Shared-scene GLES2 material. Spectral wave adapted from aaaa-zhen/glsl-playground (MIT).
 * Upstream: https://github.com/aaaa-zhen/glsl-playground/blob/main/liquid-glass.html
 * Attribution and license are bundled in res/raw/glsl_playground_license.txt.
 */
internal const val CAESAR_ORB_FRAGMENT_SHADER = """
precision highp float;
uniform sampler2D uScene;
uniform vec2 uSurfaceSize;
uniform vec4 uRegion;
uniform float uTime;
uniform float uDark;
uniform float uEnergy;
uniform float uActivity;
uniform float uListening;

// Adapted from spectral4/waveCol in glsl-playground. Audio excitation comes from real RMS;
// the source demo's synthetic low/mid/high signals and standalone render loop are not used.
vec3 spectral4(int sampleIndex) {
    float x = float(sampleIndex);
    return clamp(vec3(abs(x - 3.0) - 1.0, 2.0 - abs(x - 2.0), 2.0 - abs(x - 4.0)), 0.0, 1.0);
}

vec3 spectralWave(vec2 p, float excitation, float time) {
    float envelope = cos(1.5707963 * min(abs(.9 * p.x), 1.0));
    envelope *= envelope;
    float amplitude = .30 + .06 * excitation;
    float dispersedAmplitude = amplitude + .08 * excitation;
    float aberration = 2.6 + .7 * excitation;
    float thickness = .028;
    float intensity = .020 + .006 * excitation;
    float softness = .025 + .004 * excitation;
    float mainY = amplitude * envelope * sin(p.x * 1.1 + time);
    vec3 numerator = vec3(0.0);
    vec3 denominator = vec3(0.0);
    for (int i = 0; i < 4; i++) {
        vec3 hue = spectral4(i);
        denominator += hue;
        float offset = mix(-aberration, aberration, float(i) / 3.0);
        float spectralY = dispersedAmplitude * envelope * sin(p.x + time + offset);
        float distance = abs(p.y - spectralY);
        float line = intensity / (sqrt(distance * distance + softness * softness) + thickness);
        float bandDistance = max(0.0, max(p.y - max(mainY, spectralY), min(mainY, spectralY) - p.y));
        float band = 2.6 * intensity / (bandDistance + .08);
        numerator += hue * (line + band);
    }
    vec3 color = numerator / denominator;
    float mainDistance = abs(p.y - mainY);
    color += .5 * intensity / (sqrt(mainDistance * mainDistance + softness * softness) + thickness);
    return pow(max(color, 0.0), vec3(1.5)) * exp(-pow(p.x * 1.2, 2.0));
}

void main() {
    vec2 uv = gl_FragCoord.xy / uSurfaceSize;
    vec2 q = (uv - uRegion.xy) / uRegion.zw * 2.0 - 1.0;
    float breath = sin(uTime * 1.32) * .009;
    float stretch = .025 * uActivity + .024 * uEnergy;
    // The phone hero is a low dome, while compact status and voice orbs stay rounder.
    float hero = smoothstep(.38, .50, uRegion.z);
    vec2 p = q / vec2(.856 + breath + stretch, (.852 - breath - stretch * .45) / (1.0 + .18 * hero));
    float power = 2.20 + .16 * uActivity;
    float radius = pow(pow(abs(p.x), power) + pow(abs(p.y), power), 1.0 / power);
    float pixel = 2.0 / min(uRegion.z * uSurfaceSize.x, uRegion.w * uSurfaceSize.y);
    float coverage = 1.0 - smoothstep(1.0 - pixel, 1.0 + pixel, radius);

    // Neutral soft contact shadow, no colored halo. Standard alpha blending keeps the scene.
    if (radius > 1.0 + pixel) {
        float shadow = exp(-dot(q / vec2(.88, .65) + vec2(-.07, .31),
                               q / vec2(.88, .65) + vec2(-.07, .31)) * 2.5) * .22;
        gl_FragColor = vec4(0.0, 0.0, 0.0, shadow);
        return;
    }

    float lens = sqrt(max(0.0, 1.0 - min(radius * radius, 1.0)));
    float edge = 1.0 - lens;
    // A thick centre magnifies; the outer shell bends the same scene back outwards.
    vec2 warp = p * (.105 * edge * edge - .070 * lens) * uRegion.zw;
    vec2 split = p * uRegion.zw * .0032 * edge * edge;
    vec2 sampleUv = clamp(uv + warp, vec2(.001), vec2(.999));
    vec3 scene = vec3(texture2D(uScene, sampleUv + split).r,
                      texture2D(uScene, sampleUv).g,
                      texture2D(uScene, sampleUv - split).b);
    float lower = smoothstep(.0, .95, -p.y);
    float luminance = dot(scene, vec3(.2126, .7152, .0722));
    vec3 crystal = mix(scene, vec3(luminance), .34) * mix(.52, .96, lower);
    crystal += vec3(.022, .024, .029) * uDark;
    float cap = smoothstep(-.38, .32, p.y + .075 * (1.0 - p.x * p.x));
    vec3 blackMirror = vec3(.003, .0035, .005) + scene * .008 + vec3(edge * edge * .025);
    vec3 color = mix(crystal, blackMirror, cap);
    // The bright base is a local caustic; the rest of the lower bowl stays transmissive.
    float bottomLight = exp(-pow((p.y + .84) / .17, 2.0)) * max(0.0, 1.0 - p.x * p.x);
    color += vec3(.26, .275, .30) * bottomLight;
    color *= 1.0 - .20 * smoothstep(.88, 1.0, radius);

    // The four dispersed spectral sheets pass through the lens together. Their overlap
    // becomes white, while the soft falloff lights a small volume around the equator.
    vec2 w = p * (1.0 + .18 * edge * edge);
    float excitation = mix(.25 + .35 * uActivity, uEnergy, uListening);
    float waveHeight = mix(.36 + .07 * uActivity, .07 + .50 * uEnergy, uListening);
    vec2 wavePosition = vec2(w.x / .86, (w.y + .025) / waveHeight);
    vec3 wave = spectralWave(wavePosition, excitation, uTime * (.82 + .48 * uActivity));
    wave = mix(wave, vec3(max(wave.r, max(wave.g, wave.b))), .16);
    float waveWindow = 1.0 - smoothstep(.30, .58, abs(w.y + .025));
    color += wave * 1.10 * waveWindow;

    float rim = exp(-abs(radius - .993) / max(pixel * .65, .003));
    color += vec3(rim * mix(.035, .44, 1.0 - smoothstep(-.90, .22, p.y)));
    float side = exp(-pow((abs(p.x) - .93) / .055, 2.0)) * exp(-p.y * p.y * 5.5);
    color += vec3(side * .17);
    gl_FragColor = vec4(clamp(color, 0.0, 1.0), coverage);
}
"""
