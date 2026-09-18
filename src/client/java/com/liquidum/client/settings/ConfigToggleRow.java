package com.liquidum.client.settings;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

// Plain boolean row for mod config, same switch visuals as ToggleRow
public class ConfigToggleRow extends SettingRow {
	private final BooleanSupplier get;
	private final Consumer<Boolean> set;
	private final Runnable after;

	public ConfigToggleRow(int x, int y, int w, String name, BooleanSupplier get, Consumer<Boolean> set, Runnable after) {
		super(x, y, w, 28, net.minecraft.network.chat.Component.literal(name));
		this.get = get;
		this.set = set;
		this.after = after;
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		if (!active) return;
		set.accept(!get.getAsBoolean());
		if (after != null) {
			try {
				after.run();
			} catch (Exception ignored) {
			}
		}
		clickSound();
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		boolean on = get.getAsBoolean();
		g.text(font(), getMessage(), getX() + 10, getY() + 9, 0xFFFFFFFF, false);
		int tw = 34, th = 20, tx = getX() + getWidth() - tw - 10, ty = getY() + 4;
		int track = !active ? 0xFF1E1E1E : on ? 0xFF5EB0E5 : 0xFF2E2E2E;
		if (isHoveredOrFocused() && active) track = on ? 0xFF74BDEA : 0xFF3A3A3A;
		g.fill(RenderPipelines.GUI, tx, ty, tx + tw, ty + th, track);
		int kw = 14, kx = on ? tx + tw - kw - 3 : tx + 3;
		g.fill(RenderPipelines.GUI, kx, ty + 3, kx + kw, ty + th - 3, active ? 0xFFFFFFFF : 0xFF777777);
	}
}
