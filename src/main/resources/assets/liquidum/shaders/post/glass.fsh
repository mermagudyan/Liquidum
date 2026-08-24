#version 330

uniform sampler2D InSampler;

#define MAX_PANELS 128
layout(std140) uniform GlassConfig {
    vec4 uRects[MAX_PANELS];
    vec4 uParams;
    vec4 uMeta;     // (count, 0, 0, 0) — vec4 keeps std140 offsets aligned with the Java/JSON layout
    vec4 uScreen;   // (mainW, mainH, mouseX, mouseY)
    vec4 uFlags;    // (mode, hoverOn, edgeFX, frostRadius); edgeFX: +2 aberration, +1 rim
};

in vec2 texCoord;
out vec4 fragColor;

float sdRoundedBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

// glsl-fast-gaussian-blur (roadmap source): 25-tap 5x5 gaussian, radius r.
vec3 blur25(vec2 uv, vec2 texel, float r) {
    float w[5] = float[5](1.0, 4.0, 6.0, 4.0, 1.0);
    vec3 sum = vec3(0.0);
    float total = 0.0;
    for (int dx = -2; dx <= 2; dx++) {
        for (int dy = -2; dy <= 2; dy++) {
            float wgt = w[dx + 2] * w[dy + 2];
            sum += texture(InSampler, uv + vec2(dx, dy) * texel * r).rgb * wgt;
            total += wgt;
        }
    }
    return sum / total;
}

void main() {
    // gl_FragCoord = framebuffer pixels (bottom-origin); uv = 0..1 over screen.
    vec2 px = gl_FragCoord.xy;
    vec2 uv = px / uScreen.xy;
    vec2 texel = 1.0 / uScreen.xy;

    int mode = int(uFlags.x + 0.5);
    int uCount = int(uMeta.x + 0.5);
    bool hoverOn = uFlags.y > 0.5;
    bool aberrationOn = uFlags.z >= 1.5;
    bool rimOn = mod(uFlags.z, 2.0) >= 0.5;
    float frostR = uFlags.w;

    // DIAGNOSTIC MODES (Lab): paint green if the named UBO field arrived, red if zero.
    if (mode == 3) { bool ok = uScreen.x > 1.0; fragColor = vec4(ok ? 0.0 : 1.0, ok ? 1.0 : 0.0, 0.0, 1.0); return; }
    if (mode == 4) { float c = clamp(uMeta.x / 20.0, 0.0, 1.0); fragColor = vec4(c, 0.0, 0.0, 1.0); return; }
    if (mode == 5) { bool ok = uRects[0].x > 1.0; fragColor = vec4(ok ? 0.0 : 1.0, ok ? 1.0 : 0.0, 0.0, 1.0); return; }

    // MODE 0: raw capture passthrough (isolation test).
    if (mode == 0) {
        fragColor = vec4(texture(InSampler, uv).rgb, 1.0);
        return;
    }

    float bestMask = 0.0;
    float bestEdge = 0.0;
    float bestD = 1.0;
    vec2 bestDir = vec2(0.0);

    for (int i = 0; i < MAX_PANELS; i++) {
        if (i >= uCount) break;
        vec4 rect = uRects[i];
        vec2 halfSize = rect.zw;
        if (halfSize.x <= 0.0 || halfSize.y <= 0.0) continue;

        vec2 radial = px - rect.xy;
        vec2 dir = radial / max(length(radial), 1e-4);

        float d = sdRoundedBox(radial, halfSize, clamp(uParams.x, 0.0, 1.0) * min(halfSize.x, halfSize.y));
        float mask = 1.0 - smoothstep(-1.5, 1.5, d);
        if (mask <= 0.0) continue;

        // Roadmap: interior flat, thin outer ring = convex lens (iPhone-style).
        float reach = min(halfSize.x, halfSize.y);
        float edgeFactor = smoothstep(-reach * 0.38, -reach * 0.02, d) * mask;

        if (edgeFactor >= bestEdge) {
            bestEdge = edgeFactor;
            bestDir = dir;
            bestD = d;
        }
        bestMask = max(bestMask, mask);
    }

    // MODE 1: mask visualization (cyan glow = tiles).
    if (mode == 1) {
        fragColor = vec4(vec3(0.0, bestMask * 0.8, bestMask * 0.8), 1.0);
        return;
    }

    if (bestMask <= 0.001) {
        // Outside tiles: pass the world through untouched.
        fragColor = vec4(texture(InSampler, uv).rgb, 1.0);
        return;
    }

    // Hover: sharp falloff - only the tile under the cursor reacts strongly.
    vec2 mouse = uScreen.zw;
    float hover = hoverOn ? (1.0 - smoothstep(20.0, 70.0, length(px - mouse))) : 0.0;

    float rimBand = smoothstep(-5.0, -1.0, bestD) * (1.0 - smoothstep(-1.0, 3.0, bestD));
    float refr = bestEdge * (1.0 - rimBand * 0.45) * (1.0 + hover * 0.6);
    vec2 off = bestDir * (refr * uParams.y) / uScreen.xy;

    // Chromatic aberration (Level 2): RGB with slightly different lens power.
    float ab = aberrationOn ? 1.0 : 0.0;
    float split = mix(1.0, 1.35, ab) - (1.0 - ab) * 0.35; // 1.35 on, 1.0 off
    vec3 sharp;
    sharp.r = texture(InSampler, uv + off * split).r;
    sharp.g = texture(InSampler, uv + off).g;
    sharp.b = texture(InSampler, uv + off * (2.0 - split)).b;

    // Frosted interior: in-shader gaussian (frost toggle/radius from Lab).
    vec3 blurredC = frostR > 0.5 ? blur25(uv + off, texel, frostR) : sharp;

    vec3 glass = mix(blurredC, sharp, uParams.w);

    // Clearly visible liquid-glass body: bluish lift so tiles read even on flat
    // menu backgrounds (otherwise the interior is just a faintly blurred world).
    glass = mix(glass, glass * vec3(0.80, 0.92, 1.10) + vec3(0.06, 0.08, 0.12), 0.55 * bestMask);
    float luma = dot(glass, vec3(0.299, 0.587, 0.114));
    glass = mix(glass, vec3(1.0), (0.06 + 0.10 * (1.0 - luma)) * bestMask);

    // Fresnel rim: dim baseline glow; the hovered tile flares up.
    float topBias = clamp(0.65 + bestDir.y * 0.55, 0.0, 1.0);
    float topOnly = rimBand * smoothstep(0.05, 0.45, bestDir.y) * (rimOn ? 1.0 : 0.0);
    vec3 rimTint = vec3(0.95, 0.98, 1.05) * ((0.10 + 0.30 * topBias) * uParams.z) * (1.0 + hover * 1.6);
    glass += topOnly * rimTint;
    // Bright crisp edge so the panel boundary is always obvious.
    glass += rimBand * (rimOn ? 0.25 : 0.12) * vec3(0.9, 0.95, 1.0) * bestMask;
    glass *= 1.0 - rimBand * (1.0 - topBias) * ((rimOn ? 0.10 : 0.0) - hover * 0.06);
    glass *= 1.0 + hover * 0.10 * bestMask;

    // Opaque composite: world outside, glass inside.
    fragColor = vec4(mix(texture(InSampler, uv).rgb, glass, bestMask), 1.0);
}
