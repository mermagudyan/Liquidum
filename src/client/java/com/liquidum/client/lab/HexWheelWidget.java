package com.liquidum.client.lab;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

// Procedural HSV wheel, no textures: hue is the angle, saturation is the radius
public class HexWheelWidget extends AbstractWidget {
	public static final int WHEEL_D = 96;
	public static final int HEIGHT = 128;
	private static final int SEGS = 36;
	private static final int RINGS = 10;
	private static final int BAR_W = 14;
	private static final int GAP = 8;
	private static final int SWATCH_H = 12;
	private static final int HIST_H = 12;
	private static final int HIST_W = 20;

	private float hue;
	private float sat = 1f;
	private float brightness = 1f;
	private final Consumer<Integer> onPick;
	private final java.util.List<Integer> history;
	private final net.minecraft.client.gui.Font font;

	public HexWheelWidget(int x, int y, int argb, net.minecraft.client.gui.Font font,
		java.util.List<Integer> history, Consumer<Integer> onPick) {
		super(x, y, WHEEL_D + GAP + BAR_W, HEIGHT, Component.literal("HEX"));
		this.font = font;
		this.history = history;
		this.onPick = onPick;
		applyColor(argb);
	}

	private void applyColor(int argb) {
		float[] hsv = rgbToHsv(argb);
		hue = hsv[0];
		sat = hsv[1];
		brightness = hsv[2];
	}

	private int currentRgb() {
		return 0xFF000000 | hsvToRgb(hue, sat, brightness);
	}

	private int cx() {
		return getX() + WHEEL_D / 2;
	}

	private int cy() {
		return getY() + WHEEL_D / 2;
	}

	private int barX() {
		return getX() + WHEEL_D + GAP;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		int cx = cx();
		int cy = cy();
		int r = WHEEL_D / 2;
		for (int s = 0; s < SEGS; s++) {
			double a = (s + 0.5) / SEGS * Math.PI * 2.0;
			double dx = Math.cos(a);
			double dy = Math.sin(a);
			for (int ring = 0; ring < RINGS; ring++) {
				float rr = (ring + 0.5f) / RINGS * r;
				float cell = (float) (2.0 * Math.PI * rr / SEGS) + 1.5f;
				int rgb = hsvToRgb(s / (float) SEGS, (ring + 1f) / RINGS, brightness);
				g.fill((int) (cx + dx * rr - cell / 2), (int) (cy + dy * rr - cell / 2),
					(int) (cx + dx * rr + cell / 2), (int) (cy + dy * rr + cell / 2), 0xFF000000 | rgb);
			}
		}
		int bx = barX();
		int steps = 24;
		for (int i = 0; i < steps; i++) {
			float v = 1f - i / (float) (steps - 1);
			int rgb = hsvToRgb(hue, sat, v);
			int y0 = getY() + i * WHEEL_D / steps;
			int y1 = getY() + (i + 1) * WHEEL_D / steps;
			g.fill(bx, y0, bx + BAR_W, y1, 0xFF000000 | rgb);
		}
		// Brightness marker remembers its spot, translucent so the bar reads through
		int my = getY() + (int) ((1f - brightness) * WHEEL_D);
		g.fill(bx - 1, my - 1, bx + BAR_W + 1, my + 1, 0x88FFFFFF);
		int swY = getY() + WHEEL_D + 4;
		int hexW = 64;
		g.fill(getX(), swY, getX() + getWidth() - hexW, swY + SWATCH_H,
			0xFF000000 | hsvToRgb(hue, sat, brightness));
		g.fill(getX() + getWidth() - hexW, swY, getX() + getWidth(), swY + SWATCH_H, 0xFF101010);
		g.centeredText(font, String.format(java.util.Locale.ROOT, "%06X", currentRgb() & 0xFFFFFF),
			getX() + getWidth() - hexW / 2, swY + 2, 0xFFFFFFFF);
		int hy = getY() + WHEEL_D + 4 + SWATCH_H + 4;
		for (int i = 0; i < 5; i++) {
			int hx = getX() + i * (HIST_W + 2);
			int c = i < history.size() ? history.get(i) : 0xFF222222;
			g.fill(hx, hy, hx + HIST_W, hy + HIST_H, c | 0xFF000000);
		}
		// Cursor remembers the picked spot, dark ring outside and light inside
		double a = hue * Math.PI * 2.0;
		int px = (int) (cx + Math.cos(a) * sat * r);
		int py = (int) (cy + Math.sin(a) * sat * r);
		ring(g, px, py, 4, 0xFF000000);
		ring(g, px, py, 3, 0xFFFFFFFF);
	}

