package com.liquidum.client.settings;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;

// Disclosure row opening another screen, label left, value plus chevron right
public class MenuRow extends SettingRow {
	private final Runnable action;
	private final java.util.function.Supplier<String> value;

	public MenuRow(int x, int y, int w, String name, java.util.function.Supplier<String> value, Runnable action) {
		super(x, y, w, 28, net.minecraft.network.chat.Component.literal(name));
		this.value = value;
		this.action = action;
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		if (!active) return;
		clickSound();
		if (action != null) {
			try {
				action.run();
			} catch (Exception ignored) {
			}
		}
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		int c = !active ? 0xFF777777 : isHoveredOrFocused() ? 0xFFFFFFFF : 0xFFE8E8E8;
		g.text(font(), getMessage(), getX() + 10, getY() + 9, c, false);
		String v = value == null ? ">" : value.get() + " >";
		int vvx = getX() + getWidth() - 10 - font().width(v);
		g.text(font(), v, vvx, getY() + 9, active ? 0xFFAAAAAA : 0xFF666666, false);
	}
}
