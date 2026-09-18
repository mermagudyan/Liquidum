package com.liquidum.client.settings;

import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;

// Boolean option as an iOS-style switch, dark recess off, lit track on
public class ToggleRow extends SettingRow {
	private final OptionInstance<Boolean> option;

	public ToggleRow(int x, int y, int w, OptionInstance<Boolean> option) {
		super(x, y, w, 28, captionOf(option));
		this.option = option;
		refreshTooltip(this, option);
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		if (!active) return;
		option.set(!option.get());
		refreshTooltip(this, option);
		clickSound();
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		boolean on = option.get();
		g.text(font(), getMessage(), getX() + 10, getY() + 9, 0xFFFFFFFF, false);
		int tw = 34, th = 20, tx = getX() + getWidth() - tw - 10, ty = getY() + 4;
		int track = !active ? 0xFF1E1E1E : on ? 0xFF5EB0E5 : 0xFF2E2E2E;
		if (isHoveredOrFocused() && active) track = on ? 0xFF74BDEA : 0xFF3A3A3A;
		g.fill(RenderPipelines.GUI, tx, ty, tx + tw, ty + th, track);
		int kw = 14, kx = on ? tx + tw - kw - 3 : tx + 3;
		g.fill(RenderPipelines.GUI, kx, ty + 3, kx + kw, ty + th - 3, active ? 0xFFFFFFFF : 0xFF777777);
	}
}
