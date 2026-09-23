#version 330

uniform sampler2D InSampler;
uniform sampler2D PrevSampler;   // captured previous tab frame (uiprev target)
uniform sampler2D BlurredSampler;
uniform sampler2D ColorFieldSampler;
uniform sampler2D DeepFieldSampler;

#define MAX_PANELS 128
#define MAX_WELLS 12
layout(std140) uniform GlassConfig {
    vec4 uRects[MAX_PANELS];
    vec4 uMats[MAX_PANELS]; // x = mat id 0..7, y = elevation 0..6
    vec4 uWells[MAX_WELLS * 3]; // grid well descriptors (see below)
    vec4 uParams;
    vec4 uMeta;     // (count, fuseRadius, ringY, ringX)
    vec4 uScreen;   // (mainW, mainH, mouseX, mouseY)
    vec4 uFlags;    // (mode, hoverOn, edgeFX, frostRadius); edgeFX: +2 aberration, +1 rim
    vec4 uRing;     // (halfW, halfH, lineHalfWidth, cornerRadius) — fb px
    vec4 uGrid;     // (originX, pitch, rightEdge, 0) — hotbar slot grid, fb px
    vec4 uPanel;    // frosted container panel (centre xy, half wh) — fb px
    vec4 uPar;      // parallax: smoothed mouse xy (fb px), strength px, 0
    vec4 uAnim;     // tab transition: (active, progress 0..1, 0, 0)
    vec4 uWellMeta; // (wellCount, cookFill 0..1, tintStrength, 0)
    vec4 uFxFlame;  // furnace flame: (x, y fb px, litIntensity 0..1, radius)
    vec4 uFxChannel;// process channel: (x0, yC, length, halfHeight), fb px
    vec4 uTone;     // appearance: (darkness 0..1 smoothed, tintR, tintG, tintB)
    vec4 uDockParams; // dock: (outerPad, cornerRadius, refraction, density) — §7
    vec4 uLightDir; // light: (x, y, intensity, 0) — rim follows light (§ rim)
    vec4 uSun;      // WOW sun: (linear r, g, b of the dominant light, specMaster 0..2)
    vec4 uBleed;    // (bodyBleed, edgeBleed, chroma, plane: 0 base, 1 upper glass, 2 popup)
    vec4 uCut[16];     // overlay content cutouts (center xy, half wh), fb px
    vec4 uLayer;    // elevation window (lo, hi) plus edge lens width in z and press dent in w
    vec4 uRefl;     // face reflection (strength, width pow, tint mix, sharp boost)
};

in vec2 texCoord;
out vec4 fragColor;

float sdRoundedBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}
float sdSquircle(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    vec2 m = max(q, 0.0);
    float l = pow(pow(m.x, 4.0) + pow(m.y, 4.0), 0.25);
    return min(max(q.x, q.y), 0.0) + l - r;
}
float sdGlassBox(vec2 p, vec2 b, float r) {
    return (min(b.x,b.y) > 20.0) ? sdSquircle(p,b,r) : sdRoundedBox(p,b,r);
}
vec2 glassNormal(vec2 p, vec2 b, float r, bool squir) {
    vec2 q = abs(p) - b + r;
    // inside: closest side
    if (max(q.x, q.y) < 0.0) {
        if (q.x > q.y) return vec2(sign(p.x), 0.0);
        else return vec2(0.0, sign(p.y));
    }
    vec2 m = max(q, 0.0);
    float l = length(m);
    if (l < 1e-4) return vec2(0.0, sign(p.y));
    // squircle uses its own L4 gradient, rounded direction would point elsewhere
    if (squir) {
        vec2 g = vec2(m.x * m.x * m.x, m.y * m.y * m.y) * sign(p);
        if (dot(g, g) > 1e-8) return normalize(g);
    }
    return normalize(m * sign(p));
}
float sdTri(vec2 p, float r) {
    const float k = 1.7320508;
    p.x = abs(p.x) - r;
    p.y = p.y + r / k;
    if (p.x + k * p.y > 0.0) p = vec2(p.x - k * p.y, -k * p.x - p.y) / 2.0;
    p.x -= clamp(p.x, -2.0 * r, 0.0);
    return -length(p) * sign(p.y);
}

// Polynomial smooth minimum (opSmoothUnion lineage): fuses nearby panels into one continuous metaball blob instead of hard overlaps.
float smin(float a, float b, float k) {
    float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
    return mix(b, a, h) - k * h * (1.0 - h);
}
// Numeric SDF gradient for the optical normal: smooth across side/corner transitions where closest-side logic facets
vec2 sdfGradN(vec2 p, vec2 b, float r, bool squir, vec2 fallback) {
    float e = 0.75;
    float dx;
    float dy;
    if (squir) {
        dx = sdSquircle(p + vec2(e, 0.0), b, r) - sdSquircle(p - vec2(e, 0.0), b, r);
        dy = sdSquircle(p + vec2(0.0, e), b, r) - sdSquircle(p - vec2(0.0, e), b, r);
    } else {
        dx = sdRoundedBox(p + vec2(e, 0.0), b, r) - sdRoundedBox(p - vec2(e, 0.0), b, r);
        dy = sdRoundedBox(p + vec2(0.0, e), b, r) - sdRoundedBox(p - vec2(0.0, e), b, r);
    }
    vec2 g = vec2(dx, dy);
    return dot(g, g) > 1e-6 ? normalize(g) : fallback;
}

// HUD icon halos: heart and drumstick SDF in unit space, rim follows the sprite
float sdHeart(vec2 p) {
    float lobeL = length(p - vec2(-0.34, 0.30)) - 0.42;
    float lobeR = length(p - vec2(0.34, 0.30)) - 0.42;
    float chin = sdTri(vec2(p.x, -(p.y + 0.10)), 1.0);
    return smin(smin(lobeL, lobeR, 0.18), chin, 0.18);
}
float sdMeat(vec2 p) {
    float blob = length((p - vec2(-0.12, 0.18)) / vec2(1.0, 0.92)) - 0.52;
    float bone = sdGlassBox(p - vec2(0.38, -0.38), vec2(0.30, 0.16), 0.14);
    return smin(blob, bone, 0.15);
}

// Procedural grain breaks the flat plastic look, no assets needed
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
vec3 blur25Half(vec2 uv, vec2 texel, float r) {
    float w[5] = float[5](1.0, 4.0, 6.0, 4.0, 1.0);
    vec3 sum = vec3(0.0);
    float total = 0.0;
    for (int dx = -2; dx <= 2; dx++) {
        for (int dy = -2; dy <= 2; dy++) {
            float wgt = w[dx + 2] * w[dy + 2];
            sum += texture(BlurredSampler, uv + vec2(dx, dy) * texel * r).rgb * wgt;
            total += wgt;
        }
    }
    return sum / total;
}

vec3 treatHole(vec3 w) {
    float hl = dot(w, vec3(0.299, 0.587, 0.114));
    vec3 hs = clamp(mix(w, vec3(hl), 0.22), 0.05, 0.95);
    vec3 hw = mix(w, hs, 0.38);
    float hb = smoothstep(0.60, 0.92, hl);
    hw *= 1.0 - hb * 0.16;
    hw = mix(hw, vec3(hl), hb * 0.18);
    hw += (1.0 - smoothstep(0.04, 0.22, hl)) * 0.05;
    hw = mix(hw, hw * vec3(0.88, 0.96, 1.08) + vec3(0.02, 0.03, 0.05), 0.28);
    hw = mix(vec3(dot(hw, vec3(0.299, 0.587, 0.114))), hw, 1.15);
    hw *= 1.0 - smoothstep(0.60, 0.85, hl) * 0.12;
    hw *= mix(1.0, 0.55, clamp(clamp(uTone.x, 0.0, 1.0) * 0.8, 0.0, 1.0));
    float htStr = clamp(uWellMeta.z, 0.0, 1.0);
    if (htStr > 0.001) {
        vec3 hTint = clamp(uTone.yzw, vec3(0.05), vec3(1.0));
        vec3 hHue = hTint / max(dot(hTint, vec3(0.333)), 0.06);
        hw *= mix(vec3(1.0), mix(vec3(1.0), hHue, 0.45), htStr * 0.15);
    }
    hw = hw * (1.0 + 0.04 * smoothstep(0.15, 0.55, hl)) + vec3(0.030, 0.033, 0.038) * smoothstep(0.15, 0.55, hl);
    return hw;
}

