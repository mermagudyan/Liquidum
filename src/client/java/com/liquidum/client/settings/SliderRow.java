package com.liquidum.client.settings;

import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;

// Numeric option as label plus slim track below, drag anywhere in the row
public class SliderRow<T> extends SettingRow {
	private final OptionInstance<T> option;
	private final boolean ranged;

	public SliderRow(int x, int y, int w, OptionInstance<T> option) {
		super(x, y, w, 46, captionOf(option));
		this.option = option;
		this.ranged = option.values() instanceof OptionInstance.IntRange;
		refreshTooltip(this, option);
	}

	private double fraction() {
		try {
			if (ranged) {
				OptionInstance.IntRange r = (OptionInstance.IntRange) option.values();
				int v = (Integer) option.get();
				if (r.maxInclusive() <= r.minInclusive()) return 0.0;
				return (double) (v - r.minInclusive()) / (r.maxInclusive() - r.minInclusive());
			}
			OptionInstance.SliderableValueSet<T> s = (OptionInstance.SliderableValueSet<T>) option.values();
			return s.toSliderValue(option.get());
		} catch (Exception e) {
			return 0.0;
		}
	}

	private void applyFraction(double f) {
		f = Math.max(0.0, Math.min(1.0, f));
		try {
			if (ranged) {
				OptionInstance.IntRange r = (OptionInstance.IntRange) option.values();
				@SuppressWarnings("unchecked")
				OptionInstance<Integer> i = (OptionInstance<Integer>) (OptionInstance<?>) option;
				i.set(r.minInclusive() + (int) Math.round(f * (r.maxInclusive() - r.minInclusive())));
			} else {
				@SuppressWarnings("unchecked")
				OptionInstance.SliderableValueSet<T> s = (OptionInstance.SliderableValueSet<T>) option.values();
				option.set(s.fromSliderValue(f));
			}
			refreshTooltip(this, option);
		} catch (Exception ignored) {
		}
	}

	private double fractionAt(double mouseX) {
		return (mouseX - getX()) / (double) getWidth();
	}

	// Hitbox is the white knob itself, not the whole row
	private int[] knobRect() {
		int tx0 = getX() + 10, tx1 = getX() + getWidth() - 10, ty = getY() + getHeight() - 14;
		double f = Math.max(0.0, Math.min(1.0, fraction()));
		int fx = tx0 + (int) Math.round(f * (tx1 - tx0));
		int kx = Math.max(tx0, Math.min(fx - 5, tx1 - 10));
		return new int[]{ kx, ty - 6, 10, 16 };
	}

	@Override
	public boolean isMouseOver(double mouseX, double mouseY) {
		if (!active) return false;
		int[] k = knobRect();
		return mouseX >= k[0] && mouseX < k[0] + k[2] && mouseY >= k[1] && mouseY < k[1] + k[3];
	}

	// Track band click moves the knob there, then drag continues
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) return true;
		if (!active || !isValidClickButton(event.buttonInfo())) return false;
		if (trackHit(event.x(), event.y())) {
			applyFraction(fractionAt(event.x()));
			clickSound();
			return true;
		}
		return false;
	}

	// Band test for dispatcher routing, height matches the knob
	public boolean trackHit(double mx, double my) {
		if (!active) return false;
		int tx0 = getX() + 10, tx1 = getX() + getWidth() - 10, ty = getY() + getHeight() - 14;
		return mx >= tx0 && mx <= tx1 && my >= ty - 6 && my <= ty + 10;
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		if (!active) return;
		applyFraction(fractionAt(event.x()));
	}

	@Override
	protected void onDrag(MouseButtonEvent event, double dx, double dy) {
		if (!active) return;
		applyFraction(fractionAt(event.x()));
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		int label = !active ? 0xFF777777 : 0xFFFFFFFF;
		g.text(font(), getMessage(), getX() + 10, getY() + 5, label, false);
		String v = valueTextOf(option).getString();
		int vvx = getX() + getWidth() - 10 - font().width(v);
		g.text(font(), v, vvx, getY() + 5, active ? 0xFFAAAAAA : 0xFF666666, false);
		double f = Math.max(0.0, Math.min(1.0, fraction()));
		int tx0 = getX() + 10, tx1 = getX() + getWidth() - 10, ty = getY() + getHeight() - 14;
		trackSpan(g, tx0, tx1, ty, active ? 0xFF2E2E2E : 0xFF1E1E1E);
		int fx = tx0 + (int) Math.round(f * (tx1 - tx0));
		if (fx > tx0) trackSpan(g, tx0, fx, ty, active ? 0xFF5EB0E5 : 0xFF3A3A3A);
		int[] k = knobRect();
		g.fill(RenderPipelines.GUI, k[0], k[1], k[0] + k[2], k[1] + k[3], active ? 0xFFFFFFFF : 0xFF777777);
	}

	// Slim track under the value, full brightness everywhere
	private void trackSpan(GuiGraphicsExtractor g, int x0, int x1, int y, int color) {
		if (x1 <= x0) return;
		g.fill(RenderPipelines.GUI, x0, y, x1, y + 4, color);
	}
}
