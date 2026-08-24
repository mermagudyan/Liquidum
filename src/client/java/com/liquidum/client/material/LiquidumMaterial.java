package com.liquidum.client.material;

import com.liquidum.client.sdf.SDFUtil;

/**
 * Material model: 18 parameters describing how a glass surface looks and behaves.
 * Pure data + SDF geometry — no GL, safe to build/use anywhere.
 *
 * Composition stages (ТЗ §2): background sampling → blur → luminance adaptation
 * → color transmission → edge response → internal reflection → refraction →
 * depth separation → optional noise → composite.
 */
public class LiquidumMaterial {
	// 1. Background / blur
	public float opacity = 1.0f;
	public float blurRadius = 8.0f;
	public float blurResolution = 0.25f;   // downsample scale
	public int blurSamples = 3;

	// 2. Color / luminance
	public float luminosityAdjustment = 0.0f;
	public float saturation = 1.0f;
	public Color3f backgroundTint = Color3f.WHITE;

	// 3. Edges / highlights
	public float edgeHighlight = 0.5f;
	public float highlightWidth = 0.5f;
	public float specularStrength = 0.3f;

	// 4. Refraction / distortion
	public float refractionStrength = 0.03f;
	public float distortionStrength = 0.02f;
	public float parallaxStrength = 0.02f;

	// 5. Depth / surface
	public float noiseAmount = 0.0f;
	public float shadowStrength = 0.2f;
	public float innerReflection = 0.1f;

	// 6. Geometry defaults
	public float cornerRadius = 4.0f;
	public float borderThickness = 1.0f;
	public int shapeType = 1;        // 0=rect, 1=rounded rect, 2=ellipse, 3=circle
	public float feather = 1.0f;     // SDF edge softness (gui px)

	public LiquidumMaterial() {
	}

	public LiquidumMaterial(LiquidumMaterial src) {
		this.opacity = src.opacity;
		this.blurRadius = src.blurRadius;
		this.blurResolution = src.blurResolution;
		this.blurSamples = src.blurSamples;
		this.luminosityAdjustment = src.luminosityAdjustment;
		this.saturation = src.saturation;
		this.backgroundTint = src.backgroundTint;
		this.edgeHighlight = src.edgeHighlight;
		this.highlightWidth = src.highlightWidth;
		this.specularStrength = src.specularStrength;
		this.refractionStrength = src.refractionStrength;
		this.distortionStrength = src.distortionStrength;
		this.parallaxStrength = src.parallaxStrength;
		this.noiseAmount = src.noiseAmount;
		this.shadowStrength = src.shadowStrength;
		this.innerReflection = src.innerReflection;
		this.cornerRadius = src.cornerRadius;
		this.borderThickness = src.borderThickness;
		this.shapeType = src.shapeType;
		this.feather = src.feather;
	}

	public LiquidumMaterial copy() {
		return new LiquidumMaterial(this);
	}
}
