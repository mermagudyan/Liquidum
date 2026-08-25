package com.liquidum.client.debug;

import com.liquidum.LiquidumMod;

/**
 * Central debug/test state for the Liquidum Lab (F7).
 * Every subsystem toggle lives here; the renderer and the shader read it.
 */
public class LiquidumDebugState {

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
	/** Frost gaussian radius in pixels (= blur radius fed to blur25). */
	public static float frostRadius = 10.0f;

	/** SDF fusion of neighbouring tiles (metaball merge); radius in px, 0/off = hard union. */
	public static boolean fusion = true;
	public static float fusionRadius = 18.0f;

	/** Open animation: tiles grow out of their centres with a slight rise (easeOutCubic). */
	public static boolean animOpen = true;
	public static float animMillis = 220.0f;

	/** Material params (mirrored from glass.fsh; all editable live from the Lab). */
	public static float cornerRadiusFraction = 0.35f;
	public static float refraction = 40.0f;
	public static float fresnel = 1.0f;
	public static float sharpnessMix = 0.08f;

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
			"[lab] STATE: mode={} hover={} aberration={} rim={} frost={} frostRadius={} fusion={} fusionRadius={} animOpen={} animMillis={} crashOnError={}",
			modeName(), hover, aberration, rim, frost, frostRadius, fusion, fusionRadius, animOpen, animMillis, crashOnError);
		LiquidumMod.LOGGER.info(
			"[lab] MATERIAL: cornerRadius={} refraction={} fresnel={} sharpnessMix={}",
			cornerRadiusFraction, refraction, fresnel, sharpnessMix);
	}
}
