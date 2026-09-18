package com.liquidum.client.debug;

import com.liquidum.LiquidumMod;

/**
 * Central debug/test state for the Liquidum Lab (F7).
 * Every subsystem toggle lives here; the renderer and the shader read it.
 */
public class LiquidumDebugState {

	// Master switch via JVM argument -Dliquidum.debug=true, off for regular players
	public static final boolean DEBUG_BUILD = Boolean.getBoolean("liquidum.debug");

	/** Render mode: 0 = raw capture passthrough, 1 = mask visualization, 2 = full material. */
	public static int mode = 2;

	/** Hover flare on the tile under the cursor. */
	public static boolean hover = true;
	/** Chromatic aberration on lens edges. */
	public static boolean aberration = true;
	/** Fresnel rim (top edge glow). */
	public static boolean rim = true;
	/** Frosted gaussian interior (off = sharp glass). */
	public static boolean frost = true;
	/** Frost gaussian radius in pixels (= blur radius fed to blur25). iPhone 6-7, не 10 — читаемость. */
	public static float frostRadius = 10.0f;

	// Draw red crosses on vanilla Slot centres and green crosses on GridWell shader cell centres to verify the two coordinate systems coincide.
	public static boolean debugGeometry = false;

	// Floating glass probe: one circle on every screen, dragged by cursor
	public static boolean probeShow = false;
	public static float probeX = 0f;
	public static float probeY = 0f;
	public static float probeDiameter = 120f;
	// Probe drag state owned by the container dispatch mixin
	public static boolean probeDrag = false;
	public static float probeGrabDX = 0f;
	public static float probeGrabDY = 0f;

	/** SDF fusion of neighbouring tiles (metaball merge); radius in px, 0/off = hard union. iPhone 12, не 18 — blob умеренный. */
	public static boolean fusion = true;
	public static float fusionRadius = 12.0f;

	// Custom user settings gate, mutually exclusive with fusion
	public static boolean customSettings = false;

	/** Open animation: tiles grow out of their centres with a slight rise (easeOutCubic). */
	public static boolean animOpen = false; // P: анимации появления кнопок off — не мозолит глаза
	public static float animMillis = 220.0f;

	/** Material params (mirrored from glass.fsh; all editable live from the Lab). iPhone: тоньше, мягче, без мыла. */
	public static float cornerRadiusFraction = 0.18f;
	public static float refraction = 9.0f;
	public static float edgeWidth = 1.0f;
	public static float fresnel = 0.65f;
	public static float sharpnessMix = 0.18f;
	public static float sunSpec = 1.0f;

	// Solo isolation stage for Lab rework, 0 = full composite
	public static int soloStage = 0;
	// Master switch for cursor parallax drift in the shader
	public static boolean parallax = true;
	// Backdrop color bleed into the body, 0..0.3
	public static float bodyBleed = 0.10f;
	// Backdrop hue gathered at the rim, 0..1.5
	public static float edgeBleed = 0.65f;
	// Rim dispersion strength, 0..1.5
	public static float chroma = 0.45f;
	// Manual light for Lab tuning, world probe off while true
	public static boolean lightManual = false;
	// Light direction angle in screen space, radians
	public static float lightAngle = 2.16f;
	// Manual light level, 0..1
	public static float lightLevel = 0.8f;

	/**
	 * When true, fragile render failures (chain missing, process error, uniform
	 * buffer gone) throw instead of being swallowed → you get a real crash report
	 * with a full stack trace instead of a silent "gray screen".
	 * Seeded from config.crashOnError at core init; the Lab toggle overrides it
	 * for the current session.
	 */
	public static boolean crashOnError = false;

	public static String modeName() {
		return switch (mode) {
			case 0 -> "CAPTURE";
			case 1 -> "MASK";
			case 2 -> "FULL";
			case 3 -> "DIAG:uScreen";
			case 4 -> "DIAG:uMeta(count)";
			case 5 -> "DIAG:uRects[0]";
			case 6 -> "DIAG:MAGENTA";
			default -> "FULL";
		};
	}

	public static void cycleMode() {
		mode = (mode + 1) % 7;
		LiquidumMod.LOGGER.info("[lab] mode = {}", modeName());
	}

	public static void dump() {
		LiquidumMod.LOGGER.info(
			"[lab] STATE: mode={} hover={} aberration={} rim={} frost={} frostRadius={} fusion={} fusionRadius={} custom={} animOpen={} animMillis={} crashOnError={}",
			modeName(), hover, aberration, rim, frost, frostRadius, fusion, fusionRadius, customSettings, animOpen, animMillis, crashOnError);
		LiquidumMod.LOGGER.info(
			"[lab] MATERIAL: cornerRadius={} refraction={} edgeWidth={} fresnel={} sharpnessMix={} sunSpec={} solo={} parallax={} bleed={}/{}/{} light={}/{}/{} probe={}@{},{}x{}",
			cornerRadiusFraction, refraction, edgeWidth, fresnel, sharpnessMix, sunSpec, soloStage, parallax, bodyBleed, edgeBleed, chroma, lightManual, lightAngle, lightLevel, probeShow, (int) probeX, (int) probeY, (int) probeDiameter);
	}

	// Solo stage names for the Lab cycler, index matches soloStage
	public static String soloName() {
		return soloNameOf(soloStage);
	}

	public static String soloNameOf(int stage) {
		return switch (stage) {
			case 1 -> "BACKDROP";
			case 2 -> "MASK";
			case 3 -> "SDF";
			case 4 -> "EDGE";
			case 5 -> "BLUR";
			case 6 -> "NORMAL";
			case 7 -> "REFR UV";
			case 8 -> "BLEED";
			case 9 -> "HIGHLIGHT";
			case 10 -> "ELEV";
			case 11 -> "FUSION";
			case 12 -> "REFR SOURCE";
			case 13 -> "TOPMOST";
			case 14 -> "TAILSRC";
			default -> "FULL";
		};
	}

	public static String soloDescOf(int stage) {
		return switch (stage) {
			case 1 -> "raw chain input, no glass";
			case 2 -> "tile coverage as cyan";
			case 3 -> "fused distance field as gray";
			case 4 -> "lens gate as white";
			case 5 -> "frosted body sample alone";
			case 6 -> "SDF refraction direction as color";
			case 7 -> "lens displacement as color";
			case 8 -> "backdrop color pickup alone";
			case 9 -> "rim light plus caustic on black";
			case 10 -> "covering tile height as gray";
			case 11 -> "fuse groups as color, joints lighten";
			case 12 -> "refracted sample before frost";
			case 13 -> "winning tile material as color";
			case 14 -> "raw overlay input for popup glass";
			default -> "final glass over world";
		};
	}
}