void main() {
    // gl_FragCoord = framebuffer pixels (bottom-origin); uv = 0..1 over screen.
    vec2 px = gl_FragCoord.xy;
    vec2 uv = px / uScreen.xy;
    vec2 texel = 1.0 / uScreen.xy;
    vec2 texelHalf = texel * 1.538; // 1/0.65 for 0.65x blurred target

    // Local luminance tames glare on bright worlds, lifts dark ones
    	float bgL = dot(texture(InSampler, uv).rgb, vec3(0.299, 0.587, 0.114));

    int mode = int(uFlags.x + 0.5);
    int uCount = int(uMeta.x + 0.5);
    bool hoverOn = uFlags.y > 0.5;
    bool aberrationOn = uFlags.z >= 1.5;
    bool rimOn = mod(uFlags.z, 2.0) >= 0.5;
    float frostR = uFlags.w;

    // DIAG uScreen block arrived green else red
    if (mode == 3) { bool ok = uScreen.x > 1.0; fragColor = vec4(ok ? 0.0 : 1.0, ok ? 1.0 : 0.0, 0.0, 1.0); return; }
    // DIAG tile count as red ramp
    if (mode == 4) { float c = clamp(uMeta.x / 20.0, 0.0, 1.0); fragColor = vec4(c, 0.0, 0.0, 1.0); return; }
    // DIAG first tile rect arrived green else red
    if (mode == 5) { bool ok = uRects[0].x > 1.0; fragColor = vec4(ok ? 0.0 : 1.0, ok ? 1.0 : 0.0, 0.0, 1.0); return; }
    // MODE 6: solid magenta — proves the chain's output lands in minecraft:main at THIS point of the frame (independent of any UBO/uniform concerns).
    if (mode == 6) { fragColor = vec4(1.0, 0.0, 1.0, 1.0); return; }

    // MODE 0: raw capture passthrough (isolation test).
    if (mode == 0) {
        fragColor = vec4(texture(InSampler, uv).rgb, 1.0);
        return;
    }
    // TAILSRC: raw overlay input, shows exactly what the TAIL run samples
    if (mode == 18) {
        fragColor = vec4(texture(InSampler, uv).rgb, 1.0);
        return;
    }

    // Fusion radius melts nearby tiles, slots and dense never fuse
    float H = uPanel.w * 2.0;
    bool anim = uAnim.x > 0.5 && uPanel.z > 0.5;
    float offNew = 0.0;
    float oldOff = 0.0;
    float animP = 0.0;
    if (anim) {
        animP = clamp(uAnim.w, 0.0, 1.0);
        float eOut = 1.0 - (1.0 - animP) * (1.0 - animP) * (1.0 - animP);
        offNew = (1.0 - eOut) * H;               // new panel starts H above
        oldOff = animP * animP * H;              // old frame slides up (easeIn)
    }
    vec2 pxT = px - vec2(0.0, offNew);           // tile-space position
    vec2 uvT = uv - vec2(0.0, offNew / uScreen.y); // tile-space sample
    float fuseK = max(uMeta.y, 0.0);
    float bestMask = 0.0;
    float slotCoreMask = 0.0;
    float dfold = 1e9;      // fused SDF across all panels
    float foldElev = 0.0;   // elev of the fold bucket, bridges form inside one elev only
    float foldGrp = 0.0;    // fuse group of the fold, bridges form inside one group only
    float dminFold = 1e9;   // hard min across all, waist source for the global bridge
    float dfuse0 = 1e9;     // union rim field of group 0
    bool isoFuse0 = false;
    float dmin0 = 1e9;      // hard min of group 0, depth source for the union test
    float dfuseElev0 = 1e9;
    float dfuseElev1 = 1e9; // elev of the group 0 bucket, same-height gate
    float dfuse1 = 1e9;     // union rim field of group 1 (book plus active tab)
    bool isoFuse1 = false;
    float dmin1 = 1e9;
    bool isoFold = false;   // dfold currently belongs to a fusion-exempt tile
    vec2 fuseN = vec2(0.0, 1.0); // running smin-weighted fused normal
    bool hasFuseN = false;
    float reachAcc = 0.0;   // proximity-weighted lens reach
    float densAcc = 0.0;    // proximity-weighted "dense HUD" factor
    float slotAcc = 0.0;    // proximity-weighted "slot cell" factor
    float groupAcc = 0.0;   // proximity-weighted "functional group" factor
    float controlAcc = 0.0; // proximity-weighted "interactive glass" factor
    float controlMax = 0.0; // max w for control — no dilution when many
    float nearestReach = 4.0;
    float compAcc = 0.0;    // proximity-weighted "companion surface" factor
    float activeAcc = 0.0;  // proximity-weighted "active/selected" factor
    float dockAcc = 0.0;    // proximity-weighted "luminance dock" factor
    float popupAcc = 0.0;   // proximity-weighted popup matte factor
    float cardAcc = 0.0;    // proximity-weighted card matte factor
    float iconRim = 0.0;    // per-icon rim ring, survives neighbour overlap
    vec2 dirAcc = vec2(0.0);
    float wsum = 0.0;
    float dNear = 1e9;
    float nearGrp = -1.0;      // nearest tile — fallback direction source
    float dNearSlot = 1e9;
    vec2 nearDir = vec2(0.0, 1.0);
    vec2 nearCenter = vec2(0.0); vec2 nearHalf = vec2(4.0);
    // Topmost covering tile wins, same group and height melts instead
    int topIdx = -1;
    float topD = 1e9;
    vec2 topDir = vec2(0.0, 1.0);
    float topReach = 4.0;
    float topMat = -1.0;
    float topElev = -1e9;
    float topGrp = 0.0;
    vec2 topC = vec2(0.0);
    vec2 topHalf = vec2(4.0);
    float topOut = 1e9;
    float topFused = 1e9;   // smin within the top bucket only, buried edges stay hidden
    float topMin = 1e9;     // hard min within the top bucket, waist source
    int topCoverCount = 0;  // covering tiles inside the top bucket
    bool topFusable = false;
    vec2 topFuseN = vec2(0.0, 1.0);
    bool hasTopFuseN = false;
    int coverCountAll = 0; // solid non-slot tiles covering px, any layer

    // Материальные роли Liquidum: 0 BASE, 1 SLOT, 2 GROUP, 3 CONTROL, 4 COMPANION, 5 ACTIVE, 6 DENSE(HUD), 7 DOCK. Один материал — разные параметры.
    float overlayPlane = uBleed.w;
    for (int i = 0; i < MAX_PANELS; i++) {
        if (i >= uCount) break;
        vec4 rect = uRects[i];
        float matId = uMats[i].x;
        float elev = uMats[i].y;
        float fgrp = uMats[i].z;
        // Plane split: base draws backing plus below-window sheets, upper its own slice, TAIL popup
        bool isPopup = abs(matId - 8.0) < 0.5;
        bool sheet = (matId > 1.5 && matId < 2.5) || (matId > 2.5 && matId < 3.5) || (matId > 3.5 && matId < 4.5) || (matId > 4.5 && matId < 5.5) || (matId > 5.5 && matId < 6.5) || (matId > 8.5);
        if (overlayPlane < 0.5) { if (isPopup) continue; if (sheet && elev > uLayer.x) continue; }
        else if (overlayPlane < 1.5) { if (isPopup || !sheet || elev <= uLayer.x || elev > uLayer.y) continue; }
        else if (overlayPlane < 2.5) { if (!isPopup) continue; }
        else { if (isPopup || !sheet || elev <= uLayer.x || elev > uLayer.y) continue; }
        bool dense = matId > 5.5 && matId < 6.5;           // MAT_DENSE
        bool dock = matId > 6.5 && matId < 7.5;             // MAT_DOCK
        bool popup = matId > 7.5 && matId < 8.5;            // MAT_POPUP boosted matte
        bool card = matId > 8.5;                            // MAT_CARD static full matte
        bool slot = matId > 0.5 && matId < 1.5;         // MAT_SLOT
        // Overlay fuses only inside group 1000 (source plus bridge plus popup), cards never melt
        bool fusable = !dense && !slot && !dock && !card && (matId < 7.5 || abs(fgrp - 1000.0) < 0.5);
        vec2 halfSize = abs(rect.zw);
        if (halfSize.x <= 0.0 || halfSize.y <= 0.0) continue;

        vec2 radial = pxT - rect.xy;
        float radF = clamp(uParams.x, 0.0, 1.0) * (dense ? 1.35 : 1.0);
        // Cap corner radius so narrow panels never become capsules
        float guiScale = max(uRing.w / 4.0, 0.5);
        bool isComp = matId > 3.5 && matId < 4.5;
        bool isDock = matId > 6.5 && matId < 7.5;
        float rCap = isDock ? max(uDockParams.y, 0.0) : min(16.0 * guiScale, min(halfSize.x, halfSize.y) * 0.35);
        // Shape select from uMats w: 0 box, 1 triangle, 2 circle, 5 squircle. Fraction 0.0 keeps the legacy auto corner, else explicit 0..1.
        float shapeW = uMats[i].w;
        float shapeId = floor(shapeW + 0.0001);
        float cornerF = shapeW - shapeId;
        bool isTri = shapeId > 0.5 && shapeId < 1.5;
        bool isCircle = shapeId > 1.5 && shapeId < 2.5;
        bool isHeart = shapeId > 2.5 && shapeId < 3.5;
        bool isMeat = shapeId > 3.5 && shapeId < 4.5;
        bool isSquircle = shapeId > 4.5 && shapeId < 5.5;
        float cornerOv = cornerF < 0.25 ? -1.0 : clamp((cornerF - 0.5) / 0.499, 0.0, 1.0);
        float minHalf = min(halfSize.x, halfSize.y);
        float d;
        vec2 dir;
        float boxR = 0.0;
        bool boxLike = false;
        if (isCircle) {
            float rl = length(radial);
            d = rl - minHalf;
            dir = rl > 1e-3 ? radial / rl : vec2(0.0, 1.0);
        } else if (isTri) {
            float triR = min(halfSize.x / 0.8660254, halfSize.y / 0.75);
            d = sdTri(radial, triR);
            float rl = length(radial);
            dir = rl > 1e-3 ? radial / rl : vec2(0.0, 1.0);
        } else if (isHeart && !dock) {
            d = sdHeart(radial / minHalf) * minHalf;
            float rl = length(radial);
            dir = rl > 1e-3 ? radial / rl : vec2(0.0, 1.0);
        } else if (isMeat && !dock) {
            d = sdMeat(radial / minHalf) * minHalf;
            float rl = length(radial);
            dir = rl > 1e-3 ? radial / rl : vec2(0.0, 1.0);
        } else if (isSquircle) {
            float sqR;
            if (cornerOv >= 0.0) sqR = cornerOv >= 0.98 ? minHalf : cornerOv * minHalf;
            else sqR = min(16.0 * guiScale, minHalf * 0.35);
            d = sdSquircle(radial, halfSize, sqR);
            dir = glassNormal(radial, halfSize, sqR, true);
            boxR = sqR; boxLike = true;
        } else {
            // Etalon pill reaches full capsule at the default fraction
            bool hero = (matId > 2.5 && matId < 3.5) || (matId > 4.5 && matId < 5.5);
            float cornerR;
            if (cornerOv >= 0.0) cornerR = cornerOv >= 0.98 ? minHalf : cornerOv * minHalf;
            else cornerR = hero ? minHalf * smoothstep(0.0, 0.18, uParams.x) : min(radF * minHalf, rCap);
            d = sdRoundedBox(radial, halfSize, cornerR);
            dir = glassNormal(radial, halfSize, cornerR, false);
            boxR = cornerR; boxLike = true;
        }
        // Optical normal reads the SDF gradient near the edge: closest-side facets into sapphire at high refraction. Far tiles keep the cheap dir, smin weights them out anyway
        if (boxLike && abs(d) < 16.0) dir = sdfGradN(radial, halfSize, boxR, isSquircle, dir);
        // Slot wells: interior must be CLEAR (no glass colour), only wall is glass (§22-23)
        if (slot) {
            float wWslot = 2.5 * guiScale;
            float depthS = -d;
            float wallSlot = smoothstep(0.0, 2.5, depthS) * (1.0 - smoothstep(wWslot, wWslot + 2.5, depthS));
            bestMask = max(bestMask, wallSlot);
            slotCoreMask = max(slotCoreMask, smoothstep(0.0, 2.5, depthS));
        } else {
            bestMask = max(bestMask, 1.0 - smoothstep(-2.5, 2.5, d));
        }
        if (dock) {
            float iconBand = smoothstep(-2.8, -0.6, d) * (1.0 - smoothstep(-0.6, 1.8, d));
            iconRim = max(iconRim, iconBand);
        }

        // Far panels neither mask nor fuse — skip early (ALU saving).
        if (d > fuseK * 3.0 + 24.0) continue;

        if (d < dNear) { dNear = d; nearDir = dir; nearestReach = min(halfSize.x, halfSize.y); nearCenter = rect.xy; nearHalf = halfSize; nearGrp = fgrp; }
        if (slot) dNearSlot = min(dNearSlot, d);
        if (!slot) { topOut = min(topOut, max(d, 0.0)); }

        bool iso = !fusable;   // dense HUD panels and slots never fuse
        // per-material fusion radius ChatGPT: GridWell 8 / Freeform 9-11 / GROUP 11-13 / large 14
        float matFuse = fuseK;
        if (slot) matFuse *= 0.62; // ~8.6 when fuseK=14 -> GridWell 8-9
        else if (matId > 1.5 && matId < 2.5) matFuse *= 0.82; // GROUP 11-13
        else if (matId > 3.5 && matId < 4.5) matFuse *= 0.92; // COMPANION large
        float tileMinGui = min(halfSize.x, halfSize.y) / max(uRing.w / 4.0, 0.5);
        matFuse *= clamp(tileMinGui / 20.0, 0.25, 1.0); // small tiles fuse tight, large sheets metaball
        // Fusion melts inside one height only, elevation sets the optical plane
        // Solid cover claims pixels, slots stay clear holes under glass
        if (d < 0.0 && !slot) {
            coverCountAll++;
            if (topIdx < 0) {
                topIdx = i; topD = d; topDir = dir; topReach = min(halfSize.x, halfSize.y);
                topMat = matId; topElev = elev; topGrp = fgrp; topC = rect.xy; topHalf = halfSize;
                topFused = d; topMin = d; topCoverCount = 1; topFusable = fusable;
                topFuseN = dir; hasTopFuseN = true;
            } else {
                bool sameLayer = abs(fgrp - topGrp) < 0.5;
                bool sameHeight = abs(elev - topElev) <= 0.25;
                if (sameLayer && sameHeight && fusable && topFusable) {
                    topCoverCount++;
                    topMin = min(topMin, d);
                    if (matFuse > 0.0 && fuseK > 0.0) topFused = smin(topFused, d, min(matFuse, fuseK));
                    else topFused = min(topFused, d);
                    vec2 fnbt = hasTopFuseN ? mix(dir, topFuseN, clamp(0.5 + 0.5 * (d - topFused) / max(min(matFuse, fuseK), 1e-3), 0.0, 1.0)) : dir;
                    if (length(fnbt) > 1e-3) { topFuseN = normalize(fnbt); hasTopFuseN = true; }
                } else if (elev > topElev + 0.25 || (abs(elev - topElev) <= 0.25 && i >= topIdx)) {
                    topIdx = i; topD = d; topDir = dir; topReach = min(halfSize.x, halfSize.y);
                    topMat = matId; topElev = elev; topGrp = fgrp; topC = rect.xy; topHalf = halfSize;
                    topFused = d; topMin = d; topCoverCount = 1; topFusable = fusable;
                    topFuseN = dir; hasTopFuseN = true;
                }
            }
        }
        // Bucket halo fuses before physical overlap, same layer and height only
        if (topIdx >= 0 && d >= 0.0 && !slot && fusable && topFusable
            && abs(fgrp - topGrp) < 0.5 && abs(elev - topElev) <= 0.25
            && matFuse > 0.0 && d < max(min(matFuse, fuseK), 1e-3) * 2.0) {
            float fhk2 = max(min(matFuse, fuseK), 1e-3);
            vec2 fnb2 = hasTopFuseN ? mix(dir, topFuseN, clamp(0.5 + 0.5 * (d - topFused) / fhk2, 0.0, 1.0)) : dir;
            if (length(fnb2) > 1e-3) { topFuseN = normalize(fnb2); hasTopFuseN = true; }
            topFused = smin(topFused, d, min(matFuse, fuseK));
        }
        // dense/dock already iso, no fuse
        if (dfold > 1e8) {
            dfold = d;
            isoFold = iso;
            foldElev = elev;
            foldGrp = fgrp;
            dminFold = d;
            if (!iso) { fuseN = dir; hasFuseN = true; }
        } else if (!iso && !isoFold && matFuse > 0.0 && abs(fgrp - foldGrp) < 0.5
            && abs(elev - foldElev) <= 0.25) {
            float fhk = max(min(matFuse, fuseK), 1e-3);
            vec2 fnb = hasFuseN ? mix(dir, fuseN, clamp(0.5 + 0.5 * (d - dfold) / fhk, 0.0, 1.0)) : dir;
            if (length(fnb) > 1e-3) { fuseN = normalize(fnb); hasFuseN = true; }
            dfold = smin(dfold, d, matFuse);
            dminFold = min(dminFold, d);
        } else {
            // Retargeted bucket keeps its own group, height and hard min
            if (!iso && d < dfold) { dfold = d; foldGrp = fgrp; foldElev = elev; dminFold = d; fuseN = dir; hasFuseN = true; }
            else dfold = min(dfold, d);
            isoFold = false;
        }
        // Per-group union keeps shared rims clean, slots stay out
        if (fusable && abs(fgrp - 1.0) < 0.5) {
            if (dfuse1 > 1e8) { dfuse1 = d; isoFuse1 = false; dfuseElev1 = elev; dmin1 = d; }
            else if (abs(elev - dfuseElev1) <= 0.25) {
                if (matFuse > 0.0) dfuse1 = smin(dfuse1, d, matFuse);
                else dfuse1 = min(dfuse1, d);
                dmin1 = min(dmin1, d);
            }
        } else if (fusable && abs(fgrp) < 0.5) {
            if (dfuse0 > 1e8) { dfuse0 = d; isoFuse0 = false; dfuseElev0 = elev; dmin0 = d; }
            else if (abs(elev - dfuseElev0) <= 0.25) {
                if (matFuse > 0.0) dfuse0 = smin(dfuse0, d, matFuse);
                else dfuse0 = min(dfuse0, d);
                dmin0 = min(dmin0, d);
            }
        }

        // Proximity weight: the dominant panel drives refraction direction and reach; neighbours nudge it so bridges bend coherently.
        float w = 1.0 - smoothstep(0.0, fuseK * 3.0 + 24.0, max(d, 0.0));
        reachAcc += min(halfSize.x, halfSize.y) * w;
        densAcc += (dense ? 1.0 : 0.0) * w;
        // Slot: interior должен быть CLEAR — учитываем только wall, не всю плитку (§22)
        if (slot) {
            float wWslot2 = 2.5 * guiScale;
            float depthS2 = -d;
            float wallSlot2 = smoothstep(0.0, 2.5, depthS2) * (1.0 - smoothstep(wWslot2, wWslot2 + 2.5, depthS2));
            slotAcc += wallSlot2;
        }
        groupAcc += ((matId > 1.5 && matId < 2.5) ? 1.0 : 0.0) * w;
        controlAcc += ((matId > 2.5 && matId < 3.5) ? 1.0 : 0.0) * w;
        controlMax = max(controlMax, ((matId > 2.5 && matId < 3.5) ? w : 0.0));
        compAcc += ((matId > 3.5 && matId < 4.5) ? 1.0 : 0.0) * w;
        activeAcc += ((matId > 4.5 && matId < 5.5) ? 1.0 : 0.0) * w;
        dockAcc += (dock ? 1.0 : 0.0) * w;
        popupAcc += (popup ? 1.0 : 0.0) * w;
        cardAcc += (card ? 1.0 : 0.0) * w;
        dirAcc += dir * w;
        wsum += w;
    }

    // Roadmap: interior flat, thin outer ring = convex lens (iPhone-style).
    float bestEdge = 0.0;
    float bestD = 1.0;
    vec2 bestDir = vec2(0.0);
    float reach = 4.0;      // lens reach of the dominant tile (fb px)
    float density = 0.0;    // DENSE HUD (hotbar)
    float slotLevel = 0.0;  // SLOT cell
    float groupLevel = 0.0; // FUNCTIONAL GROUP
    float controlLevel = 0.0; // INTERACTIVE GLASS
    float compLevel = 0.0;  // COMPANION surface
    float activeLevel = 0.0;// ACTIVE/selected state
    float dockLevel = 0.0;  // LUMINANCE DOCK
    float popupLevel = 0.0; // POPUP matte
    float cardLevel = 0.0;  // CARD full matte
    // Same-group melt lifts coverage so joints read as one surface. dfold bridge covers any fuse group (Lab zOrder layers), dfuse0/1 keep legacy groups.
    if (dmin0 < 1e8 && dfuse0 < 1e8) bestMask = max(bestMask, 1.0 - smoothstep(-2.5, 2.5, dfuse0));
    if (dmin1 < 1e8 && dfuse1 < 1e8) bestMask = max(bestMask, 1.0 - smoothstep(-2.5, 2.5, dfuse1));
    if (dfold < 1e8 && !isoFold && dminFold < 1e8) {
        float waistG = dminFold - dfold;
        if (waistG > 0.25) bestMask = max(bestMask, 1.0 - smoothstep(-2.5, 2.5, dfold));
    }
    if (wsum > 0.0001) {
        bestD = dfold;
        // Fallback: at 4-tile intersections dirAcc cancels to ~zero and normalize() blows up (dark dots). Use the nearest tile's direction.
        bestDir = length(dirAcc) > 1e-3 ? normalize(dirAcc)
                                        : ((dNear < 1e8) ? nearDir : vec2(0.0, 1.0));
        // for many controls, use per-tile direction to keep highlight as band, not point
        if (controlLevel > 0.6 && dNear < 1e8) {
            bestDir = nearDir;
        }
        // for control, use nearest reach, not averaged — no point for lower when many
        if (controlLevel > 0.6) {
            reach = max(nearestReach, 4.0);
        } else {
            reach = max(reachAcc / wsum, 4.0);
        }
        bestEdge = smoothstep(-reach * 0.38, -reach * 0.02, bestD) * bestMask;
        density = clamp(densAcc / wsum, 0.0, 1.0);
        slotLevel = clamp(slotAcc / wsum, 0.0, 1.0);
        groupLevel = clamp(groupAcc / wsum, 0.0, 1.0);
        controlLevel = clamp(max(controlAcc / wsum, controlMax), 0.0, 1.0);
        compLevel = clamp(compAcc / wsum, 0.0, 1.0);
        activeLevel = clamp(activeAcc / wsum, 0.0, 1.0);
        dockLevel = clamp(dockAcc / wsum, 0.0, 1.0);
        popupLevel = clamp(popupAcc / wsum, 0.0, 1.0);
        cardLevel = clamp(cardAcc / wsum, 0.0, 1.0);
    }
    // Covered pixels read the top layer only, buried edges stay hidden
    // fuseW weighs the analytic smin gradient: singletons keep their own normal, melts bend as one surface
    float fuseW = 0.0;
    if (topIdx >= 0) {
        bool topMelted = topCoverCount >= 2 && topFused < 1e8 && topMin < 1e8;
        if (topFused < 1e8 && topMin < 1e8) fuseW = clamp((topMin - topFused) / 2.0, 0.0, 1.0);
        bestD = topFused;
        bestDir = topDir;
        if (hasTopFuseN && length(topFuseN) > 1e-3 && fuseW > 0.001) bestDir = normalize(mix(topDir, topFuseN, fuseW));
        reach = max(topReach, 4.0);
        density = topMat > 5.5 && topMat < 6.5 ? 1.0 : 0.0;
        slotLevel = 0.0;
        groupLevel = topMat > 1.5 && topMat < 2.5 ? 1.0 : 0.0;
        controlLevel = topMat > 2.5 && topMat < 3.5 ? 1.0 : 0.0;
        compLevel = topMat > 3.5 && topMat < 4.5 ? 1.0 : 0.0;
        activeLevel = topMat > 4.5 && topMat < 5.5 ? 1.0 : 0.0;
        dockLevel = topMat > 6.5 && topMat < 7.5 ? 1.0 : 0.0;
        popupLevel = topMat > 7.5 && topMat < 8.5 ? 1.0 : 0.0;
        cardLevel = topMat > 8.5 ? 1.0 : 0.0;
        bestEdge = smoothstep(-reach * 0.38, -reach * 0.02, bestD) * bestMask;
        // Fused bridge interior reads flat: no edge bend where one object continues
        float layFB = 1e9;
        if (topMelted) layFB = topFused;
        else if (abs(topGrp - 1.0) < 0.5) layFB = dfuse1;
        else if (abs(topGrp) < 0.5) layFB = dfuse0;
        if (layFB < 1e8) {
            float bridge = (topMelted ? topMin : topD) - layFB;
            bestEdge *= 1.0 - smoothstep(0.3, 2.0, bridge) * (1.0 - smoothstep(5.0, 9.0, bridge));
        }
    }
    // Cover field only sizes the tile, refraction direction comes from the SDF below
    vec2 covC = topIdx >= 0 ? topC : nearCenter;
    vec2 covH = topIdx >= 0 ? topHalf : nearHalf;
    vec2 relN = (pxT - covC) / max(covH, vec2(1.0));
    vec2 edgeN = relN / max(length(relN), 1e-3);
    // NORMAL SDF refraction direction as color
    if (mode == 10) {
        vec2 dbgN = length(bestDir) > 1e-3 ? normalize(bestDir) : edgeN;
        fragColor = vec4(dbgN * 0.5 + 0.5, 0.0, 1.0);
        return;
    }
    // ELEV covering tile height as gray
    if (mode == 14) {
        float e = topIdx >= 0 ? clamp(topElev / 6.0, 0.0, 1.0) : 0.0;
        fragColor = vec4(vec3(e), 1.0);
        return;
    }
    // FUSION lanes: default 10+ cycles colors, explicit 0-3 keep theirs
    if (mode == 15) {
        float gRaw = topIdx >= 0 ? topGrp : nearGrp;
        float g = gRaw > 9.5 && gRaw < 999.5 ? mod(gRaw - 10.0, 4.0) : gRaw;
        vec3 c = vec3(0.05);
        if (g > -0.5 && g < 0.5) c = vec3(1.0, 0.25, 0.25);
        else if (g > 0.5 && g < 1.5) c = vec3(0.25, 1.0, 0.25);
        else if (g > 1.5 && g < 2.5) c = vec3(0.3, 0.5, 1.0);
        else if (g > 2.5 && g < 999.5) c = vec3(1.0, 0.85, 0.3);
        else if (g > 999.5) c = vec3(0.75, 0.35, 1.0);
        if (topIdx >= 0 && topCoverCount >= 2) c = mix(c, vec3(1.0), 0.4);
        fragColor = vec4(c, 1.0);
        return;
    }
    // TOPMOST TILE: winning material as color, dark when no tile covers
    if (mode == 17) {
        vec3 c = vec3(0.02);
        if (topIdx >= 0) {
            float m = topMat;
            c = vec3(fract(m * 0.37 + 0.11), fract(m * 0.73 + 0.29), fract(m * 0.13 + 0.61));
        }
        fragColor = vec4(c, 1.0);
        return;
    }
    float effDensity = max(density, controlLevel * 0.50);
    float guiScaleE = max(uRing.w / 4.0, 0.5);
    float minHalfGui = min(covH.x, covH.y) / guiScaleE;
    float sizeK = clamp((minHalfGui - 10.0) / 50.0, 0.0, 1.0);
    effDensity = clamp(effDensity + sizeK * 0.22, 0.0, 1.0);
    // Upper diagnostic layer keeps lower passes outside its own tiles
    if (overlayPlane > 2.5 && bestMask <= 0.001) {
        fragColor = vec4(texture(InSampler, uv).rgb, 1.0);
        return;
    }
    // MODE 1: mask visualization (cyan glow = tiles).
    if (mode == 1) {
        fragColor = vec4(vec3(0.0, bestMask * 0.8, bestMask * 0.8), 1.0);
        return;
    }
    // SDF fused distance field as gray
    if (mode == 7) {
        float v = clamp(0.5 - bestD * 0.04, 0.0, 1.0);
        fragColor = vec4(vec3(v), 1.0);
        return;
    }
    // EDGE lens gate as white
    if (mode == 8) {
        fragColor = vec4(vec3(bestEdge), 1.0);
        return;
    }
    // BLUR frosted body sample alone
    if (mode == 9) {
        vec3 b = blur25Half(uv, texelHalf, min(max(frostR, 2.0) * 0.65, 2.0));
        fragColor = vec4(b, 1.0);
        return;
    }

    // GridWell: one descriptor per grid, cells resolved from pixels
    float wellMask = 0.0;      // wall ring only — interior is clear world
    float coreMask = 0.0;      // clear hole interior (no glass colour)
    float wellHover = 0.0;
    vec2 cellC = vec2(0.0);
    vec2 cellHalf = vec2(1.0);
    vec2 wellBend = vec2(0.0);   // inward refraction in the concave wall (P1)
    int wCount = overlayPlane > 0.5 ? 0 : int(uWellMeta.x + 0.5);
    for (int wq = 0; wq < MAX_WELLS; wq++) {
        if (wq >= wCount) break;
        vec4 A = uWells[wq * 3];
        vec4 B = uWells[wq * 3 + 1];
        vec4 C = uWells[wq * 3 + 2];
        if (B.z < 0.5 || B.w < 0.5) continue;
        vec2 pitch = B.xy;
        // Bounding box всей сетки (центр ячейки [0][0] + extent).
        vec2 farC = A.xy + vec2((B.z - 1.0) * pitch.x, -(B.w - 1.0) * pitch.y);
        vec2 bbC = (A.xy + farC) * 0.5;
        vec2 bbH = abs(farC - A.xy) * 0.5 + A.zw + 2.0;
        if (any(greaterThan(abs(px - bbC), bbH))) continue;
        float ci = clamp(floor((px.x - A.xy.x) / pitch.x + 0.5), 0.0, B.z - 1.0);
        float ri = clamp(floor((A.xy.y - px.y) / pitch.y + 0.5), 0.0, B.w - 1.0);
        vec2 cc = A.xy + vec2(ci * pitch.x, -ri * pitch.y);
        float radFw = clamp(uParams.x, 0.0, 1.0) * 0.9;
        float d = sdGlassBox(px - cc, A.zw, radFw * min(A.z, A.w));
        // wall / core for this cell
        float gsc = max(uRing.w / 4.0, 0.5);
        float rf = clamp(uParams.x, 0.0, 1.0) * 0.9;
        float wW = 2.5 * gsc;
        float innerR = min(max(rf * min(A.z, A.w) + 1.0, 1.2 * gsc), min(A.z, A.w));
        float iD = sdGlassBox(px - cc, A.zw, innerR);
        float oD = sdGlassBox(px - cc, A.zw + wW, innerR + wW);
        float soft = 2.5;
        float core = smoothstep(0.0, soft, -iD);
        float prof = sin(3.14159 * clamp(-iD / wW, 0.0, 1.0));
        if (prof > wellMask || core > coreMask) {
            if (prof > wellMask) {
                wellMask = prof;
                cellC = cc;
                cellHalf = A.zw;
                wellHover = (ci == C.x && ri == C.y) ? 1.0 : 0.0;
            }
            coreMask = max(coreMask, core);
            vec2 inward = (length(px - cc) > 1e-3) ? normalize(cc - px) : vec2(0.0, 1.0);
            float bendWellPx = min(7.0 * (0.35 + 0.65 * (uParams.y / 15.0)), min(A.z, A.w) * 0.6);
            // Hover lifts light only (hovBand below), never deepens the dip
            wellBend += inward * prof * bendWellPx / uScreen.xy;
        }
    }

    // Furnace FX: flame spill and burn channel from menu progress
    vec3 fxAdd = vec3(0.0);
    float fxDark = 0.0;
    if (uFxFlame.z > 0.003) {
        vec2 fv = px - uFxFlame.xy;
        float rad = max(uFxFlame.w, 1.0);
        float spill = exp(-dot(fv, fv) / (rad * rad)) * uFxFlame.z;
        fxAdd += spill * vec3(0.26, 0.12, 0.028);   // тёплый spill — ЛОКАЛЬНЫЙ
    }
    if (uFxChannel.z > 1.0) {
        vec2 chc = vec2(uFxChannel.x + uFxChannel.z * 0.5, uFxChannel.y);
        vec2 chh = vec2(uFxChannel.z * 0.5, uFxChannel.w);
        float cd = sdGlassBox(px - chc, chh, chh.y * 0.8);
        float groove = 1.0 - smoothstep(0.5, 1.6, abs(cd));
        fxDark += groove * 0.07;                     // слабый idle-желоб
        float fillX = uFxChannel.x + uFxChannel.z * clamp(uWellMeta.y, 0.0, 1.0);
        fxAdd += groove * step(px.x, fillX) * vec3(0.045, 0.038, 0.027);
        // specular-блик на фронте процесса
        fxAdd += groove * exp(-abs(px.x - fillX) * 0.30)
            * step(0.01, uWellMeta.y) * vec3(0.09, 0.08, 0.06);
    }

    // Well-ячейки наследуют роль SLOT (тихая recessed-поверхность).
    slotLevel = max(slotLevel, wellMask);
    // Lens runs before the panel so panelBase itself refracts, one transformation for all frequencies
    // Flat front, edge-only bend, dome volume removed. Fused top bucket reads its fused field (no buried rim), cover reads own edge.
    float rimSDF = topD;
    float layFuse = 1e9;
    bool topMeltRim = topIdx >= 0 && topCoverCount >= 2 && topFused < 1e8 && topMin < 1e8;
    if (topIdx < 0) {
        float waist = 0.0;
        if (dmin0 < 1e8) waist = max(waist, dmin0 - dfuse0);
        if (dmin1 < 1e8) waist = max(waist, dmin1 - dfuse1);
        if (dminFold < 1e8 && dfold < 1e8) waist = max(waist, dminFold - dfold);
        rimSDF = (controlLevel > 0.6 && waist <= 0.3) ? dNear : bestD;
    } else if (topMeltRim) {
        rimSDF = topFused;
        layFuse = topFused;
    } else {
        // Union rim where a same-group partner deepens the field, own edge elsewhere Buried edge (partner just below the border) draws no rim at all
        float layMin = 1e9;
        if (abs(topGrp - 1.0) < 0.5) { layFuse = dfuse1; layMin = dmin1; }
        else if (abs(topGrp) < 0.5) { layFuse = dfuse0; layMin = dmin0; }
        float coverDiff = topD - dNear;
        if (coverDiff > 0.5 && coverDiff < 6.0 && layFuse < 1e8) rimSDF = layFuse;
        else if (layFuse < 1e8 && layMin < 1e8 && (layMin - layFuse) > 0.3) rimSDF = layFuse;
        // Halo-deepened bucket union draws its own rim for any group
        if (topFused < 1e8 && topMin < 1e8 && (topMin - topFused) > 0.25) { rimSDF = topFused; layFuse = topFused; }
    }
    float edgeW = clamp(1.10 + 0.008 * reach + popupLevel * 1.2, 1.0, 2.6);
    float rimBand = smoothstep(-edgeW, -0.5, rimSDF) * (1.0 - smoothstep(-0.5, 1.2, rimSDF));
    // Inner edge sits over lower glass, outer edge over world
    float overGlass = (topIdx >= 0 && coverCountAll > topCoverCount) ? 1.0 : 0.0;
    float overGlassSuppress = mix(0.85, 0.20, clamp(controlLevel, 0.0, 1.0));
    rimBand *= 1.0 - overGlass * overGlassSuppress;
    rimBand *= 1.0 - fuseW * 0.35; // fused necks read as surface, not as edge
    // Distance maps directly to source UV, rim only visualizes the surface
    float edgeWidthMul = clamp(uLayer.z, 0.25, 2.0);
    float lensWidthPx = 5.25 * guiScaleE * edgeWidthMul;
    float lensDepth = max(-bestD, 0.0);
    float lensT = clamp(lensDepth / max(lensWidthPx, 1.0), 0.0, 1.0);
    // Calm center without a microscopic spike at the rim
    float lens = pow(1.0 - lensT, 2.15);
    // Single linear gain, etalon 9 reads 1.0
    float refrK = clamp(uParams.y / 9.0, 0.0, 4.0);
    // Derivative of the same function, 0.36 x 2.15 = 0.774
    float lensGrad = 0.774 * refrK * pow(max(1.0 - lensT, 0.0), 1.15);
    float tilt = atan(lensGrad);
    float cosTheta = cos(tilt);
    float fresnelS = 0.04 + 0.96 * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
    float slopeN = clamp(lensGrad, 0.0, 1.0);
    float matteK = clamp(popupLevel + cardLevel, 0.0, 1.0);
    float maxDispPx = lensWidthPx * 0.36 * refrK;
    float refr = lens * (1.0 - slotLevel * 0.55);
    refr *= 1.0 - matteK * 0.9;
    // Внутри Well рефракция почти нулевая — это плоское углубление, не линза; hover-ячейка сохраняет чуть живой отклик.
    refr *= 1.0 - wellMask * (0.9 - wellHover * 0.4);
    // Solid fused core bends nothing: one flat glass, no directional seams. Top-bucket melt flattens its own interior, legacy groups keep their waist.
    if (topIdx >= 0 && bestD < -2.0) {
        if (topMeltRim) refr *= 1.0 - fuseW;
        else {
            float cMin = abs(topGrp - 1.0) < 0.5 ? dmin1 : (abs(topGrp) < 0.5 ? dmin0 : 1e9);
            float cFuse = abs(topGrp - 1.0) < 0.5 ? dfuse1 : (abs(topGrp) < 0.5 ? dfuse0 : 1e9);
            if (cMin < 1e8 && cFuse < 1e8) refr *= 1.0 - clamp((cMin - cFuse) / 2.0, 0.0, 1.0);
            refr *= 1.0 - fuseW * 0.7; // halo joint calms even outside the melted path
        }
    }
    // Geometric density (iOS model): thicker glass BENDS more — refraction scales with density instead of the material turning opaque.
    float concaveK = clamp(slotLevel + wellMask, 0.0, 1.0);
    // SDF surface normal drives the lens, radial centre vector only rescues degenerate pixels
    // bestDir already carries the fused gradient, no second joint blend
    vec2 lensN = length(bestDir) > 1e-3 ? normalize(bestDir) : edgeN;
    vec2 pressV = vec2(0.0);
    float pressDent = 0.0;
    float pressK = clamp(uLayer.w, 0.0, 1.0);
    if (pressK > 0.003) {
        float dentR = 46.0 * guiScaleE;
        pressV = pxT - uScreen.zw;
        pressDent = exp(-dot(pressV, pressV) / (dentR * dentR)) * pressK;
    }
    float elevRefK = topIdx >= 0 ? (1.0 + clamp(topElev, 0.0, 6.0) * 0.02) : 1.0;
    float capR = clamp(1.0 - min(covH.x, covH.y) / max(max(covH.x, covH.y), 1.0), 0.0, 1.0);
    vec2 capLoc = (pxT - covC) / max(covH, vec2(1.0));
    float capAlong = (covH.x >= covH.y) ? abs(capLoc.x) : abs(capLoc.y);
    float capRole = clamp(controlLevel + activeLevel, 0.0, 1.0);
    float capMask = smoothstep(capR - 0.12, capR + 0.08, capAlong) * step(0.3, capR) * capRole;
    float capBoost = 1.0;
    vec2 edgeOffPx = lensN * (refr * (1.0 - pressDent * 0.45) * maxDispPx * (1.0 + 0.42 * effDensity) * elevRefK * capBoost);
    float distort = clamp(controlLevel + activeLevel, 0.0, 1.0);
    edgeOffPx = mix(-edgeOffPx * mix(0.35, 1.0, distort), -edgeOffPx * 0.9, concaveK);
    vec2 off = edgeOffPx / uScreen.xy;
    off += wellBend;
    if (mode == 11) {
        vec2 v = clamp(off * uScreen.xy * 0.25, -1.0, 1.0);
        fragColor = vec4(v * 0.5 + 0.5, 0.0, 1.0);
        return;
    }
    if (mode == 19 && controlLevel > 0.5) {
        vec2 testOff = -lensN * maxDispPx * lens / uScreen.xy;
        vec3 testSrc = texture(InSampler, uvT + testOff).rgb;
        fragColor = vec4(testSrc, 1.0);
        return;
    }

    vec2 lightDir = normalize(uLightDir.xy + vec2(0.0001,0.0001));
    float lightDot = dot(edgeN, lightDir);
    float lightBias = clamp(0.5 + 0.5 * lightDot, 0.0, 1.0);
    float topBias = clamp(0.65 + lightBias * 0.55, 0.0, 1.0);

    // Frosted panel renders first so tiles inherit it
    vec3 panelBase = vec3(0.0);
    float pmask = 0.0;
    vec2 panelC = uPanel.xy + vec2(0.0, offNew); // bottom-origin: +y = up
    if (uPanel.z > 0.5 && overlayPlane < 0.5) {
        float pd = sdGlassBox(px - panelC, uPanel.zw, uRing.w);
        pmask = 1.0 - smoothstep(-1.0, 1.0, pd);
        pmask *= 1.0 - coreMask;
        pmask *= 1.0 - slotCoreMask;
        if (pmask > 0.001) {
            vec2 puv = uv + off; // panel samples the same refracted point as tiles
            vec3 frosted = blur25(puv, texel, 4.2);
            // Panel shares button material: world blur plus stable, no gray paint
            float pbl = dot(frosted, vec3(0.299, 0.587, 0.114));
            vec3 stable = clamp(mix(frosted, vec3(pbl), 0.22), 0.05, 0.95);
            vec3 base = mix(frosted, stable, 0.38);

            // Adaptive base: dim on bright worlds, lift on dark ones
            float bright = smoothstep(0.60, 0.92, pbl);
            base *= 1.0 - bright * 0.16;
            base = mix(base, vec3(pbl), bright * 0.18);
            float dark = 1.0 - smoothstep(0.04, 0.22, pbl);
            base += dark * 0.05;
            vec3 sN = texture(InSampler, puv + vec2(7.0, 0.0) / uScreen.xy).rgb;
            vec3 sS = texture(InSampler, puv - vec2(7.0, 0.0) / uScreen.xy).rgb;
            vec3 sE = texture(InSampler, puv + vec2(0.0, 7.0) / uScreen.xy).rgb;
            vec3 sW = texture(InSampler, puv - vec2(0.0, 7.0) / uScreen.xy).rgb;
            float busy = clamp(max(max(length(sN - frosted), length(sS - frosted)),
                                   max(length(sE - frosted), length(sW - frosted))), 0.0, 1.0);
            base = mix(base, stable, busy * 0.35);

            // Тот же cool tint, что у тайлов pause menu:
            base = mix(base, base * vec3(0.88, 0.96, 1.08) + vec3(0.02, 0.03, 0.05), 0.06);
            base = mix(vec3(dot(base, vec3(0.299, 0.587, 0.114))), base, 1.15);
            // Near the border the bent world shows through more (glass edge). Quieter than before: a bright halo here read as white glow.
            float edgeBand = smoothstep(-9.0, -2.0, pd) * (1.0 - smoothstep(-2.0, 0.5, pd));
            base = mix(base, texture(InSampler, puv).rgb, edgeBand * 0.30);
            // Ambient adaptation: world tints the base, bright dims for readability
            float bl = dot(frosted, vec3(0.299, 0.587, 0.114));
            base *= 1.0 - smoothstep(0.60, 0.85, bl) * 0.12;
            // Dark outer outline: относительный + АБСОЛЮТНЫЙ минимум — кромка не исчезает в тёмном Nether и не белеет на снегу (толщина, а не stroke).
            vec3 lineCol = min(base * 0.72, vec3(0.40));
            base = mix(base, lineCol, (1.0 - smoothstep(0.0, 1.4, abs(pd))) * 0.45);
            // FRAME: lightest band around the perimeter + subtle separating line.
            float distIn = -pd;
            float frameW = uRing.w * 1.75;
            float frameM = 1.0 - smoothstep(frameW - 2.0, frameW + 2.0, distIn);
            base = mix(base, base * 1.05 + vec3(0.045, 0.05, 0.055), frameM * 0.5);
            base *= 1.0 - (1.0 - smoothstep(0.0, 1.5, abs(distIn - frameW))) * 0.10;
            // Inner shadow (thickness cue).
            base *= 1.0 - (1.0 - smoothstep(-4.0, -1.0, pd)) * (0.10 + 0.06 * (1.0 - topBias));
            // Uneven glass: gentle light from above + broad smudge patches — a big panel must NOT read as one flat grey rectangle.
            base *= 1.0 + 0.05 * smoothstep(-uPanel.w, uPanel.w, (px - panelC).y);
            // hash OFF Appearance на панели: DARK затемняет тело внутри mask (мир за ним не трогается), custom tint у BASE минимальный.
            base *= mix(1.0, 0.42, clamp(uTone.x, 0.0, 1.0));
            vec3 pTint = clamp(uTone.yzw, vec3(0.05), vec3(1.0));
            float ptStr = clamp(uWellMeta.z, 0.0, 1.0);
            vec3 pHue = pTint / max(dot(pTint, vec3(0.333)), 0.06);
            float pDepth = clamp(distIn / max(min(uPanel.z, uPanel.w) * 0.5, 1.0), 0.0, 1.0); // Beer-Lambert-Bouguer optical depth
            vec3 pSigma = clamp(vec3(1.0) - pHue, vec3(0.0), vec3(1.0)) * 2.2;
            base *= mix(vec3(1.0), exp(-pSigma * (0.25 + 0.75 * pDepth)), ptStr * 0.12);
            // TAB TRANSITION: the captured OLD tab frame slides UP and away (clipped by the FINAL panel rect), revealing the new content.
            if (anim) {
                vec2 pMin = (uPanel.xy - uPanel.zw) / uScreen.xy;
                vec2 pSize = (uPanel.zw * 2.0) / uScreen.xy;
                vec2 rel = (uv - pMin) / pSize;                  // 0..1 in final panel
                vec2 oldUv = vec2(uv.x, uv.y + oldOff / uScreen.y);
                vec3 oldC = texture(PrevSampler, oldUv).rgb;
                float edgeFade = 1.0 - smoothstep(0.80, 1.0, rel.y + oldOff / H);
                float fade = 1.0 - smoothstep(0.75, 1.0, animP);
                base = mix(base, oldC, fade);
            }
            panelBase = base;
        }
    }
    float inPanel = smoothstep(0.0, 1.0, pmask);

    vec2 parShift = vec2(0.0);
    // DENSE bar and DOCK keep parallax whenever they exist, hotbar drifts the same with inventory open or closed
    if (uPar.z > 0.0 && (uPanel.z <= 0.5 || pmask > 0.3 || density > 0.5 || dockLevel > 0.5)) {
        vec2 toM = uPar.xy - px;
        float md = length(toM);
        float fall = 1.0 - smoothstep(0.0, 200.0, md);
        parShift = (toM / max(md, 1e-3)) * fall * uPar.z / uScreen.xy;
    }
    vec2 uvP = uvT + parShift;

    if (bestMask <= 0.001 && wellMask <= 0.001) {
        // Outside tiles: world, or the panel where it exists.
        vec3 w = texture(InSampler, uv).rgb;
        float edgeFeather = 1.0 - smoothstep(0.0, 9.0, dNear);
        if (edgeFeather > 0.001) w = mix(w, texture(BlurredSampler, uv).rgb, edgeFeather * 0.45);
        float hole = max(coreMask, slotCoreMask);
        if (hole > 0.001) {
            vec3 holeFrost = mix(texture(BlurredSampler, uv).rgb, texture(ColorFieldSampler, uv).rgb, 0.45);
            float holeK = frostR > 0.5 ? 0.9 : 0.25;
            w = mix(w, holeFrost, hole * holeK);
        }
        fragColor = vec4(mix(w, panelBase, pmask)
            * (1.0 - fxDark) + fxAdd, 1.0);
        return;
    }

    // Hover: sharp falloff - only the tile under the cursor reacts strongly.
    vec2 mouse = uScreen.zw;
    float hover = hoverOn ? (1.0 - smoothstep(20.0, 70.0, length(px - mouse))) : 0.0;

    // Sun colour comes from Java, neutral fallback when empty
    vec3 sunCol = clamp(uSun.rgb, vec3(0.0), vec3(1.5));
    if (dot(sunCol, sunCol) < 1e-4) sunCol = vec3(1.0, 0.98, 0.94);
    float sunMaster = clamp(uSun.a, 0.0, 2.0);
    float sunI = uLightDir.z * clamp(uLightDir.w + 0.35, 0.0, 1.0);

    // Lens runs above (before the panel) so panelBase already refracts

    // Cauchy-lite dispersion, blue spreads about 3x red
    float Vd = aberrationOn ? max(60.0 / (1.0 + uBleed.z * 2.2), 15.0) : 1e9; // chroma reads as Abbe number
    float Bc = (0.5 / Vd) / 1.909;
    float dispK = (1.0 - effDensity * 0.42) * (1.0 - smoothstep(0.58, 0.92, bgL) * 0.50) * (1.0 - popupLevel * 0.9);
    float dispR = -0.529 * Bc / 0.5 * dispK;
    float dispB = 1.631 * Bc / 0.5 * dispK;

    // Tiles inside the panel sample the panel base, not the world
    vec3 sharp;
    sharp.r = mix(texture(InSampler, uvP + off * (1.0 + dispR)).r, panelBase.r, inPanel);
    sharp.g = mix(texture(InSampler, uvP + off).g, panelBase.g, inPanel);
    sharp.b = mix(texture(InSampler, uvP + off * (1.0 + dispB)).b, panelBase.b, inPanel);
    vec3 sharpWorld;
    sharpWorld.r = texture(InSampler, uvP + off * (1.0 + dispR)).r;
    sharpWorld.g = texture(InSampler, uvP + off).g;
    sharpWorld.b = texture(InSampler, uvP + off * (1.0 + dispB)).b;
    sharpWorld = mix(sharpWorld, texture(BlurredSampler, uvP + off).rgb, 0.45);
    sharpWorld = treatHole(sharpWorld);
    // REFR SOURCE: the exact refracted sample the tile reads, before frost
    if (mode == 16) {
        fragColor = vec4(sharp, 1.0);
        return;
    }

    // Frosted body with a LENS profile and adaptive diffusion
    bool frostOn = frostR > 0.5 || cardLevel > 0.5 || popupLevel > 0.5;
    float frostEff = frostR;
    if (cardLevel > 0.5) frostEff = max(frostEff, 13.0);
    if (popupLevel > 0.5) frostEff = max(frostEff, 12.0); // popup stays shut even with user frost low
    vec3 body = sharp;
    float overlayK = inPanel * controlLevel;
    float busyTileG = 0.0;
    if (frostOn) {
        vec3 sN2 = texture(InSampler, uvP + vec2(7.0, 0.0) / uScreen.xy).rgb;
        vec3 sS2 = texture(InSampler, uvP - vec2(7.0, 0.0) / uScreen.xy).rgb;
        vec3 sE2 = texture(InSampler, uvP + vec2(0.0, 7.0) / uScreen.xy).rgb;
        vec3 sW2 = texture(InSampler, uvP - vec2(0.0, 7.0) / uScreen.xy).rgb;
        float busyTile = clamp(max(max(length(sN2 - sharp), length(sS2 - sharp)), max(length(sE2 - sharp), length(sW2 - sharp))), 0.0, 1.0);
        busyTileG = busyTile;
        float roleFrost = clamp(1.0 + compLevel * 0.35 + groupLevel * 0.20 + density * 0.30 - controlLevel * 0.10 + popupLevel * 0.5 + cardLevel * 0.8, 0.7, 1.8);
        float localFrost = max(frostEff * (1.0 - 0.25 * effDensity) * (1.0 - 0.30 * slotLevel) * (1.0 + busyTile * 0.8 + sizeK * 0.35 - (1.0 - sizeK) * 0.10) * (1.0 - overlayK * 0.45) * roleFrost, 1.0);
        localFrost *= 1.0 - lens * 0.48;
        float isFlat = smoothstep(0.58, 1.0, lensT);
        // Edge reads narrower blur plus sharp return, interior keeps wide frost
        vec3 blurHalf = blur25Half(uvP + off, texelHalf, min(localFrost * 0.65, 1.5 + popupLevel * 2.2));
        vec3 blurMix = blurHalf;
        float wideW = clamp(isFlat * (0.80 + 0.20 * sizeK), 0.0, 1.0);
        if (isFlat > 0.05) {
            vec3 blurFull = blur25(uvP + off, texel, min(localFrost * 0.35, 3.2 + popupLevel * 4.0));
            blurMix = mix(blurFull, blurHalf, wideW);
        }
        body = mix(blurMix, panelBase, inPanel);
        vec3 colorField = texture(ColorFieldSampler, uvP + off).rgb;
        float cfLuma = dot(colorField, vec3(0.2126, 0.7152, 0.0722));
        vec3 colorBlob = mix(vec3(cfLuma), colorField, 1.15 + 0.15 * sizeK);
        float cfW = (0.14 + 0.016 * frostR + 0.18 * sizeK) * isFlat;
        cfW *= 1.0 - controlLevel * 0.68;
        body = mix(body, colorBlob, clamp(cfW, 0.0, 0.18));
        vec3 deepField = texture(DeepFieldSampler, uvP + off).rgb;
        float deepLuma = dot(deepField, vec3(0.2126, 0.7152, 0.0722));
        vec3 deepBlob = mix(vec3(deepLuma), deepField, 1.25);
        float deepM = smoothstep(0.0, 0.3, sizeK) * isFlat * (1.0 - controlLevel);
        body = mix(body, deepBlob, min((0.20 + 0.030 * frostR) * deepM, 1.0));
        // Intrinsic veil: matte body of its own, dimmed world barely reads
        vec3 veilCol = mix(vec3(0.75), vec3(0.24), clamp(uTone.x, 0.0, 1.0));
        body = mix(body, veilCol, cardLevel * 0.35);
        body = mix(body, veilCol, popupLevel * 0.50 * (frostOn ? 1.0 : 0.0));
        // Dock sharpness only on dock tiles, nearby bar keeps its own frost
        body = mix(body, texture(InSampler, uv).rgb, clamp(dockLevel, 0.0, 1.0) * (topMat > 6.5 && topMat < 7.5 ? 1.0 : 0.0));
    }

    float edgeClarity = pow(clamp(lens, 0.0, 1.0), 1.10);
    float sharpW = clamp(mix(0.015, 0.72, edgeClarity) * max(bestMask, wellMask) + rimBand * (0.28 + 0.18 * capMask) + overlayK * 0.15 - busyTileG * 0.12 + pressDent * 0.15, 0.0, 1.0) * (1.0 - cardLevel * 0.9) * (1.0 - popupLevel * 0.7);
    // Popup suppresses sharp return from behind so rows never ghost through

    // Upper tile refracts lower glass instead of hiding it (base plane only: overlay already samples the captured composite, manual remix would double-count its rim)
    if (overlayPlane < 0.5 && topIdx >= 0 && coverCountAll > topCoverCount && bestMask > 0.001 && concaveK < 0.5) {
        vec2 pxLow = pxT + edgeOffPx;
        vec2 uvLow = clamp(uvP + off, vec2(0.001), vec2(0.999));
        float lowMin = 1e9;
        for (int j = 0; j < MAX_PANELS; j++) {
            if (j >= uCount) break;
            if (j == topIdx) continue;
            vec4 lr = uRects[j];
            float lm = uMats[j].x;
            float le = uMats[j].y;
            float lg = uMats[j].z;
            // Stacked heights cover opaque, no glass through glass
            if (abs(le - topElev) > 0.25) continue;
            bool lslot = lm > 0.5 && lm < 1.5;
            if (lslot) continue;
            bool ldense = lm > 5.5 && lm < 6.5;
            bool ldock = lm > 6.5 && lm < 7.5;
            bool lfus = !ldense && !lslot && !ldock;
            vec2 lh = abs(lr.zw);
            if (lh.x <= 0.0 || lh.y <= 0.0) continue;
            bool sameLay = abs(lg - topGrp) < 0.5;
            bool sameH = abs(le - topElev) <= 0.25;
            bool bookP = sameLay && abs(topGrp - 1.0) < 0.5;
            if (topFusable && lfus && sameLay && (sameH || bookP)) continue;
            vec2 lrad = pxLow - lr.xy;
            float lmh = min(lh.x, lh.y);
            float lsw = uMats[j].w;
            float lsid = floor(lsw + 0.0001);
            bool ltri = lsid > 0.5 && lsid < 1.5;
            bool lcir = lsid > 1.5;
            float lcf = lsw - lsid;
            float lcov = lcf < 0.25 ? -1.0 : clamp((lcf - 0.5) / 0.499, 0.0, 1.0);
            float ld;
            if (lcir) {
                ld = length(lrad) - lmh;
            } else if (ltri) {
                float ltr = min(lh.x / 0.8660254, lh.y / 0.75);
                ld = sdTri(lrad, ltr);
            } else {
                bool lhero = (lm > 2.5 && lm < 3.5) || (lm > 4.5 && lm < 5.5);
                float lrf = clamp(uParams.x, 0.0, 1.0);
                float lrCap = min(16.0 * max(uRing.w / 4.0, 0.5), lmh * 0.35);
                float lcr;
                if (lcov >= 0.0) lcr = lcov >= 0.98 ? lmh : lcov * lmh;
                else lcr = lhero ? lmh * smoothstep(0.0, 0.18, uParams.x) : min(lrf * lmh, lrCap); // matches the main hero capsule
                ld = sdGlassBox(lrad, lh, lcr);
            }
            lowMin = min(lowMin, ld);
        }
        float lowMask = (lowMin < 1e8) ? (1.0 - smoothstep(-2.5, 2.5, lowMin)) : 0.0;
        lowMask *= 1.0 - popupLevel * 0.75;
        if (lowMask > 0.003) {
            vec3 lowSharpW = mix(texture(InSampler, uvLow).rgb, panelBase, inPanel);
            vec3 lowBlurW = mix(texture(BlurredSampler, uvLow).rgb, panelBase, inPanel);
            lowSharpW = mix(lowSharpW, lowSharpW * vec3(0.88, 0.96, 1.08) + vec3(0.02, 0.03, 0.05), 0.12 * lowMask);
            lowBlurW = mix(lowBlurW, lowBlurW * vec3(0.88, 0.96, 1.08) + vec3(0.02, 0.03, 0.05), 0.12 * lowMask);
            float lowRim = smoothstep(-2.0, -0.75, lowMin) * (1.0 - smoothstep(-0.75, 2.2, lowMin));
            lowSharpW += lowRim * 0.04 * vec3(1.0, 1.02, 1.06) * lowMask;
            lowBlurW += lowRim * 0.02 * vec3(1.0, 1.02, 1.06) * lowMask;
            sharp = mix(sharp, lowSharpW, lowMask * 0.25);
            body = mix(body, mix(lowBlurW, lowSharpW, sharpW), lowMask * 0.6);
        }
    }
    vec3 glass = mix(body, sharp, sharpW);
    glass += pressDent * bestMask * vec3(0.020, 0.022, 0.026); // pressed spot thins and lifts

    // Иерархия отклика: интерактивные элементы живее, большие — спокойнее. CONTENT = резкий, BASE = тихий, CONTROLS = живее, ACTIVE = заметен.
    float hovAmp = hover * (0.5 + 0.6 * clamp(controlLevel + activeLevel, 0.0, 1.0));

    // GROUP (L2): едва заметный локальный подъём яркости — группировка читается БЕЗ отдельной карточки.
    glass *= 1.0 + 0.03 * groupLevel * bestMask;

    // ACTIVE/selected: gentle cool lift of the SAME material.
    glass = mix(glass, glass * vec3(0.97, 1.0, 1.05) + vec3(0.055, 0.06, 0.07), activeLevel * bestMask);
    // COMPANION (recipe book): прозрачнее — меньше cool-tint, меньше массы. S1 calm: halved the navy smoke so the backdrop reads through tiles
    glass = mix(glass, glass * vec3(0.88, 0.96, 1.08) + vec3(0.02, 0.03, 0.05),
        0.12 * (1.0 - 0.35 * compLevel) * bestMask);
    // Companion lift, pale glass reads apart from the base panel on dark
    glass += compLevel * bestMask * vec3(0.035, 0.038, 0.045);
    vec3 envSingle = texture(BlurredSampler, uvP + off).rgb;
    float pickupScale = clamp(1.0 - slotLevel * 0.55 - groupLevel * 0.20, 0.25, 1.0);
    glass += envSingle * uBleed.x * (0.35 + 0.65 * topBias) * bestMask * pickupScale;
    float luma = dot(glass, vec3(0.299, 0.587, 0.114));
    // ...VIBRANCY for dense panels (iOS model): saturate the background, keep luminance - never paint over it.
    glass = mix(vec3(luma), glass, 1.0 + 0.20 * density + 0.12 * controlLevel);
    glass += vec3(0.020, 0.021, 0.024) * controlLevel * bestMask;
    // backdropLuma adaptive — fixes snow/bright vs night (ChatGPT)
    float backdropLuma = bgL; // sampled centre tap
    float brightMask = smoothstep(0.65, 0.92, backdropLuma);
    float darkMask = 1.0 - smoothstep(0.18, 0.42, backdropLuma);
    // bright bg: tint slightly up, innerShadow up, rim darker, vibrancy down, lumaBoost down we modulate via multipliers below: luma already, tweak glass
    glass *= mix(1.0, 0.96, brightMask * 0.35); // lumaBoost down on snow
    // rim darker on bright bg will be handled via rimTint below with backdrop ChatGPT 1.12 not 1.35

    // Appearance: DARK dims only the glass body, edge work stays
    float darkness = clamp(uTone.x, 0.0, 1.0);
    glass *= mix(1.0, 0.55, clamp(darkness * 0.8, 0.0, 1.0));

    // ── CUSTOM TINT (role-scaled): BASE почти нейтральный, CONTROL заметнее, ACTIVE максимум; мир через стекло сохраняет цвет.
    float tStr = clamp(uWellMeta.z, 0.0, 1.0);
    vec3 userTint = clamp(uTone.yzw, vec3(0.05), vec3(1.0));
    vec3 tintHue = userTint / max(dot(userTint, vec3(0.333)), 0.06);
    if (tStr > 0.001) {
        float roleW = (0.15 + 0.35 * controlLevel + 0.55 * activeLevel) * (1.0 - cardLevel);
        float optDepth = clamp(max(-bestD, 0.0) / max(lensWidthPx, 1.0), 0.0, 1.0);
        vec3 sigma = clamp(vec3(1.0) - tintHue, vec3(0.0), vec3(1.0)) * 2.2;
        glass *= mix(vec3(1.0), exp(-sigma * (0.25 + 0.75 * optDepth)), tStr * roleW);
        glass += userTint * tStr * roleW * 0.05;
    }

    // ── LUMINANCE DOCK (reuses blurred sampler, no new passes): samples background luma under the dock region and scales dock opacity.
    float dockMaskScale = 1.0;
    float outerMask = 0.0;
    if (dockLevel > 0.001) {
        float bgL = dot(texture(InSampler, uv).rgb, vec3(0.299, 0.587, 0.114));
        // Тёмный фон → почти невидим (0.08), снег/яркий → до 0.32. Дёшево: 1 sample.
        float dockAlpha = mix(0.06, 0.32, smoothstep(0.45, 0.85, bgL));
        dockMaskScale = 1.0; // dock interior stays sharp, rim carries the shape
        // Outer refractive footprint: innerSdf dilated by outerPad (§3-4, §41)
        float outerPad = max(uDockParams.x, 0.0);
        float outer = 1.0 - smoothstep(-1.0, 1.0, bestD - outerPad);
        outerMask = 0.0; // dock footprint off with the blur
        // Outer has much lower density but visible refraction/edge
        if (outerMask > 0.001) {
            vec2 outerOff = edgeN * outerMask * uDockParams.z * 0.6 / uScreen.xy;
            vec3 outerSample = texture(InSampler, uv + outerOff).rgb;
            // Very light fill, almost just refraction
            glass = mix(glass, outerSample, outerMask * 0.22);
        }
    }

    // Внутри well обычный контур гасится (кроме hover-ячейки) — до вычисления rim-вкладов.
    rimBand *= 1.0 - wellMask * (1.0 - wellHover);
    if (uPanel.z > 0.5 && inPanel > 0.35) rimBand *= 0.72;

    float isCtrl = clamp(controlLevel, 0.0, 1.0);
    // Static light, iPhone rim does not wander with time
    vec2 walkLight = lightDir;
    // Screen-space light probe: gradient from blur, no readback
    {
        vec2 ssTex = 8.0 / uScreen.xy;
        float lC = dot(texture(BlurredSampler, uv).rgb, vec3(0.299, 0.587, 0.114));
        float lR = dot(texture(BlurredSampler, uv + vec2(ssTex.x, 0.0)).rgb, vec3(0.299, 0.587, 0.114));
        float lL = dot(texture(BlurredSampler, uv - vec2(ssTex.x, 0.0)).rgb, vec3(0.299, 0.587, 0.114));
        float lT = dot(texture(BlurredSampler, uv + vec2(0.0, ssTex.y)).rgb, vec3(0.299, 0.587, 0.114));
        float lB = dot(texture(BlurredSampler, uv - vec2(0.0, ssTex.y)).rgb, vec3(0.299, 0.587, 0.114));
        vec2 ssGrad = vec2(lR - lL, lT - lB);
        float ssLen = length(ssGrad);
        if (ssLen > 0.004) {
            vec2 ssDir = ssGrad / ssLen;
            float ssK = clamp(ssLen * 6.0, 0.0, 0.65);
            walkLight = normalize(mix(walkLight, ssDir, ssK));
        }
    }
    vec2 rimN = mix(lensN, -lensN, concaveK);
    walkLight = normalize(mix(normalize(vec2(-0.45, 0.89)), walkLight, 0.40));
    vec3 N3 = normalize(vec3(rimN * lensGrad * 0.9, 1.0));
    vec3 L3 = normalize(vec3(walkLight * 0.6, 0.8));
    // One rim language for buttons and panels, brightest faces the light
    float facing = max(dot(rimN, walkLight), 0.0);
    float angular = smoothstep(-0.15, 0.85, facing);
    float frostK = clamp(frostR / 20.0, 0.0, 1.0); // roughness proxy, etalon frost 10 reads 0.5
    angular = pow(angular, mix(2.4, 0.7, frostK));
    float specN3 = pow(max(dot(N3, normalize(L3 + vec3(0.0, 0.0, 1.0))), 0.0), mix(64.0, 20.0, frostK));
    float intensity = smoothstep(0.10, 0.90, uLightDir.z * max(uLightDir.w, 0.32));
    float specular = angular * intensity * (rimOn ? 1.0 : 0.0) * mix(0.92, 1.0, isCtrl);
    specular = max(specular, (rimOn ? 0.05 : 0.0) * max(uLightDir.w, 0.25));
    specular *= mix(0.35, 1.0, clamp(fresnelS, 0.0, 1.0)); // edge-on reflects, flat transmits
    specular *= mix(1.25, 0.8, frostK); // wide rough lobe reads dimmer
    specular *= mix(1.0, 1.15, capMask); // caps catch harder light
    specular = max(specular, specN3 * intensity * (rimOn ? 1.0 : 0.0) * clamp(uRefl.w, 0.0, 2.0)); // N3 lobe joins the facing lobe
    // material coeff WOW: HERO catches MORE sun (convex cabochon), BASE 1.00 GROUP 0.80 SLOT 0.28 ACTIVE 1.35 — buttons brighter than panel.
    float matSpec = mix(1.00, 1.30, isCtrl);
    matSpec = mix(matSpec, 0.28, clamp(slotLevel,0.0,1.0));
    matSpec = mix(matSpec, 0.80, clamp(groupLevel,0.0,1.0));
    matSpec = mix(matSpec, 0.90, clamp(activeLevel,0.0,1.0));
    float curveBoost = 1.0 + (1.0 - sizeK) * 0.25 * clamp(controlLevel + activeLevel + dockLevel, 0.0, 1.0); // small radius bends harder
    float topOnly = min(rimBand * specular * matSpec * 0.75 * curveBoost * (1.0 + cardLevel * 0.7) * (1.0 - popupLevel * 0.65), 0.9);
    float oppFacing = max(dot(rimN, -walkLight), 0.0);
    float oppMask = smoothstep(0.18, 0.85, oppFacing) * pow(oppFacing, 3.0);
    float bottomOnly = rimBand * oppMask * uLightDir.z * max(uLightDir.w,0.32) * 0.18 * isCtrl;
    float causticBand = smoothstep(-3.0, -1.0, rimSDF) * (1.0 - smoothstep(-1.0, 0.2, rimSDF));
    glass += causticBand * bestMask * sunCol * (0.06 * sunMaster * sunI * topBias) * (1.0 - concaveK); // focused-ray lift, sun slider wakes it
    // HIGHLIGHT rim light terms alone on black
    if (mode == 13) {
        fragColor = vec4(vec3(clamp(topOnly + bottomOnly, 0.0, 1.0)), 1.0);
        return;
    }
    float rimK = clamp(1.0 - effDensity * 0.7 - slotLevel * 0.55 - groupLevel * 0.6
        + controlLevel * 0.15 + activeLevel * 0.30 - compLevel * 0.15, 0.2, 1.35);
    vec3 rimWhite = vec3(1.0, 1.02, 1.08);
    // S1 calm: edge takes the light colour, softer white base
    float sunK = 0.8 * (1.0 - concaveK * 0.65);
    vec3 rimTint = mix(rimWhite, sunCol, sunK) * (0.30 + 1.15 * specular) * uParams.z;
    // backdrop adaptive for rim: bright bg -> rim darker, dark bg -> brighter
    rimTint *= mix(1.0, 0.82, brightMask * 0.45);
    rimTint *= mix(1.0, 1.18, darkMask * 0.35);
    rimTint = mix(rimTint, rimTint * tintHue,
        tStr * (0.3 + 0.7 * clamp(controlLevel + activeLevel, 0.0, 1.0)) * (1.0 - cardLevel));
    glass += topOnly * rimTint;
    glass += bottomOnly * rimTint * 0.55;
    glass += iconRim * rimTint * 0.9;
    // Face sheen veil: wide soft light across the flat face, backdrop colour leaning to the light side
    float reflK = clamp(uRefl.x, 0.0, 1.0);
    vec3 Vv = vec3(0.0, 0.0, 1.0);
    vec3 Hv = normalize(L3 + Vv);
    // Tilt the half-vector toward the light: peak sits on the lit slope, never a flashlight blob dead centre
    vec3 Hshift = normalize(Hv + vec3(walkLight * 0.45, 0.0));
    float broadSpec = pow(max(dot(N3, Hshift), 0.0), clamp(uRefl.y, 2.0, 6.0));
    vec3 envRef = texture(BlurredSampler, uvP - walkLight * 0.010).rgb;
    vec3 reflCol = mix(vec3(1.0), envRef, clamp(uRefl.z, 0.0, 1.0));
    float faceZone = 1.0 - rimBand * 0.65;
    glass += broadSpec * reflCol * (0.10 * reflK) * bestMask * faceZone * (1.0 - matteK) * (1.0 - concaveK * 0.5) * (1.0 - fuseW * 0.5);
    // Backdrop hue gathered at the rim, grey rooms add nothing
    vec3 edgeCol = texture(BlurredSampler, uvP + off).rgb;
    vec3 edgeHue = edgeCol - vec3(dot(edgeCol, vec3(0.3333)));
    glass += rimBand * edgeHue * uBleed.y * bestMask * (1.0 - popupLevel * 0.7);
    // Inner glow sits one band deeper than the white rim: white line outside, backdrop colour inside
    float innerGlowBand = smoothstep(-6.0, -3.0, rimSDF) * (1.0 - smoothstep(-3.0, -0.7, rimSDF));
    vec3 coloredGlow = mix(edgeHue, edgeCol, 0.35);
    glass += innerGlowBand * coloredGlow * uBleed.y * 0.40 * bestMask * (1.0 - popupLevel * 0.7) * (0.65 + 0.35 * topBias) * (1.0 - fuseW * 0.5);
    // BLEED backdrop color pickup alone
    if (mode == 12) {
        fragColor = vec4(clamp(envSingle * uBleed.x + rimBand * edgeHue * uBleed.y, 0.0, 1.0), 1.0);
        return;
    }
    // base uniform edge — thin, visible even with Refraction=0/Frost=OFF, decoupled
    float rimAddBase = rimOn ? 0.030 : 0.012;
    if (uPanel.z > 0.5 && controlLevel > 0.5) rimAddBase *= 0.62;
    // Higher glass catches more rim light, elevation reads at any material
    float elevK = topIdx >= 0 ? (0.8 + 0.10 * clamp(topElev, 0.0, 6.0)) : 1.0;
    float rimAdd = rimAddBase * rimK * elevK * (1.0 + cardLevel * 0.5) * (1.0 - popupLevel * 0.4) * (0.20 + 0.80 * facing * mix(1.0, 0.55, isCtrl) * max(uLightDir.w,0.32));
    rimAdd *= 1.0 - concaveK * 0.85;
    glass += rimBand * rimAdd * (0.45 + 0.55 * slopeN) * vec3(1.0, 1.02, 1.06) * mix(1.0, 1.2, isCtrl);
    glass *= 1.0 - rimBand * (1.0 - topBias) * (rimOn ? 0.025 : 0.0);
    // Upper glass casts onto lower glass, layers read apart
    float topShadow = exp(-max(topOut - 1.0, 0.0) * 0.45) * (1.0 - smoothstep(4.0, 6.0, topOut));
    glass *= 1.0 - topShadow * 0.14 * bestMask * step(0.5, topOut);
    // Upper layer lifts off the lower glass, height reads as separation
    float elevLift = topIdx >= 0 ? clamp(topElev, 0.0, 6.0) * 0.012 : 0.0;
    glass *= 1.0 + elevLift * bestMask;
    float darkBand = (1.0 - smoothstep(0.0, 2.4, abs(rimSDF + 1.0))) * (0.45 + 0.55 * slopeN);
    darkBand *= 1.0 - overGlass * 0.85;
    darkBand *= 0.20 + 0.80 * (1.0 - facing);
    glass *= 1.0 - darkBand * (0.010 + 0.012 * brightMask) * bestMask * (1.0 - concaveK * 0.85) * (1.0 - fuseW * 0.4);
    // Side thickness cues geometry, not light: left/right walls read as glass mass from any sun angle
    float sideThick = (1.0 - smoothstep(0.0, 2.4, abs(rimSDF + 1.0))) * clamp(abs(rimN.x), 0.0, 1.0);
    glass *= 1.0 - sideThick * 0.012 * bestMask * (1.0 - concaveK * 0.85);
    // exposure clamp — never pure white/black (fixes snow dissolve)
    glass = clamp(glass, vec3(0.04), vec3(0.96));

    // Thickness cues (iOS model) for dense HUD panels only — slot cells must NOT stack inner shadows at shared edges (that caused the dark grid).
    float innerShadow = (1.0 - smoothstep(-3.5, -0.5, bestD)) * density
        * (1.0 - slotLevel) * bestMask;
    glass *= 1.0 - innerShadow * (0.10 + 0.14 * (1.0 - topBias));
    float topLine = smoothstep(-2.2, -1.2, bestD) * (1.0 - smoothstep(-1.2, -0.2, bestD))
        * smoothstep(0.55, 0.9, edgeN.y) * density * (1.0 - slotLevel) * bestMask;
    glass += topLine * vec3(0.025, 0.028, 0.032);
    // Outer contour of the dense HUD bar: thin light outline like the panels' frame — the window border outranks every internal divider.
    float barEdge = smoothstep(-2.4, -1.1, bestD) * (1.0 - smoothstep(-0.6, 1.2, bestD))
        * density * (1.0 - slotLevel) * bestMask;
    glass += barEdge * vec3(0.045, 0.048, 0.055);

    // Dense cell grid mirrors vanilla hotbar geometry from Java
    if (density > 0.4 && uGrid.w > 0.0 && bestMask > 0.001) {
        float rel = px.x - uGrid.x;
        float inX = step(1.0, rel) * step(rel, uGrid.z);
        float fx = mod(rel - 1.0, uGrid.y);
        fx = min(fx, uGrid.y - fx);              // 0 at cell border, pitch/2 at centre
        float fy = abs(px.y - uMeta.z);          // 0 at panel centre
        float lattice = max(1.0 - smoothstep(0.4, 1.5, fx), smoothstep(8.8, 10.0, fy)) * inX;
        float well = smoothstep(0.9, 2.4, fx) * (1.0 - smoothstep(7.6, 9.4, fy)) * inX;
        float wellTop = well * smoothstep(2.0, 7.0, fy);  // darker toward visual top
        glass += lattice * vec3(0.032, 0.036, 0.042) * bestMask;
        glass = mix(glass, glass * 0.80, clamp(well * 0.40 * bestMask, 0.0, 1.0));
        glass = mix(glass, glass * 0.90, clamp(wellTop * 0.25 * bestMask, 0.0, 1.0));
    }

    // SLOT cells (FreeformWell): recess прижат к кромке — центр остаётся прозрачным продолжением панели, а не серой плиткой.
    float slotRecessK = slotLevel * bestMask * (0.15 + 0.85 * bestEdge);
    glass = mix(glass, glass * 0.93, clamp(slotRecessK * 0.5, 0.0, 1.0));
    float slotConc = clamp(slotLevel - wellMask, 0.0, 1.0) * bestMask;
    glass *= 1.0 - slotConc * smoothstep(0.2, 0.9, edgeN.y) * 0.04;
    glass += slotConc * smoothstep(0.2, 0.9, -edgeN.y) * vec3(0.030, 0.034, 0.040);

    // Well recess lives on the bevel edge, cell centre stays clear
    if (wellMask > 0.001) {
        // Pit walls face inward toward the flat floor, panel stays flat between cells
        float gsc = max(uRing.w / 4.0, 0.5);
        float rf = clamp(uParams.x, 0.0, 1.0) * 0.9;
        float wW = 2.5 * gsc;
        float innerRW = min(max(rf * min(cellHalf.x, cellHalf.y) + 1.0, 1.2 * gsc), min(cellHalf.x, cellHalf.y));
        float iD = sdGlassBox(px - cellC, cellHalf, innerRW);
        float fDepth = -iD;
        float wp = sin(3.14159 * clamp(fDepth / wW, 0.0, 1.0));
        float seam = 1.0 - smoothstep(0.0, 2.5, abs(fDepth - wW));
        // Light from top (bottom-origin): the TOP inner lip is shadowed, the BOTTOM inner wall catches light — concave read (opposite of convex).
        float topLip = 1.0 - smoothstep(0.0, 1.5, (cellC.y + cellHalf.y) - px.y);
        float botLip = 1.0 - smoothstep(0.0, 2.5, px.y - (cellC.y - cellHalf.y));
        glass *= 1.0 - wp * (0.04 + 0.05 * topLip);
        glass += wp * botLip * vec3(0.010, 0.012, 0.014);
        glass *= 1.0 - seam * 0.05;
        float lipLine = (1.0 - smoothstep(0.0, 2.0, fDepth)) * botLip;
        glass += lipLine * vec3(0.045, 0.050, 0.058);
        float hovBand = wellHover * wp;
        glass += hovBand * vec3(0.060, 0.066, 0.078);
    }
    glass = mix(glass, sharpWorld, concaveK * 0.30);

    // iPhone clean glass

    // CONTACT SHADOW (§19): мир чуть темнеет у кромки поверхности — край ощущается как толщина стекла, а не нарисованная линия.
    vec3 worldC = texture(InSampler, uv).rgb;
    float outD = max(bestD, 0.0);
    float contact = exp(-max(outD - 1.5, 0.0) * 0.20) * smoothstep(0.5, 3.0, outD);
    worldC *= 1.0 - contact * 0.08 * (1.0 - topBias * 0.5) * smoothstep(0.0, 5.0, dNearSlot); // ChatGPT 0.08
    if (uPanel.z > 0.5) {
        float pdp = sdGlassBox(px - uPanel.xy, uPanel.zw, uRing.w);
        float cp = exp(-max(pdp - 1.5, 0.0) * 0.16) * smoothstep(0.5, 3.0, pdp);
        worldC *= 1.0 - cp * 0.09;
    }

    // Нижняя подстилка вырезается слотами (§ base - wellInner) — внутри колодца нет блюра/стекла, только sharp мир, как у плиток кнопок/Recipe Book.
    float baseMaskCut = bestMask * (1.0 - coreMask);
    float effectiveTileMask = mix(baseMaskCut, baseMaskCut * dockMaskScale, dockLevel);
    effectiveTileMask = max(effectiveTileMask, outerMask);
    // Opaque composite: world/panel outside, tile glass / well wall inside, clear hole — world
    vec4 outColor = vec4(mix(worldC, glass, max(effectiveTileMask, wellMask)), 1.0);
    // TAIL cutouts: overlay content stays sharp over its own glass
    if (overlayPlane > 1.5) {
        float cutMask = 0.0;
        for (int k = 0; k < 16; k++) {
            vec4 cr = uCut[k];
            if (cr.z <= 0.0 || cr.w <= 0.0) continue;
            vec2 q = abs(px - cr.xy) - cr.zw;
            cutMask = max(cutMask, 1.0 - smoothstep(-2.0, 2.0, max(q.x, q.y)));
        }
        outColor.rgb = mix(outColor.rgb, worldC, cutMask);
    }
    // Hover glow lives outside the edge in screen space, never bent
    {
        float hovOut = exp(-max(rimSDF - 1.0, 0.0) * 0.30) * smoothstep(0.5, 3.0, rimSDF);
        hovOut *= hovAmp * (1.0 - concaveK) * (1.0 - max(effectiveTileMask, wellMask));
        outColor.rgb += hovOut * vec3(0.10, 0.11, 0.13) * (0.4 + 0.6 * topBias);
    }
    // Furnace FX поверх composite (flame spill / process channel).
    outColor.rgb = outColor.rgb * (1.0 - fxDark) + fxAdd;

    // Selected-slot ring: quiet state, brighter than seams
    if (uMeta.w >= 0.0 && uMeta.z >= 0.0) {
        vec2 ringC = vec2(uMeta.w, uMeta.z);
        float d = sdGlassBox(px - ringC, uRing.xy, uRing.w);
        float ring = 1.0 - smoothstep(uRing.z * 0.4, uRing.z, abs(d));
        float glow = exp(-max(abs(d) - uRing.z, 0.0) * 0.5) * 0.10;
        vec3 ringCol = vec3(0.94, 0.97, 1.05);
        outColor.rgb = mix(outColor.rgb, ringCol, ring * 0.5);
        outColor.rgb += ringCol * glow;
    }

    fragColor = outColor; // FULL composite glass over world
}
