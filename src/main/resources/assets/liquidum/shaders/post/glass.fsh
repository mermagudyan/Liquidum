#version 330

uniform sampler2D InSampler;
uniform sampler2D PrevSampler;   // captured previous tab frame (uiprev target)

#define MAX_PANELS 128
#define MAX_WELLS 12
layout(std140) uniform GlassConfig {
    vec4 uRects[MAX_PANELS];
    vec4 uMats[MAX_PANELS]; // x = material id: 0 BASE, 1 SLOT, 2 GROUP, 3 CONTROL, 4 COMPANION, 5 ACTIVE, 6 DENSE
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

    	// Local world luminance under this pixel (cheap centre tap). Used to tame
    	// glare / chromatic fringe on bright worlds (snow) and to lift dark worlds
    	// (Nether) — adaptive optical material, no fullscreen dim (P2).
    	float bgL = dot(texture(InSampler, uv).rgb, vec3(0.299, 0.587, 0.114));

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
    // Materials: 0 CLEAR (pause-menu button surface — the reference),
    // 1 SLOT (small clear: thin border, mild recess), 2 BRIGHT (selected),
    // 3 DENSE (HUD hotbar). Slots and dense panels never fuse.
    // TAB TRANSITION offsets (shared): the whole grid rides the sliding panel.
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
    float dfold = 1e9;      // fused SDF across all panels
    bool isoFold = false;   // dfold currently belongs to a fusion-exempt tile
    float reachAcc = 0.0;   // proximity-weighted lens reach
    float densAcc = 0.0;    // proximity-weighted "dense HUD" factor
    float slotAcc = 0.0;    // proximity-weighted "slot cell" factor
    float groupAcc = 0.0;   // proximity-weighted "functional group" factor
    float controlAcc = 0.0; // proximity-weighted "interactive glass" factor
    float compAcc = 0.0;    // proximity-weighted "companion surface" factor
    float activeAcc = 0.0;  // proximity-weighted "active/selected" factor
    float dockAcc = 0.0;    // proximity-weighted "luminance dock" factor
    vec2 dirAcc = vec2(0.0);
    float wsum = 0.0;
    float dNear = 1e9;      // nearest tile — fallback direction source
    vec2 nearDir = vec2(0.0, 1.0);

    // Материальные роли Liquidum: 0 BASE, 1 SLOT, 2 GROUP, 3 CONTROL,
    // 4 COMPANION, 5 ACTIVE, 6 DENSE(HUD), 7 DOCK. Один материал — разные параметры.
    for (int i = 0; i < MAX_PANELS; i++) {
        if (i >= uCount) break;
        vec4 rect = uRects[i];
        float matId = uMats[i].x;
        bool dense = matId > 5.5 && matId < 6.5;           // MAT_DENSE
        bool dock = matId > 6.5;                           // MAT_DOCK
        bool slot = matId > 0.5 && matId < 1.5;         // MAT_SLOT
        bool fusable = !dense && !slot && !dock;        // только они не сливаются
        vec2 halfSize = abs(rect.zw);
        if (halfSize.x <= 0.0 || halfSize.y <= 0.0) continue;

        vec2 radial = pxT - rect.xy;
        vec2 dir = radial / max(length(radial), 1e-4);

        float radF = clamp(uParams.x, 0.0, 1.0) * (dense ? 1.35 : 1.0);
        // Cap corner radius to a MODEST ABSOLUTE value (gui-scale aware) so a
        // tall, narrow panel (Recipe Book / companion) never collapses into a
        // full capsule "bubble". Dock radius is user-controlled (§6).
        float guiScale = max(uRing.w / 4.0, 0.5);
        bool isComp = matId > 3.5 && matId < 4.5;
        bool isDock = matId > 6.5;
        float rCap = isDock ? max(uDockParams.y, 0.0) : (isComp ? 15.0 : 14.0) * guiScale;
        float d = sdRoundedBox(radial, halfSize, min(radF * min(halfSize.x, halfSize.y), rCap));
        // Slot wells: interior must be CLEAR (no glass colour), only wall is glass (§22-23)
        if (slot) {
            float wWslot = 2.2 * guiScale;
            float oDslot = sdRoundedBox(radial, halfSize + wWslot, min(radF * min(halfSize.x + wWslot, halfSize.y + wWslot), rCap));
            float wallSlot = (1.0 - smoothstep(0.0, 1.5, -d)) * (1.0 - smoothstep(0.0, 1.5, oDslot));
            bestMask = max(bestMask, wallSlot);
        } else {
            bestMask = max(bestMask, 1.0 - smoothstep(-2.5, 2.5, d));
        }

        // Far panels neither mask nor fuse — skip early (ALU saving).
        if (d > fuseK * 3.0 + 24.0) continue;

        if (d < dNear) { dNear = d; nearDir = dir; }

        bool iso = !fusable;   // dense HUD panels and slots never fuse
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
        // Slot: interior должен быть CLEAR — учитываем только wall, не всю плитку (§22)
        if (slot) {
            float wWslot2 = 2.2 * guiScale;
            float oDslot2 = sdRoundedBox(radial, halfSize + wWslot2, min(radF * min(halfSize.x + wWslot2, halfSize.y + wWslot2), rCap));
            float wallSlot2 = (1.0 - smoothstep(0.0, 1.5, -d)) * (1.0 - smoothstep(0.0, 1.5, oDslot2));
            slotAcc += wallSlot2;
        }
        groupAcc += ((matId > 1.5 && matId < 2.5) ? 1.0 : 0.0) * w;
        controlAcc += ((matId > 2.5 && matId < 3.5) ? 1.0 : 0.0) * w;
        compAcc += ((matId > 3.5 && matId < 4.5) ? 1.0 : 0.0) * w;
        activeAcc += ((matId > 4.5 && matId < 5.5) ? 1.0 : 0.0) * w;
        dockAcc += (dock ? 1.0 : 0.0) * w;
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
    if (wsum > 0.0001) {
        bestD = dfold;
        // Fallback: at 4-tile intersections dirAcc cancels to ~zero and
        // normalize() blows up (dark dots). Use the nearest tile's direction.
        bestDir = length(dirAcc) > 1e-3 ? normalize(dirAcc)
                                        : ((dNear < 1e8) ? nearDir : vec2(0.0, 1.0));
        reach = max(reachAcc / wsum, 4.0);
        bestEdge = smoothstep(-reach * 0.38, -reach * 0.02, bestD) * bestMask;
        density = clamp(densAcc / wsum, 0.0, 1.0);
        slotLevel = clamp(slotAcc / wsum, 0.0, 1.0);
        groupLevel = clamp(groupAcc / wsum, 0.0, 1.0);
        controlLevel = clamp(controlAcc / wsum, 0.0, 1.0);
        compLevel = clamp(compAcc / wsum, 0.0, 1.0);
        activeLevel = clamp(activeAcc / wsum, 0.0, 1.0);
        dockLevel = clamp(dockAcc / wsum, 0.0, 1.0);
    }

    // MODE 1: mask visualization (cyan glow = tiles).
    if (mode == 1) {
        fragColor = vec4(vec3(0.0, bestMask * 0.8, bestMask * 0.8), 1.0);
        return;
    }

    // ─── GRID WELL (процедурная recessed-сетка) ───
    // Один дескриптор на сетку: ячейка определяется МАТЕМАТИЧЕСКИ из
    // координаты пикселя — стоимость не зависит от числа слотов.
    // uWells[w*3+0] = (cell[0][0] centre x,y, innerHalfW, innerHalfH)
    // uWells[w*3+1] = (pitchX, pitchY, cols, rows)
    // uWells[w*3+2] = (hoverCol, hoverRow, 0, 0)
    float wellMask = 0.0;      // wall ring only — interior is clear world
    float coreMask = 0.0;      // clear hole interior (no glass colour)
    float wellHover = 0.0;
    vec2 cellC = vec2(0.0);
    vec2 cellHalf = vec2(1.0);
    vec2 wellBend = vec2(0.0);   // inward refraction in the concave wall (P1)
    int wCount = int(uWellMeta.x + 0.5);
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
        float d = sdRoundedBox(px - cc, A.zw, radFw * min(A.z, A.w));
        // wall / core for this cell
        float gsc = max(uRing.w / 4.0, 0.5);
        float rf = clamp(uParams.x, 0.0, 1.0) * 0.9;
        float wW = 3.0 * gsc;
        float iD = sdRoundedBox(px - cc, A.zw, rf * min(A.z, A.w));
        float oD = sdRoundedBox(px - cc, A.zw + wW, rf * min(A.z + wW, A.w + wW));
        float core = smoothstep(0.0, 1.5, -iD) * (1.0 - smoothstep(0.0, 1.5, oD));
        float wallBand = (1.0 - smoothstep(0.0, 1.5, -iD)) * (1.0 - smoothstep(0.0, 1.5, oD));
        // wall is the glass wall ring, core is the clear hole interior
        if (wallBand > wellMask || core > coreMask) {
            if (wallBand > wellMask) {
                wellMask = wallBand;
                cellC = cc;
                cellHalf = A.zw;
                wellHover = (ci == C.x && ri == C.y) ? 1.0 : 0.0;
            }
            coreMask = max(coreMask, core);
            vec2 inward = (length(px - cc) > 1e-3) ? normalize(cc - px) : vec2(0.0, 1.0);
            float wf = clamp(iD / wW, 0.0, 1.0);
            wellBend += inward * wallBand * (1.0 - wf) * (2.0 + 3.0 * wellHover) / uScreen.xy;
        }
    }

    // ─── FURNACE FX (semantic adapter) ───
    // Flame spill: маленький радиус, интенсивность = getLitProgress().
    // ProcessChannel: recessed-желоб, заполнение = getBurnProgress().
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
        float cd = sdRoundedBox(px - chc, chh, chh.y * 0.8);
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

    vec2 lightDir = normalize(uLightDir.xy + vec2(0.0001,0.0001));
    float lightDot = dot(bestDir, lightDir);
    float lightBias = clamp(0.5 + 0.5 * lightDot, 0.0, 1.0);
    float topBias = clamp(0.65 + lightBias * 0.55, 0.0, 1.0);

    // ─── FROSTED PANEL (uPanel) — computed FIRST so tiles can inherit it ───
    // Three tones like the vanilla texture: light FRAME band around the
    // perimeter (~7 gui px, vanilla proportions), light-gray BODY, and the
    // dark cell wells drawn by the tile glass on top of it.
    // TAB TRANSITION: the NEW panel slides DOWN from above (offNew), while the
    // captured OLD frame slides UP and away beneath/behind it (uAnim.z).
    vec3 panelBase = vec3(0.0);
    float pmask = 0.0;
    vec2 panelC = uPanel.xy + vec2(0.0, offNew); // bottom-origin: +y = up
    if (uPanel.z > 0.5) {
        float pd = sdRoundedBox(px - panelC, uPanel.zw, uRing.w);
        pmask = 1.0 - smoothstep(-1.0, 1.0, pd);
        if (pmask > 0.001) {
            float edge = smoothstep(-8.0, -1.5, pd);
            vec2 cdir = (px - uPanel.xy) / max(length(px - uPanel.xy), 1e-4);
            vec2 puv = uv + cdir * edge * 4.0 / uScreen.xy;
            vec3 frosted = blur25(puv, texel, 4.2);
            // ── PAUSE-MENU MATERIAL UNIFICATION (P0) ──
            // Как у плиток кнопок / Recipe Book: blur мира + stable без серой краски.
            // Inventory теперь тот же clear optical family, спокойнее из-за размера.
            float pbl = dot(frosted, vec3(0.299, 0.587, 0.114));
            vec3 stable = clamp(mix(frosted, vec3(pbl), 0.22), 0.05, 0.95);
            vec3 base = mix(frosted, stable, 0.38);

            // ADAPTIVE BASE (P2). Material reacts to the local world behind it:
            //  - bright bg (snow/sky): dim + desaturate + kill spill so text reads
            //  - dark bg (Nether): lift so the panel never vanishes
            //  - busy bg (high local contrast): push harder toward 'stable'
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
            base = mix(base, base * vec3(0.88, 0.96, 1.08) + vec3(0.02, 0.03, 0.05), 0.28);
            base = mix(vec3(dot(base, vec3(0.299, 0.587, 0.114))), base, 1.15);
            // Near the border the bent world shows through more (glass edge).
            // Quieter than before: a bright halo here read as white glow.
            float edgeBand = smoothstep(-9.0, -2.0, pd) * (1.0 - smoothstep(-2.0, 0.5, pd));
            base = mix(base, texture(InSampler, puv).rgb, edgeBand * 0.30);
            // AMBIENT ADAPTATION (L0 → L1): мир — источник цвета. На ярком
            // фоне (снег/небо) база слегка гасится для читаемости; зелёный/
            // розовый spill приходит бесплатно через frosted.
            float bl = dot(frosted, vec3(0.299, 0.587, 0.114));
            base *= 1.0 - smoothstep(0.60, 0.85, bl) * 0.12;
            // Dark outer outline: относительный + АБСОЛЮТНЫЙ минимум — кромка
            // не исчезает в тёмном Nether и не белеет на снегу (толщина, а не stroke).
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
            // Uneven glass: gentle light from above + broad smudge patches —
            // a big panel must NOT read as one flat grey rectangle.
            base *= 1.0 + 0.05 * smoothstep(-uPanel.w, uPanel.w, (px - panelC).y);
            base *= 1.0 + (hash12(floor(px / 96.0)) - 0.5) * 0.06;
            base += (hash12(floor(px / 2.0)) - 0.5) * 0.008;
            // Appearance на панели: DARK затемняет тело внутри mask
            // (мир за ним не трогается), custom tint у BASE минимальный.
            base *= mix(1.0, 0.42, clamp(uTone.x, 0.0, 1.0));
            vec3 pTint = clamp(uTone.yzw, vec3(0.05), vec3(1.0));
            float ptStr = clamp(uWellMeta.z, 0.0, 1.0);
            vec3 pHue = pTint / max(dot(pTint, vec3(0.333)), 0.06);
            base *= mix(vec3(1.0), mix(vec3(1.0), pHue, 0.6), ptStr * 0.12);
            // TAB TRANSITION: the captured OLD tab frame slides UP and away
            // (clipped by the FINAL panel rect), revealing the new content.
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

    if (bestMask <= 0.001 && wellMask <= 0.001) {
        // Outside tiles: world, or the panel where it exists.
        fragColor = vec4(mix(texture(InSampler, uv).rgb, panelBase, pmask)
            * (1.0 - fxDark) + fxAdd, 1.0);
        return;
    }

    // Hover: sharp falloff - only the tile under the cursor reacts strongly.
    vec2 mouse = uScreen.zw;
    float hover = hoverOn ? (1.0 - smoothstep(20.0, 70.0, length(px - mouse))) : 0.0;

    // Rim band: width ADAPTS to element size + control gets slightly wider
    // (иначе на маленьких кнопках rim выглядел обрубком). Outer side follows light.
    float rimW = clamp(reach * (0.14 + 0.06 * controlLevel), 1.4 + 0.4 * controlLevel, 4.5);
    float rimBand = smoothstep(-rimW - density * 1.5, -1.0, bestD)
        * (1.0 - smoothstep(-1.0, 1.5 + density * 0.5, bestD));
    float refr = bestEdge * (1.0 - rimBand * 0.45) * (1.0 + hover * 0.6) * (1.0 - slotLevel * 0.7);
    // Внутри Well рефракция почти нулевая — это плоское углубление,
    // не линза; hover-ячейка сохраняет чуть живой отклик.
    refr *= 1.0 - wellMask * (0.9 - wellHover * 0.4);
    // Geometric density (iOS model): thicker glass BENDS more — refraction
    // scales with density instead of the material turning opaque.
    vec2 off = bestDir * (refr * uParams.y * (1.0 + 0.7 * density)) / uScreen.xy;
    off += wellBend;   // concave well: background dips inward (P1)

    // Chromatic aberration — subtle dispersion PROPORTIONAL to the refraction
    // vector. DISABLED on dense panels: on small tiles the lens covers the
    // whole cell and the split turns into rainbow fringing around every slot.
            float ab = aberrationOn ? 0.12 * (1.0 - density) * (1.0 - smoothstep(0.58, 0.92, bgL) * 0.85) : 0.0;

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
    vec2 uvP = uvT + parShift;

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
        // FreeformWell: центр ячейки почти без frost — мутность только к кромке.
        float localFrost = max(frostR * (1.0 - 0.65 * bestEdge) * (1.0 - 0.45 * density)
            * (1.0 - 0.45 * slotLevel), 1.0);
        body = mix(blur25(uvP + off, texel, localFrost), panelBase, inPanel);
    }

    // Near the rim blend in the sharp (aberrated) sample so dispersion survives
    // the frost; the matte centre keeps its frosted density.
    float sharpW = clamp(uParams.w + bestEdge * (frostOn ? 0.55 : 0.0), 0.0, 1.0);
    vec3 glass = mix(body, sharp, sharpW);

    // Иерархия отклика: интерактивные элементы живее, большие — спокойнее.
    // CONTENT = резкий, BASE = тихий, CONTROLS = живее, ACTIVE = заметен.
    float hovAmp = hover * (0.5 + 0.6 * clamp(controlLevel + activeLevel, 0.0, 1.0));

    // GROUP (L2): едва заметный локальный подъём яркости — группировка
    // читается БЕЗ отдельной карточки.
    glass *= 1.0 + 0.03 * groupLevel * bestMask;

    // ACTIVE/selected: gentle cool lift of the SAME material.
    glass = mix(glass, glass * vec3(0.97, 1.0, 1.05) + vec3(0.055, 0.06, 0.07), activeLevel * bestMask);
    // COMPANION (recipe book): прозрачнее — меньше cool-tint, меньше массы.
    glass = mix(glass, glass * vec3(0.88, 0.96, 1.08) + vec3(0.02, 0.03, 0.05),
        0.28 * (1.0 - 0.35 * compLevel) * bestMask);
    float luma = dot(glass, vec3(0.299, 0.587, 0.114));
    // ...VIBRANCY for dense panels (iOS model): saturate the background,
    // keep luminance - never paint over it.
    glass = mix(vec3(luma), glass, 1.0 + 0.35 * density);

    // ── APPEARANCE (LIGHT / DARK / AUTO): состояния одного материала ──
    // DARK затемняет только тело стекла ВНУТРИ mask; refraction/rim/edge
    // добавляются НИЖЕ и остаются заметными. LIGHT = darkness≈0.
    // uTone.x сглаживается в Java (hysteresis ~0.4 c) — без мигания.
    float darkness = clamp(uTone.x, 0.0, 1.0);
    glass *= mix(1.0, 0.42, darkness);

    // ── CUSTOM TINT (role-scaled): BASE почти нейтральный, CONTROL
    // заметнее, ACTIVE максимум; мир через стекло сохраняет цвет.
    float tStr = clamp(uWellMeta.z, 0.0, 1.0);
    vec3 userTint = clamp(uTone.yzw, vec3(0.05), vec3(1.0));
    vec3 tintHue = userTint / max(dot(userTint, vec3(0.333)), 0.06);
    if (tStr > 0.001) {
        float roleW = 0.15 + 0.35 * controlLevel + 0.55 * activeLevel;
        glass *= mix(vec3(1.0), mix(vec3(1.0), tintHue, 0.6), tStr * roleW);
        glass += userTint * tStr * roleW * 0.05;
    }

    // ── LUMINANCE DOCK (reuses blurred sampler, no new passes): samples
    // background luma under the dock region and scales dock opacity.
    float dockMaskScale = 1.0;
    float outerMask = 0.0;
    if (dockLevel > 0.001) {
        float bgL = dot(texture(InSampler, uv).rgb, vec3(0.299, 0.587, 0.114));
        // Тёмный фон → почти невидим (0.08), снег/яркий → до 0.32. Дёшево: 1 sample.
        float dockAlpha = mix(0.06, 0.32, smoothstep(0.45, 0.85, bgL));
        dockMaskScale = dockAlpha / 0.5; // 0.12..0.64
        // Outer refractive footprint: innerSdf dilated by outerPad (§3-4, §41)
        float outerPad = max(uDockParams.x, 0.0);
        float outer = 1.0 - smoothstep(-1.0, 1.0, bestD - outerPad);
        outerMask = clamp(outer - bestMask, 0.0, 1.0) * dockLevel;
        // Outer has much lower density but visible refraction/edge
        if (outerMask > 0.001) {
            vec2 outerOff = bestDir * outerMask * uDockParams.z * 0.6 / uScreen.xy;
            vec3 outerSample = texture(InSampler, uv + outerOff).rgb;
            // Very light fill, almost just refraction
            glass = mix(glass, outerSample, outerMask * 0.22);
        }
    }

    // Внутри well обычный контур гасится (кроме hover-ячейки) — до
    // вычисления rim-вкладов.
    rimBand *= 1.0 - wellMask * (1.0 - wellHover);

    // Fresnel rim: follows light (outer side bright, opposite dark), intensity by light.
    float topOnly = rimBand * smoothstep(0.0, 0.6, lightBias) * uLightDir.z * (rimOn ? 1.0 : 0.0);
    float rimK = clamp(1.0 - density * 0.7 - slotLevel * 0.55 - groupLevel * 0.6
        + controlLevel * 0.15 + activeLevel * 0.30 - compLevel * 0.15, 0.2, 1.4);
    vec3 rimTint = vec3(0.95, 0.98, 1.05) * ((0.08 + 0.32 * lightBias) * uParams.z)
        * (1.0 + hovAmp * 1.6) * rimK * (0.6 + 0.4 * uLightDir.z);
    // Custom tint подкрашивает edge-ответ (сильнее у контролов/активных).
    rimTint = mix(rimTint, rimTint * tintHue,
        tStr * (0.3 + 0.7 * clamp(controlLevel + activeLevel, 0.0, 1.0)));
    glass += topOnly * rimTint;
    // Edge definition: контур важнее у контролов и активных, тише у слотов
    // и групп — внешняя рамка окна всегда главнее внутренних линий.
    float rimAdd = (rimOn ? 0.25 : 0.12) * rimK;
    glass += rimBand * rimAdd * vec3(0.9, 0.95, 1.0) * bestMask;
    glass *= 1.0 - rimBand * (1.0 - topBias) * ((rimOn ? 0.10 : 0.0) - hovAmp * 0.06);
    glass *= 1.0 + hovAmp * 0.10 * bestMask;

    // Thickness cues (iOS model) for dense HUD panels only — slot cells must
    // NOT stack inner shadows at shared edges (that caused the dark grid).
    float innerShadow = (1.0 - smoothstep(-3.5, -0.5, bestD)) * density
        * (1.0 - slotLevel) * bestMask;
    glass *= 1.0 - innerShadow * (0.10 + 0.14 * (1.0 - topBias));
    float topLine = smoothstep(-2.2, -1.2, bestD) * (1.0 - smoothstep(-1.2, -0.2, bestD))
        * smoothstep(0.55, 0.9, bestDir.y) * density * (1.0 - slotLevel) * bestMask;
    glass += topLine * vec3(0.05, 0.055, 0.065);
    // Outer contour of the dense HUD bar: thin light outline like the panels'
    // frame — the window border outranks every internal divider.
    float barEdge = smoothstep(-2.4, -1.1, bestD) * (1.0 - smoothstep(-0.6, 1.2, bestD))
        * density * (1.0 - slotLevel) * bestMask;
    glass += barEdge * vec3(0.075, 0.08, 0.09);

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
        float lattice = max(1.0 - smoothstep(0.4, 1.5, fx), smoothstep(8.8, 10.0, fy)) * inX;
        float well = smoothstep(0.9, 2.4, fx) * (1.0 - smoothstep(7.6, 9.4, fy)) * inX;
        float wellTop = well * smoothstep(2.0, 7.0, fy);  // darker toward visual top
        glass += lattice * vec3(0.032, 0.036, 0.042) * bestMask;
        glass = mix(glass, glass * 0.80, clamp(well * 0.40 * bestMask, 0.0, 1.0));
        glass = mix(glass, glass * 0.90, clamp(wellTop * 0.25 * bestMask, 0.0, 1.0));
    }

    // SLOT cells (FreeformWell): recess прижат к кромке — центр остаётся
    // прозрачным продолжением панели, а не серой плиткой.
    float slotRecessK = slotLevel * bestMask * (0.15 + 0.85 * bestEdge);
    glass = mix(glass, glass * 0.93, clamp(slotRecessK * 0.5, 0.0, 1.0));

    // ─── WELL RECESS PROFILE: колодец в цельном стекле ───
    // Центр ячейки — прозрачное продолжение панели (никакого fill/noise);
    // вся глубина живёт на bevel-кромке шириной ~2px.
    if (wellMask > 0.001) {
        // TRUE CONCAVE WELL (P1): the centre stays a clear continuation of the
        // panel (no fill / no noise), all depth lives in the CURVED WALL between
        // the inner clear hole and the outer flat panel. The wall faces INWARD
        // (toward the cell centre) — the inverse of a convex tile — so the
        // captured world dips into a real recess and neighbouring cells read as
        // ONE continuous glass surface (no drawn divider).
        float gsc = max(uRing.w / 4.0, 0.5);
        float rf = clamp(uParams.x, 0.0, 1.0) * 0.9;
        float wW = 3.0 * gsc;                                   // wall width (fb px)
        float iD = sdRoundedBox(px - cellC, cellHalf, rf * min(cellHalf.x, cellHalf.y));
        float oD = sdRoundedBox(px - cellC, cellHalf + wW, rf * min(cellHalf.x + wW, cellHalf.y + wW));
        float core = smoothstep(0.0, 1.5, -iD);                 // 1 inside clear hole
        float outer = smoothstep(0.0, 1.5, oD);                 // 1 outside flat panel
        float wall = (1.0 - core) * (1.0 - outer);              // curved wall band
        float wf = clamp(iD / wW, 0.0, 1.0);                    // 0 inner lip -> 1 flat
        // Light from top (bottom-origin): the TOP inner lip is shadowed, the
        // BOTTOM inner wall catches light — concave read (opposite of convex).
        float topLip = 1.0 - smoothstep(0.0, 2.5, (cellC.y + cellHalf.y) - px.y);
        float botLip = 1.0 - smoothstep(0.0, 2.5, px.y - (cellC.y - cellHalf.y));
        glass *= 1.0 - wall * (0.06 + 0.12 * topLip) * (1.0 - wf);
        glass += wall * botLip * vec3(0.022, 0.025, 0.030) * (1.0 - wf);
        // crisp contact shadow exactly at the clear/wall seam (depth cue)
        glass *= 1.0 - core * (1.0 - outer) * 0.06;
        // HOVER: a focused edge response on the selected cell — no bright square.
        float hovBand = wellHover * wall * (1.0 - wf * 0.5);
        glass += hovBand * vec3(0.060, 0.066, 0.078);
    }

    // Procedural glass texture: fine grain (2px cells) + broad smudge patches.
    float grain = hash12(floor(px / 2.0)) - 0.5;
    float smudge = hash12(floor(px / 96.0)) - 0.5;
    glass += grain * 0.018 * bestMask;
    glass *= 1.0 + smudge * 0.04 * bestMask;

    // CONTACT SHADOW (§19): мир чуть темнеет у кромки поверхности — край
    // ощущается как толщина стекла, а не нарисованная линия.
    vec3 worldC = texture(InSampler, uv).rgb;
    float outD = max(bestD, 0.0);
    float contact = exp(-max(outD - 1.5, 0.0) * 0.22) * smoothstep(0.5, 3.0, outD);
    worldC *= 1.0 - contact * 0.11 * (1.0 - topBias * 0.5);
    if (uPanel.z > 0.5) {
        float pdp = sdRoundedBox(px - uPanel.xy, uPanel.zw, uRing.w);
        float cp = exp(-max(pdp - 1.5, 0.0) * 0.16) * smoothstep(0.5, 3.0, pdp);
        worldC *= 1.0 - cp * 0.09;
    }

    // Нижняя подстилка вырезается слотами (§ base - wellInner) — внутри колодца
    // нет блюра/стекла, только sharp мир, как у плиток кнопок/Recipe Book.
    float baseMaskCut = bestMask * (1.0 - coreMask);
    float effectiveTileMask = mix(baseMaskCut, baseMaskCut * dockMaskScale, dockLevel);
    effectiveTileMask = max(effectiveTileMask, outerMask);
    // Opaque composite: world/panel outside, tile glass / well wall inside, clear hole — world
    vec4 outColor = vec4(mix(worldC, glass, max(effectiveTileMask, wellMask)), 1.0);
    // Furnace FX поверх composite (flame spill / process channel).
    outColor.rgb = outColor.rgb * (1.0 - fxDark) + fxAdd;

    // Selected-slot ring (uMeta.w/x, uMeta.z/y): a QUIET state, not a glowing
    // box — noticeably brighter than slot seams, far quieter than the outer
    // window border. Still glides between slots.
    if (uMeta.w >= 0.0 && uMeta.z >= 0.0) {
        vec2 ringC = vec2(uMeta.w, uMeta.z);
        float d = sdRoundedBox(px - ringC, uRing.xy, uRing.w);
        float ring = 1.0 - smoothstep(uRing.z * 0.4, uRing.z, abs(d));
        float glow = exp(-max(abs(d) - uRing.z, 0.0) * 0.5) * 0.10;
        vec3 ringCol = vec3(0.94, 0.97, 1.05);
        outColor.rgb = mix(outColor.rgb, ringCol, ring * 0.5);
        outColor.rgb += ringCol * glow;
    }

    fragColor = outColor;
}