	private static void ring(GuiGraphicsExtractor g, int cx, int cy, int o, int color) {
		g.fill(cx - o, cy - o - 1, cx + o + 1, cy - o, color);
		g.fill(cx - o, cy + o, cx + o + 1, cy + o + 1, color);
		g.fill(cx - o - 1, cy - o, cx - o, cy + o + 1, color);
		g.fill(cx + o, cy - o, cx + o + 1, cy + o + 1, color);
	}

	private int historyAt(double mx, double my) {
		int hy = getY() + WHEEL_D + 4 + SWATCH_H + 4;
		if (my < hy || my >= hy + HIST_H) {
			return -1;
		}
		int idx = (int) ((mx - getX()) / (HIST_W + 2));
		return idx >= 0 && idx < history.size() ? idx : -1;
	}

	private void pickAt(double mx, double my) {
		int hi = historyAt(mx, my);
		if (hi >= 0) {
			applyColor(history.get(hi));
			onPick.accept(currentRgb());
			return;
		}
		double dx = mx - cx();
		double dy = my - cy();
		double rr = Math.sqrt(dx * dx + dy * dy);
		if (rr <= WHEEL_D / 2.0) {
			float h = (float) (Math.atan2(dy, dx) / (Math.PI * 2.0));
			if (h < 0f) {
				h += 1f;
			}
			hue = h;
			sat = (float) Math.min(1.0, rr / (WHEEL_D / 2.0));
			onPick.accept(0xFF000000 | hsvToRgb(hue, sat, brightness));
		} else if (mx >= barX() && mx <= barX() + BAR_W && my >= getY() && my <= getY() + WHEEL_D) {
			brightness = clamp01(1f - (float) ((my - getY()) / WHEEL_D));
			onPick.accept(0xFF000000 | hsvToRgb(hue, sat, brightness));
		}
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean dbl) {
		pickAt(event.x(), event.y());
	}

	@Override
	protected void onDrag(MouseButtonEvent event, double dx, double dy) {
		pickAt(event.x(), event.y());
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, Component.literal("HEX"));
	}

	private static float clamp01(float v) {
		return v < 0f ? 0f : (v > 1f ? 1f : v);
	}

	static int hsvToRgb(float h, float s, float v) {
		h = h - (float) Math.floor(h);
		float c = v * s;
		float x = c * (1f - Math.abs((h * 6f) % 2f - 1f));
		float m = v - c;
		float r;
		float g;
		float b;
		int sector = (int) (h * 6f);
		switch (sector) {
			case 0 -> {
				r = c;
				g = x;
				b = 0f;
			}
			case 1 -> {
				r = x;
				g = c;
				b = 0f;
			}
			case 2 -> {
				r = 0f;
				g = c;
				b = x;
			}
			case 3 -> {
				r = 0f;
				g = x;
				b = c;
			}
			case 4 -> {
				r = x;
				g = 0f;
				b = c;
			}
			default -> {
				r = c;
				g = 0f;
				b = x;
			}
		}
		return (Math.round((r + m) * 255f) << 16) | (Math.round((g + m) * 255f) << 8) | Math.round((b + m) * 255f);
	}

	static float[] rgbToHsv(int argb) {
		float r = ((argb >> 16) & 0xFF) / 255f;
		float g = ((argb >> 8) & 0xFF) / 255f;
		float b = (argb & 0xFF) / 255f;
		float mx = Math.max(r, Math.max(g, b));
		float mn = Math.min(r, Math.min(g, b));
		float d = mx - mn;
		float h = 0f;
		if (d > 0f) {
			if (mx == r) {
				h = ((g - b) / d) % 6f;
			} else if (mx == g) {
				h = (b - r) / d + 2f;
			} else {
				h = (r - g) / d + 4f;
			}
			h /= 6f;
			if (h < 0f) {
				h += 1f;
			}
		}
		return new float[]{h, mx == 0f ? 0f : d / mx, mx};
	}
}
