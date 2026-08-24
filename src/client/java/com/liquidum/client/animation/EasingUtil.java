package com.liquidum.client.animation;

public class EasingUtil {

	public static float linear(float t) {
		return t;
	}

	public static float easeInQuad(float t) {
		return t * t;
	}

	public static float easeOutQuad(float t) {
		return t * (2.0f - t);
	}

	public static float easeInOutQuad(float t) {
		return t < 0.5f ? 2.0f * t * t : -1.0f + (4.0f - 2.0f * t) * t;
	}

	public static float easeInCubic(float t) {
		return t * t * t;
	}

	public static float easeOutCubic(float t) {
		float f = t - 1.0f;
		return f * f * f + 1.0f;
	}

	public static float easeInOutCubic(float t) {
		return t < 0.5f ? 4.0f * t * t * t : (t - 1.0f) * (2.0f * t - 2.0f) * (2.0f * t - 2.0f) + 1.0f;
	}

	public static float easeOutBack(float t) {
		float c1 = 1.70158f;
		float c3 = c1 + 1.0f;
		float tm1 = t - 1.0f;
		return 1.0f + c3 * tm1 * tm1 * tm1 + c1 * tm1 * tm1;
	}

	public static float easeOutElastic(float t) {
		if (t == 0.0f || t == 1.0f) return t;
		float p = 0.3f;
		float s = p / 4.0f;
		return (float)(Math.pow(2.0, -10.0 * t) * Math.sin((t - s) * (2.0 * Math.PI) / p) + 1.0);
	}

	public static float easeOutBounce(float t) {
		float n1 = 7.5625f;
		float d1 = 2.75f;
		if (t < 1.0f / d1) {
			return n1 * t * t;
		} else if (t < 2.0f / d1) {
			t -= 1.5f / d1;
			return n1 * t * t + 0.75f;
		} else if (t < 2.5f / d1) {
			t -= 2.25f / d1;
			return n1 * t * t + 0.9375f;
		} else {
			t -= 2.625f / d1;
			return n1 * t * t + 0.984375f;
		}
	}

	public static float easeInBack(float t) {
		float c1 = 1.70158f;
		float c3 = c1 + 1.0f;
		return c3 * t * t * t - c1 * t * t;
	}

	public static float spring(float t, float damping, float frequency) {
		return 1.0f - (float)(Math.exp(-damping * t) * Math.cos(frequency * t));
	}

	public static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	public static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}

	public static float smoothstep(float edge0, float edge1, float x) {
		float t = clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
		return t * t * (3.0f - 2.0f * t);
	}
}
