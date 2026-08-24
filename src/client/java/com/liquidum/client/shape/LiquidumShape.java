package com.liquidum.client.shape;

import com.liquidum.client.material.LiquidumMaterial;
import com.liquidum.client.sdf.SDFUtil;

/**
 * Geometric description of a glass surface. Pure SDF/geometry model — no GL.
 * The renderer later consumes this to produce per-widget masks.
 */
public class LiquidumShape {
	public enum Type {
		RECTANGLE,
		ROUNDED_RECTANGLE,
		CIRCLE,
		CAPSULE,
		PILL,
		CUSTOM
	}

	private Type type = Type.ROUNDED_RECTANGLE;
	private float x = 0.0f;
	private float y = 0.0f;
	private float width = 0.0f;
	private float height = 0.0f;
	private float cornerRadius = 4.0f;
	private float borderThickness = 1.0f;
	private LiquidumMaterial material = new LiquidumMaterial();

	// Animation state (0..1, 1 = fully shown). Consumed by animation system later.
	private float animation = 1.0f;

	public LiquidumShape() {
	}

	public LiquidumShape(Type type, float x, float y, float width, float height) {
		this.type = type;
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
	}

	public Type getType() {
		return type;
	}

	public void setType(Type type) {
		this.type = type;
	}

	public float getX() {
		return x;
	}

	public float getY() {
		return y;
	}

	public float getWidth() {
		return width;
	}

	public float getHeight() {
		return height;
	}

	public void setBounds(float x, float y, float width, float height) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
	}

	public float getCornerRadius() {
		return cornerRadius;
	}

	public void setCornerRadius(float cornerRadius) {
		this.cornerRadius = cornerRadius;
	}

	public float getBorderThickness() {
		return borderThickness;
	}

	public void setBorderThickness(float borderThickness) {
		this.borderThickness = borderThickness;
	}

	public LiquidumMaterial getMaterial() {
		return material;
	}

	public void setMaterial(LiquidumMaterial material) {
		if (material != null) {
			this.material = material;
		}
	}

	public float getAnimation() {
		return animation;
	}

	public void setAnimation(float animation) {
		this.animation = animation;
	}

	public boolean contains(float px, float py) {
		return sdf(px, py) <= 0.0f;
	}

	/**
	 * Signed distance field evaluation in the shape's local pixel space.
	 * Negative inside, positive outside, 0 on the edge.
	 */
	public float sdf(float px, float py) {
		float cx = x + width * 0.5f;
		float cy = y + height * 0.5f;
		float hw = width * 0.5f;
		float hh = height * 0.5f;
		float r = Math.min(cornerRadius, Math.min(hw, hh));

		return switch (type) {
			case RECTANGLE -> SDFUtil.roundedBoxSDF(px, py, cx, cy, hw, hh, 0.0f);
			case ROUNDED_RECTANGLE -> SDFUtil.roundedBoxSDF(px, py, cx, cy, hw, hh, r);
			case CIRCLE -> SDFUtil.circleSDF(px, py, cx, cy, Math.min(hw, hh));
			case CAPSULE, PILL -> SDFUtil.pillSDF(px, py, x, cy, x + width, cy, Math.min(hw, hh));
			case CUSTOM -> SDFUtil.roundedBoxSDF(px, py, cx, cy, hw, hh, r);
		};
	}
}
