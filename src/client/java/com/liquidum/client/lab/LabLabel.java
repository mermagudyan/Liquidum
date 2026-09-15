package com.liquidum.client.lab;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

// Static heading line, renders centered text and never consumes clicks
public class LabLabel extends AbstractWidget {
	private final String text;
	private final net.minecraft.client.gui.Font font;
	private final int color;

	public LabLabel(int x, int y, int w, String text, net.minecraft.client.gui.Font font, int color) {
		super(x, y, w, 20, Component.literal(text));
		this.text = text;
		this.font = font;
		this.color = color;
		this.active = false;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean dbl) {
		return false;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		g.centeredText(font, text, getX() + getWidth() / 2, getY() + 6, color);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
	}
}
