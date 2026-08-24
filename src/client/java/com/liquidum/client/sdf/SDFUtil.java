package com.liquidum.client.sdf;

public class SDFUtil {

	public static float smoothMinimum(float a, float b, float k) {
		float h = Math.max(k - Math.abs(a - b), 0.0f) / k;
		return Math.min(a, b) - h * h * k * 0.25f;
	}

	public static float roundedBoxSDF(float px, float py, float bx, float by, float bw, float bh, float br) {
		float dx = Math.max(Math.abs(px - bx) - bw + br, 0.0f);
		float dy = Math.max(Math.abs(py - by) - bh + br, 0.0f);
		return (float)Math.sqrt(dx * dx + dy * dy) - br;
	}

	public static float circleSDF(float px, float py, float cx, float cy, float r) {
		float dx = px - cx;
		float dy = py - cy;
		return (float)Math.sqrt(dx * dx + dy * dy) - r;
	}

	public static float pillSDF(float px, float py, float ax, float ay, float bx, float by, float r) {
		float pax = px - ax;
		float pay = py - ay;
		float bax = bx - ax;
		float bay = by - ay;
		float h = Math.max(0.0f, Math.min(1.0f, (pax * bax + pay * bay) / (bax * bax + bay * bay)));
		float dx = pax - bax * h;
		float dy = pay - bay * h;
		return (float)Math.sqrt(dx * dx + dy * dy) - r;
	}

	public static float smoothUnion(float d1, float d2, float k) {
		float h = Math.max(k - Math.abs(d1 - d2), 0.0f) / k;
		return Math.min(d1, d2) - h * h * k * 0.25f;
	}

	public static float smoothIntersection(float d1, float d2, float k) {
		float h = Math.max(k - Math.abs(d1 - d2), 0.0f) / k;
		return Math.max(d1, d2) + h * h * k * 0.25f;
	}

	public static float smoothSubtraction(float d1, float d2, float k) {
		float h = Math.max(k - Math.abs(-d1 - d2), 0.0f) / k;
		return Math.max(-d1, d2) + h * h * k * 0.25f;
	}
}
