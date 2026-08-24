package com.liquidum.client.material;

/**
 * Factory for the 8 roadmap material variants. Each returns a fresh, mutable
 * {@link LiquidumMaterial} so callers can tweak without affecting the preset.
 */
public final class MaterialPresets {
	private MaterialPresets() {
	}

	public static LiquidumMaterial regular() {
		LiquidumMaterial m = new LiquidumMaterial();
		m.blurRadius = 8.0f; m.blurResolution = 0.25f; m.blurSamples = 3;
		m.saturation = 1.1f;
		m.edgeHighlight = 0.5f; m.highlightWidth = 0.5f; m.specularStrength = 0.3f;
		m.refractionStrength = 0.03f; m.distortionStrength = 0.02f;
		m.shadowStrength = 0.2f; m.innerReflection = 0.1f;
		m.parallaxStrength = 0.02f; m.cornerRadius = 4.0f; m.borderThickness = 1.0f;
		return m;
	}

	public static LiquidumMaterial clear() {
		LiquidumMaterial m = regular();
		m.opacity = 0.85f;
		m.blurRadius = 4.0f; m.blurResolution = 0.5f;
		m.refractionStrength = 0.015f; m.distortionStrength = 0.01f;
		m.edgeHighlight = 0.7f; m.specularStrength = 0.2f;
		m.cornerRadius = 6.0f;
		return m;
	}

	public static LiquidumMaterial frosted() {
		LiquidumMaterial m = regular();
		m.opacity = 1.0f;
		m.blurRadius = 12.0f; m.blurSamples = 5;
		m.saturation = 0.95f;
		m.edgeHighlight = 0.3f; m.specularStrength = 0.15f;
		m.refractionStrength = 0.01f; m.distortionStrength = 0.005f;
		m.shadowStrength = 0.35f; m.cornerRadius = 4.0f;
		return m;
	}

	public static LiquidumMaterial dark() {
		LiquidumMaterial m = regular();
		m.backgroundTint = new Color3f(0.08f, 0.08f, 0.10f);
		m.opacity = 0.92f;
		m.luminosityAdjustment = -0.15f;
		m.saturation = 1.05f;
		m.refractionStrength = 0.025f;
		m.cornerRadius = 5.0f;
		return m;
	}

	public static LiquidumMaterial light() {
		LiquidumMaterial m = regular();
		m.backgroundTint = new Color3f(0.95f, 0.95f, 1.0f);
		m.opacity = 0.90f;
		m.luminosityAdjustment = 0.10f;
		m.cornerRadius = 5.0f;
		return m;
	}

	public static LiquidumMaterial performance() {
		LiquidumMaterial m = regular();
		m.blurSamples = 1;
		m.blurRadius = 4.0f; m.blurResolution = 0.5f;
		m.refractionStrength = 0.0f; m.distortionStrength = 0.0f;
		m.noiseAmount = 0.0f;
		m.edgeHighlight = 0.4f; m.specularStrength = 0.1f;
		m.cornerRadius = 3.0f;
		return m;
	}

	public static LiquidumMaterial strong() {
		LiquidumMaterial m = regular();
		m.refractionStrength = 0.08f; m.distortionStrength = 0.06f;
		m.blurRadius = 14.0f; m.blurSamples = 5;
		m.specularStrength = 0.5f; m.edgeHighlight = 0.8f;
		m.cornerRadius = 8.0f;
		return m;
	}

	public static LiquidumMaterial weak() {
		LiquidumMaterial m = regular();
		m.opacity = 1.0f;
		m.blurRadius = 3.0f; m.blurSamples = 1;
		m.refractionStrength = 0.005f; m.distortionStrength = 0.002f;
		m.edgeHighlight = 0.2f; m.specularStrength = 0.1f;
		m.cornerRadius = 2.0f;
		return m;
	}
}
