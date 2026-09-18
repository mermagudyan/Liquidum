package com.liquidum.client.settings;

import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.List;

// Enum option as a dropdown row, label left, value plus pixel chevron right
public class PopupRow<T> extends SettingRow implements PopupAnchor {
	public interface Opener {
		void open(PopupAnchor anchor);
	}

	private final OptionInstance<T> option;
	private final List<T> values;
	public Opener opener;
	private int vrx;
	private int vry;
	private int vrw;
	private int vrh;

	public PopupRow(int x, int y, int w, OptionInstance<T> option, List<T> values) {
		super(x, y, w, 28, captionOf(option));
		this.option = option;
		this.values = values;
		refreshTooltip(this, option);
	}

	public OptionInstance<?> option() {
		return option;
	}

	public List<?> values() {
		return values;
	}

	public void applyIndex(int i) {
		if (!active || values.isEmpty()) return;
		option.set(values.get(Math.max(0, Math.min(i, values.size() - 1))));
		refreshTooltip(this, option);
		clickSound();
	}

	public static int fitWidth(int rowW, java.util.List<String> labels) {
		int maxTw = 0;
		for (String s : labels) maxTw = Math.max(maxTw, font().width(s));
		return Math.max(110, Math.min(rowW - 16, maxTw + 42));
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		if (!active || values.isEmpty() || opener == null) return;
		opener.open(this);
	}

	@Override
	public java.util.List<String> popupLabels() {
		java.util.List<String> labels = new java.util.ArrayList<>();
		for (T v : values) labels.add(valueTextFor(option, v).getString());
		return labels;
	}

	@Override
	public int popupSelected() {
		return values.indexOf(option.get());
	}

	@Override
	public void popupPick(int idx) {
		applyIndex(idx);
	}

	@Override
	public int[] popupValueRect() {
		return new int[]{vrx, vry, vrw, vrh};
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		String v = valueTextOf(option).getString();
		int vx = getX() + getWidth() - 24 - font().width(v);
		vrx = vx;
		vry = getY() + 9;
		vrw = font().width(v);
		vrh = 9;
		int c = !active ? 0xFF777777 : isHoveredOrFocused() ? 0xFFFFFFFF : 0xFFE8E8E8;
		g.text(font(), getMessage(), getX() + 10, getY() + 9, c, false);
		g.text(font(), v, vx, getY() + 9, active ? 0xFFAAAAAA : 0xFF666666, false);
		int tx0 = getX() + getWidth() - 16;
		int cy = getY() + 14;
		int tri = active ? 0xFFAAAAAA : 0xFF666666;
		for (int i = 0; i < 5; i++) {
			g.fill(RenderPipelines.GUI, tx0 + i, cy - 2 + i, tx0 + 8 - i, cy - 1 + i, tri);
		}
	}
}
