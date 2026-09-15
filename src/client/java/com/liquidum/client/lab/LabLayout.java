package com.liquidum.client.lab;

// Two layout states with animated morph factor
public class LabLayout {
	public enum Mode { NORMAL, PREVIEW }

	public static final int GAP = 16;
	public static final int LEFT_W = 220;

	public final int screenW;
	public final int screenH;
	public final Mode mode;
	public final float transition;
	public final boolean dockRight;

	public LabLayout(int screenW, int screenH, Mode mode, float transition) {
		this(screenW, screenH, mode, transition, false);
	}

	public LabLayout(int screenW, int screenH, Mode mode, float transition, boolean dockRight) {
		this.screenW = screenW;
		this.screenH = screenH;
		this.mode = mode;
		this.transition = Math.max(0f, Math.min(1f, transition));
		this.dockRight = dockRight;
	}

	public int leftX() {
		return dockRight ? screenW - 12 - LEFT_W : 12;
	}

	public int leftW() {
		return LEFT_W;
	}

	// Demo zone, zero size when demo off, mirrors the dock side
	public int[] viewport() {
		int previewW = (int) ((screenW - LEFT_W - GAP * 3) * transition);
		if (mode == Mode.NORMAL && transition <= 0f) {
			return new int[]{screenW, 0, 0, 0};
		}
		int vx = dockRight ? GAP : screenW - GAP - Math.max(0, previewW);
		return new int[]{vx, 40, Math.max(0, previewW), screenH - 40 - 36};
	}

	public static float approach(float current, float target, float delta, float speed) {
		if (current < target) {
			return Math.min(target, current + delta * speed);
		}
		return Math.max(target, current - delta * speed);
	}
}
