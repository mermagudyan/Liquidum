package com.liquidum.client.motion;

/**
 * Reusable elastic overscroll (§10–12).
 * effective = raw / (1 + k*|raw|)  — resistance grows with distance.
 * Foreground (icons/text/player) stays rigid, only outer glass shape deforms
 * (handled in shader via separate uniform if needed).
 */
public final class ElasticOverscroll {

	private ElasticOverscroll() {}

	/** Resistance curve, k ~ 0.015 for gui px. */
	public static float apply(float raw, float k) {
		if (raw == 0) return 0;
		float sign = Math.signum(raw);
		float abs = Math.abs(raw);
		return sign * (abs / (1f + k * abs));
	}

	public static float apply(float raw) {
		return apply(raw, 0.015f);
	}

	/** Max overscroll before hard clamp (gui px). */
	public static float clamp(float v, float max) {
		return Math.max(-max, Math.min(max, v));
	}
}
