package com.liquidum.client.settings;

import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.Optional;

// Fullscreen resolution picker, dropdown with the vanilla cycle entries
public class ResolutionRow extends SettingRow implements PopupAnchor {
	public PopupRow.Opener opener;
	private int vrx;
	private int vry;
	private int vrw;
	private int vrh;
	public ResolutionRow(int x, int y, int w) {
		super(x, y, w, 28, Component.translatable("options.fullscreen.resolution"));
	}

	private static Window window() {
		return Minecraft.getInstance().getWindow();
	}

	private static Monitor monitor() {
		try {
			return window().findBestMonitor();
		} catch (Exception e) {
			return null;
		}
	}

	private static int currentIndex() {
		try {
			Monitor m = monitor();
			if (m == null) return -2;
			Optional<VideoMode> pref = window().getPreferredFullscreenVideoMode();
			return pref.map(m::indexOfMode).orElse(-1);
		} catch (Exception e) {
			return -2;
		}
	}

	private Component labelFor(int idx) {
		Monitor m = monitor();
		if (m == null) return Component.translatable("options.fullscreen.unavailable");
		if (idx < 0) return Component.translatable("options.fullscreen.current");
		VideoMode mode = m.mode(idx);
		return Component.translatable("options.fullscreen.entry",
			mode.getWidth(), mode.getHeight(), mode.getRefreshRate(),
			mode.getRedBits() + mode.getGreenBits() + mode.getBlueBits());
	}

	private Component valueText() {
		return labelFor(currentIndex());
	}

	private void applyModeIndex(int i) {
		if (!active) return;
		try {
			Monitor m = monitor();
			if (m == null) return;
			int idx = i - 1;
			if (idx < -1 || idx >= m.modeCount()) return;
			window().setPreferredFullscreenVideoMode(idx < 0 ? Optional.empty() : Optional.of(m.mode(idx)));
			clickSound();
		} catch (Exception ignored) {
		}
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		if (!active || opener == null) return;
		opener.open(this);
	}

	@Override
	public java.util.List<String> popupLabels() {
		java.util.List<String> labels = new java.util.ArrayList<>();
		try {
			Monitor m = monitor();
			if (m == null) return labels;
			for (int idx = -1; idx < m.modeCount(); idx++) labels.add(labelFor(idx).getString());
		} catch (Exception ignored) {
		}
		return labels;
	}

	@Override
	public int popupSelected() {
		return currentIndex() + 1;
	}

	@Override
	public void popupPick(int idx) {
		applyModeIndex(idx);
	}

	@Override
	public int[] popupValueRect() {
		return new int[]{vrx, vry, vrw, vrh};
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		String v = valueText().getString();
		int vx = getX() + getWidth() - 24 - font().width(v);
		vrx = vx;
		vry = getY() + 9;
		vrw = font().width(v);
		vrh = 9;
		int c = !active ? 0xFF777777 : isHoveredOrFocused() ? 0xFFFFFFFF : 0xFFE8E8E8;
		g.text(font(), getMessage(), getX() + 10, getY() + 9, c, false);
		g.text(font(), v, vrx, getY() + 9, active ? 0xFFAAAAAA : 0xFF666666, false);
		int tx0 = getX() + getWidth() - 16;
		int cy = getY() + 14;
		int tri = active ? 0xFFAAAAAA : 0xFF666666;
		for (int i = 0; i < 5; i++) {
			g.fill(net.minecraft.client.renderer.RenderPipelines.GUI, tx0 + i, cy - 2 + i, tx0 + 8 - i, cy - 1 + i, tri);
		}
	}
}
