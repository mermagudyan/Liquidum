package com.liquidum.client.material;

public record Color3f(float r, float g, float b) {
	public static final Color3f WHITE = new Color3f(1.0f, 1.0f, 1.0f);
	public static final Color3f BLACK = new Color3f(0.0f, 0.0f, 0.0f);

	public Color3f scale(float s) {
		return new Color3f(r * s, g * s, b * s);
	}
}
