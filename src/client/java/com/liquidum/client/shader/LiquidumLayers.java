package com.liquidum.client.shader;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

public final class LiquidumLayers {
	public static final int HOTBAR = 1;
	public static final int INVENTORY = 2;
	public static final int ITEMS = 3;
	public static final int TEXT_DELTA = 1;
	public static final int CARRIED = 5;
	public static final int TOOLTIP = 6;
	public static final int PAUSE = 5;
	public static final int MENU_FUSE_BASE = 10;

	private LiquidumLayers() {
	}

	private static void push(GuiGraphicsExtractor g) {
		if (g == null) return;
		if (!LiquidGlassRenderer.isEnabled()) return;
		try {
			g.nextStratum();
		} catch (Exception ignored) {
		}
	}

	public static void beginHotbarBase(GuiGraphicsExtractor g) {
		push(g);
	}

	public static void beginInventoryObjects(GuiGraphicsExtractor g) {
		push(g);
	}

	public static void beginItems(GuiGraphicsExtractor g) {
		push(g);
	}

	public static void beginText(GuiGraphicsExtractor g) {
		push(g);
	}

	public static void beginCarried(GuiGraphicsExtractor g) {
		push(g);
	}

	public static void beginTooltip(GuiGraphicsExtractor g) {
		push(g);
	}

	public static void beginPauseBase(GuiGraphicsExtractor g) {
		push(g);
	}

	public static int textAbove(int base) {
		return base + TEXT_DELTA;
	}

	public static boolean isInventoryObjects(Screen s) {
		return s instanceof AbstractContainerScreen;
	}
}
