#version 330

uniform sampler2D InSampler;

#define MAX_PANELS 128
layout(std140) uniform GlassConfig {
    vec4 uRects[MAX_PANELS];
    vec4 uParams;
    vec4 uMeta;     // (count, fuseRadius, ringY, ringX)
    vec4 uScreen;   // (mainW, mainH, mouseX, mouseY)
    vec4 uFlags;    // (mode, hoverOn, edgeFX, frostRadius); edgeFX: +2 aberration, +1 rim
    vec4 uRing;     // (halfW, halfH, lineHalfWidth, cornerRadius) — fb px
    vec4 uGrid;     // (originX, pitch, rightEdge, 0) — hotbar slot grid, fb px
    vec4 uPanel;    // frosted container panel (centre xy, half wh) — fb px
    vec4 uPar;      // parallax: smoothed mouse xy (fb px), strength px, 0
};

in vec2 texCoord;
out vec4 fragColor;

float sdRoundedBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

// Polynomial smooth minimum (opSmoothUnion lineage): fuses nearby panels into
// one continuous metaball blob instead of hard overlaps.
float smin(float a, float b, float k) {
    float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
    return mix(b, a, h) - k * h * (1.0 - h);
}

// Procedural glass texture (OverShifted/LiquidGlass: "blur, noise and glow"):
// hash grain breaks the flat plastic look, low-frequency smudges add
// "touched glass" patches. No assets, ~free on GPU.
float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
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
    // MODE 6: solid magenta — proves the chain's output lands in minecraft:main
    // at THIS point of the frame (independent of any UBO/uniform concerns).
    if (mode == 6) { fragColor = vec4(1.0, 0.0, 1.0, 1.0); return; }

    // MODE 0: raw capture passthrough (isolation test).
    if (mode == 0) {
        fragColor = vec4(texture(InSampler, uv).rgb, 1.0);
        return;
    }

    // uMeta.y = fusion radius in px (0 = hard union). Nearby tiles melt into
    // one blob with rounded bridges, like iOS glass elements merging.
    // Tiles with NEGATIVE halfWidth opt out of fusion (hard union) — used by
    // hotbar slots so every block stays individually visible.
    float fuseK = max(uMeta.y, 0.0);
    float bestMask = 0.0;
    float dfold = 1e9;      // fused SDF across all panels
    bool isoFold = false;   // dfold currently belongs to a fusion-exempt tile
    float reachAcc = 0.0;   // proximity-weighted lens reach
    float densAcc = 0.0;    // proximity-weighted "dense tile" factor
    vec2 dirAcc = vec2(0.0);
    float wsum = 0.0;

    for (int i = 0; i < MAX_PANELS; i++) {
        if (i >= uCount) break;
        vec4 rect = uRects[i];
        bool fusable = rect.z >= 0.0;
        bool dense = rect.w < 0.0;   // negative halfHeight = denser material
        vec2 halfSize = abs(rect.zw);
        if (halfSize.x <= 0.0 || halfSize.y <= 0.0) continue;

        vec2 radial = px - rect.xy;
        vec2 dir = radial / max(length(radial), 1e-4);

        float d = sdRoundedBox(radial, halfSize, clamp(uParams.x, 0.0, 1.0) * min(halfSize.x, halfSize.y));
        bestMask = max(bestMask, 1.0 - smoothstep(-1.5, 1.5, d));

        // Far panels neither mask nor fuse — skip early (ALU saving).
        if (d > fuseK * 3.0 + 24.0) continue;

        bool iso = !fusable || dense;   // dense HUD panels never fuse with screen tiles
        if (dfold > 1e8) {
            dfold = d;
            isoFold = iso;
        } else if (!iso && !isoFold && fuseK > 0.0) {
            dfold = smin(dfold, d, fuseK);
        } else {
            dfold = min(dfold, d);
            isoFold = false;
        }

        // Proximity weight: the dominant panel drives refraction direction and
        // reach; neighbours nudge it so bridges bend coherently.
        float w = 1.0 - smoothstep(0.0, fuseK * 3.0 + 24.0, max(d, 0.0));
        reachAcc += min(halfSize.x, halfSize.y) * w;
        densAcc += (dense ? 1.0 : 0.0) * w;
        dirAcc += dir * w;
        wsum += w;
    }

    // Roadmap: interior flat, thin outer ring = convex lens (iPhone-style).
    float bestEdge = 0.0;
    float bestD = 1.0;
    vec2 bestDir = vec2(0.0);
    float density = 0.0;    // 0 = normal material, 1 = dense (HUD panels)
    if (wsum > 0.0001) {
        bestD = dfold;
        bestDir = normalize(dirAcc);
        float reach = max(reachAcc / wsum, 4.0);
        bestEdge = smoothstep(-reach * 0.38, -reach * 0.02, bestD) * bestMask;
        density = clamp(densAcc / wsum, 0.0, 1.0);
    }

    // MODE 1: mask visualization (cyan glow = tiles).
    if (mode == 1) {
        fragColor = vec4(vec3(0.0, bestMask * 0.8, bestMask * 0.8), 1.0);
        return;
    }

    float topBias = clamp(0.65 + bestDir.y * 0.55, 0.0, 1.0);

    // ─── FROSTED PANEL (uPanel) — computed FIRST so tiles can inherit it ───
    // Three tones like the vanilla texture: light FRAME band around the
    // perimeter (~7 gui px, vanilla proportions), light-gray BODY, and the
    // dark cell wells drawn by the tile glass on top of it.
    vec3 panelBase = vec3(0.0);
    float pmask = 0.0;
    if (uPanel.z > 0.5) {
        float pd = sdRoundedBox(px - uPanel.xy, uPanel.zw, uRing.w);
        pmask = 1.0 - smoothstep(-1.0, 1.0, pd);
        if (pmask > 0.001) {
            float edge = smoothstep(-8.0, -1.5, pd);
            vec2 cdir = (px - uPanel.xy) / max(length(px - uPanel.xy), 1e-4);
            vec2 puv = uv + cdir * edge * 4.0 / uScreen.xy;
            vec3 frosted = blur25(puv, texel, 6.0);
            // SAME material as the buttons, denser end: identical cool tint,
            // identical vibrancy — only the veil (0.62) and blur are stronger.
            vec3 base = mix(frosted, frosted * vec3(0.88, 0.96, 1.08) + vec3(0.05, 0.06, 0.09), 0.62);
            base = mix(vec3(dot(base, vec3(0.299, 0.587, 0.114))), base, 1.15);
            // Near the border the bent world shows through more (glass edge).
            float edgeBand = smoothstep(-9.0, -2.0, pd) * (1.0 - smoothstep(-2.0, 0.5, pd));
            base = mix(base, texture(InSampler, puv).rgb, edgeBand * 0.45);
            // Dark outer outline.
            base = mix(base, base * 0.68, (1.0 - smoothstep(0.0, 1.4, abs(pd))) * 0.65);
            // FRAME: lightest band around the perimeter + subtle separating line.
            float distIn = -pd;
            float frameW = uRing.w * 1.75;
            float frameM = 1.0 - smoothstep(frameW - 2.0, frameW + 2.0, distIn);
            base = mix(base, base * 1.05 + vec3(0.045, 0.05, 0.055), frameM * 0.8);
            base *= 1.0 - (1.0 - smoothstep(0.0, 1.5, abs(distIn - frameW))) * 0.10;
            // Inner shadow (thickness cue).
            base *= 1.0 - (1.0 - smoothstep(-4.0, -1.0, pd)) * (0.10 + 0.06 * (1.0 - topBias));
            base += (hash12(floor(px / 2.0)) - 0.5) * 0.015;
            panelBase = base;
        }
    }
    float inPanel = smoothstep(0.0, 1.0, pmask);

    if (bestMask <= 0.001) {
        // Outside tiles: world, or the panel where it exists.
        fragColor = vec4(mix(texture(InSampler, uv).rgb, panelBase, pmask), 1.0);
        return;
    }

    // Hover: sharp falloff - only the tile under the cursor reacts strongly.
    vec2 mouse = uScreen.zw;
    float hover = hoverOn ? (1.0 - smoothstep(20.0, 70.0, length(px - mouse))) : 0.0;

    // Rim band: thin on dense tiles — bright wide rims read as glow chains.
    float rimBand = smoothstep(-5.0 - 2.0 * density, -1.0, bestD)
        * (1.0 - smoothstep(-1.0, 2.0 + density, bestD));
    float refr = bestEdge * (1.0 - rimBand * 0.45) * (1.0 + hover * 0.6);
    // Geometric density (iOS model): thicker glass BENDS more — refraction
    // scales with density instead of the material turning opaque.
    vec2 off = bestDir * (refr * uParams.y * (1.0 + 0.7 * density)) / uScreen.xy;

    // Chromatic aberration — subtle dispersion PROPORTIONAL to the refraction
    // vector. DISABLED on dense panels: on small tiles the lens covers the
    // whole cell and the split turns into rainbow fringing around every slot.
    float ab = aberrationOn ? 0.12 * (1.0 - density) : 0.0;

    // PARALLAX layer 1 (shader): the world seen THROUGH a tile drifts AWAY
    // from the cursor (the item icon on top drifts toward it — Java side).
    // Falls off over ~160 gui px; sampling toward the mouse shifts the
    // apparent content backward.
    vec2 parShift = vec2(0.0);
    // Parallax applies INSIDE the container panel (and in-world, where there
    // is no panel) — tiles OUTSIDE it (creative tabs above the panel) stay
    // static, so tab contents never drift.
    if (uPar.z > 0.0 && (uPanel.z <= 0.5 || pmask > 0.3)) {
        vec2 toM = uPar.xy - px;
        float md = length(toM);
        float fall = 1.0 - smoothstep(0.0, 200.0, md);
        parShift = (toM / max(md, 1e-3)) * fall * uPar.z / uScreen.xy;
    }
    vec2 uvP = uv + parShift;

    // Tiles INSIDE the frosted panel sample the panel base, not the world —
    // otherwise the world would bleed through the cells (wrong distortion at
    // the frame/slot contact).
    vec3 sharp;
    sharp.r = mix(texture(InSampler, uvP + off * (1.0 - ab)).r, panelBase.r, inPanel);
    sharp.g = mix(texture(InSampler, uvP + off).g, panelBase.g, inPanel);
    sharp.b = mix(texture(InSampler, uvP + off * (1.0 + ab)).b, panelBase.b, inPanel);

    // Frosted body with a LENS profile (lensglass rule: never let the blur get
    // as wide as the bend). Dense panels stay clearer; inside the panel the
    // "blur" of the flat base is the base itself.
    bool frostOn = frostR > 0.5;
    vec3 body = sharp;
    if (frostOn) {
        float localFrost = max(frostR * (1.0 - 0.65 * bestEdge) * (1.0 - 0.45 * density), 1.0);
        body = mix(blur25(uvP + off, texel, localFrost), panelBase, inPanel);
    }

    // Near the rim blend in the sharp (aberrated) sample so dispersion survives
    // the frost; the matte centre keeps its frosted density.
    float sharpW = clamp(uParams.w + bestEdge * (frostOn ? 0.55 : 0.0), 0.0, 1.0);
    vec3 glass = mix(body, sharp, sharpW);

    // Material base: mild cool tint for everyone...
    glass = mix(glass, glass * vec3(0.88, 0.96, 1.08) + vec3(0.02, 0.03, 0.05), 0.28 * bestMask);
    float luma = dot(glass, vec3(0.299, 0.587, 0.114));
    // ...VIBRANCY for dense panels (iOS model): saturate the background,
    // keep luminance — never paint over it.
    glass = mix(vec3(luma), glass, 1.0 + 0.35 * density);

    // Fresnel rim: dim baseline glow; the hovered tile flares up.
    float topOnly = rimBand * smoothstep(0.05, 0.45, bestDir.y) * (rimOn ? 1.0 : 0.0);
    vec3 rimTint = vec3(0.95, 0.98, 1.05) * ((0.10 + 0.30 * topBias) * uParams.z) * (1.0 + hover * 1.6);
    glass += topOnly * rimTint;
    // Edge definition: BRIGHT rim on clear tiles only — dense tiles get a
    // whisper of it (their contrast comes from the dark wells, iOS-style).
    float rimAdd = (rimOn ? 0.25 : 0.12) * (1.0 - density * 0.85);
    glass += rimBand * rimAdd * vec3(0.9, 0.95, 1.0) * bestMask;
    glass *= 1.0 - rimBand * (1.0 - topBias) * ((rimOn ? 0.10 : 0.0) - hover * 0.06);
    glass *= 1.0 + hover * 0.10 * bestMask;

    // Thickness cues (iOS model) for dense panels:
    // inner shadow just inside the border (bottom-biased) + bright line under
    // the top edge — light from above makes the panel read as thick glass.
    float innerShadow = (1.0 - smoothstep(-3.5, -0.5, bestD)) * density * bestMask;
    glass *= 1.0 - innerShadow * (0.10 + 0.14 * (1.0 - topBias));
    float topLine = smoothstep(-2.2, -1.2, bestD) * (1.0 - smoothstep(-1.2, -0.2, bestD))
        * smoothstep(0.55, 0.9, bestDir.y) * density * bestMask;
    glass += topLine * vec3(0.05, 0.055, 0.065);

    // Cell grid (dense panels only): BRIGHT convex lattice + DARK recessed
    // wells — vanilla hotbar structure in glass. Grid origin/pitch come
    // EXACTLY from Java (uGrid) — no proximity averaging, so neighbouring
    // tiles (offhand) can never shift the grid. Vanilla slots: +1px origin,
    // 20px pitch, wells ~18x18 gui.
    if (density > 0.4 && uGrid.w > 0.0 && bestMask > 0.001) {
        float rel = px.x - uGrid.x;
        float inX = step(1.0, rel) * step(rel, uGrid.z);
        float fx = mod(rel - 1.0, uGrid.y);
        fx = min(fx, uGrid.y - fx);              // 0 at cell border, pitch/2 at centre
        float fy = abs(px.y - uMeta.z);          // 0 at panel centre
        float lattice = max(1.0 - smoothstep(0.5, 1.2, fx), smoothstep(9.0, 10.0, fy)) * inX;
        float well = smoothstep(1.1, 2.0, fx) * (1.0 - smoothstep(8.2, 9.4, fy)) * inX;
        float wellTop = well * smoothstep(2.0, 7.0, fy);  // darker toward visual top
        glass += lattice * vec3(0.045, 0.05, 0.06) * bestMask;
        glass = mix(glass, glass * 0.80, clamp(well * 0.40 * bestMask, 0.0, 1.0));
        glass = mix(glass, glass * 0.90, clamp(wellTop * 0.25 * bestMask, 0.0, 1.0));
    }

    // Procedural glass texture: fine grain (2px cells) + broad smudge patches.
    float grain = hash12(floor(px / 2.0)) - 0.5;
    float smudge = hash12(floor(px / 96.0)) - 0.5;
    glass += grain * 0.018 * bestMask;
    glass *= 1.0 + smudge * 0.04 * bestMask;

    // Opaque composite: world/panel outside, tile glass inside.
    vec4 outColor = vec4(mix(texture(InSampler, uv).rgb, glass, bestMask), 1.0);

    // Selected-slot ring (uMeta.w/x, uMeta.z/y): drawn ON TOP of everything,
    // slightly larger than the cell so it floats above the panel like on iOS.
    // The ring glides between slots (Java smooths uMeta.w); size from uRing.
    if (uMeta.w >= 0.0 && uMeta.z >= 0.0) {
        vec2 ringC = vec2(uMeta.w, uMeta.z);
        float d = sdRoundedBox(px - ringC, uRing.xy, uRing.w);
        float ring = 1.0 - smoothstep(uRing.z * 0.4, uRing.z, abs(d));
        float glow = exp(-max(abs(d) - uRing.z, 0.0) * 0.5) * 0.22;
        vec3 ringCol = vec3(0.94, 0.97, 1.05);
        outColor.rgb = mix(outColor.rgb, ringCol, ring * 0.9);
        outColor.rgb += ringCol * glow;
    }

    fragColor = outColor;
}
