package com.liquidum.client.shape;

/**
 * Shape rendering helpers.
 *
 * <p>{@link #sampleMask} is a CPU SDF sampler usable anywhere (debug, inspector,
 * off-line tests) — no GL context required.
 *
 * <p>{@link #renderMask} is the GPU entry point. Its real implementation belongs
 * to the MC 26.x RenderPass-based renderer (see LiquidGlassRenderer rewrite) and
 * is intentionally a no-op here so callers can be wired up without a context.
 */
public final class ShapeRenderer {
	private ShapeRenderer() {
	}

	/** CPU coverage sample in [0,1]: 1 = inside shape, 0 = outside. */
	public static float sampleMask(LiquidumShape shape, float px, float py) {
		float d = shape.sdf(px, py);
		return 1.0f - smoothstep(-1.0f, 1.0f, d);
	}

	/**
	 * GPU mask draw for the shape. Implemented later in the RenderPass-based
	 * renderer. No-op for now.
	 */
	public static void renderMask(LiquidumShape shape) {
		// TODO(renderer): draw SDF mask via RenderPass / CommandEncoder (MC 26.x)
	}

	private static float smoothstep(float e0, float e1, float x) {
		float t = Math.max(0.0f, Math.min(1.0f, (x - e0) / (e1 - e0)));
		return t * t * (3.0f - 2.0f * t);
	}
}
