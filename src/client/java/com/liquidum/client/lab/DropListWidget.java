package com.liquidum.client.lab;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

// Closed button with a popup list, no textures, options render below the box
public class DropListWidget extends AbstractWidget {
	private static final int ROW_H = 20;

	private final String title;
	private final List<String> options;
	private int current;
	private boolean open;
	private float popupScroll;
	private boolean popupDrag;
	private float popupGrab;
	private final Consumer<Integer> onPick;
	private final Consumer<DropListWidget> onOpen;
	private final net.minecraft.client.gui.Font font;

	public DropListWidget(int x, int y, int w, String title, List<String> options, int current,
		net.minecraft.client.gui.Font font, Consumer<Integer> onPick, Consumer<DropListWidget> onOpen) {
		super(x, y, w, ROW_H, Component.literal(title));
		this.title = title;
		this.options = options;
		this.current = current;
		this.font = font;
		this.onPick = onPick;
		this.onOpen = onOpen;
	}

	public boolean isOpen() {
		return open;
	}

	public void close() {
		open = false;
	}

	// Visible popup window for the given space below the box
	public int popupH(int maxH) {
		return Math.min(options.size() * ROW_H, Math.max(40, maxH));
	}

	public void scrollPopup(float dy, int maxH) {
		int ph = popupH(maxH);
		float max = Math.max(0, options.size() * ROW_H - ph);
		popupScroll = Math.max(0f, Math.min(popupScroll - dy * 20f, max));
	}

	public boolean popupHit(float mx, float my, int maxH) {
		return mx >= getX() && mx <= getX() + getWidth()
			&& my >= getY() + ROW_H && my < getY() + ROW_H + popupH(maxH);
	}

	private int popupThumbTop(int maxH) {
		int ph = popupH(maxH);
		int full = options.size() * ROW_H;
		if (full <= ph) {
			return -1;
		}
		int th = Math.max(12, ph * ph / full);
		return getY() + ROW_H + (int) ((ph - th) * (popupScroll / (float) (full - ph)));
	}

	private int popupThumbH(int maxH) {
		int ph = popupH(maxH);
		int full = options.size() * ROW_H;
		if (full <= ph) {
			return 0;
		}
		return Math.max(12, ph * ph / full);
	}

	// Mini scrollbar has click priority so a pick never fires by accident
	public boolean thumbHit(float mx, float my, int maxH) {
		int ty = popupThumbTop(maxH);
		if (ty < 0) {
			return false;
		}
		return mx >= getX() + getWidth() - 3 && mx <= getX() + getWidth()
			&& my >= ty && my < ty + popupThumbH(maxH);
	}

	public void beginPopupDrag(float my, int maxH) {
		popupDrag = true;
		popupGrab = my - popupThumbTop(maxH);
	}

	public void dragPopupTo(float my, int maxH) {
		int ph = popupH(maxH);
		int full = options.size() * ROW_H;
		if (full <= ph) {
			return;
		}
		int th = Math.max(12, ph * ph / full);
		float max = full - ph;
		popupScroll = Math.max(0f, Math.min((my - popupGrab - (getY() + ROW_H)) / (ph - th) * max, max));
	}

	public boolean isPopupDrag() {
		return popupDrag;
	}

	public void endPopupDrag() {
		popupDrag = false;
	}

	// Popup hit test in screen space, option index or -1
	public int optionAt(float mx, float my, int maxH) {
		if (mx < getX() || mx > getX() + getWidth()) {
			return -1;
		}
		int rel = (int) (my - getY()) - ROW_H + (int) popupScroll;
		if (rel < 0 || my >= getY() + ROW_H + popupH(maxH)) {
			return -1;
		}
		int idx = rel / ROW_H;
		return idx >= 0 && idx < options.size() ? idx : -1;
	}

	// Returns true when an option was picked
	public boolean handlePopupClick(float mx, float my, int maxH) {
		open = false;
		int idx = optionAt(mx, my, maxH);
		if (idx < 0) {
			return false;
		}
		current = idx;
		onPick.accept(idx);
		return true;
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean dbl) {
		open = !open;
		if (open) {
			onOpen.accept(this);
		}
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		int x = getX();
		int y = getY();
		int w = getWidth();
		g.fill(x, y, x + w, y + ROW_H, 0xFF2A2A2A);
		g.fill(x, y + ROW_H - 1, x + w, y + ROW_H, 0xFF555555);
		String label = title + ": " + options.get(Math.max(0, Math.min(current, options.size() - 1))) + (open ? " ▴" : " ▾");
		g.centeredText(font, label, x + w / 2, y + 6, 0xFFFFFFFF);
	}

	// Popup renders dead last from the screen so it sits above widgets and text
	public void drawPopup(GuiGraphicsExtractor g, int mouseX, int mouseY, int maxH, float alpha) {
		if (!open) {
			return;
		}
		int x = getX();
		int wy0 = getY() + ROW_H;
		int ph = popupH(maxH);
		int wy1 = wy0 + ph;
		g.enableScissor(x, wy0, x + getWidth(), wy1);
		for (int i = 0; i < options.size(); i++) {
			int ry = wy0 + i * ROW_H - (int) popupScroll;
			if (ry + ROW_H <= wy0 || ry >= wy1) {
				continue;
			}
			boolean hover = mouseX >= x && mouseX <= x + getWidth() && mouseY >= ry && mouseY < ry + ROW_H;
			g.fill(x, ry, x + getWidth(), ry + ROW_H, i == current ? 0xFF3D4A2A : hover ? 0xFF3A3A3A : 0xFF1E1E1E);
			g.centeredText(font, options.get(i), x + getWidth() / 2, ry + 6, 0xFFFFFFFF);
		}
		g.disableScissor();
		int ty = popupThumbTop(maxH);
		int th = popupThumbH(maxH);
		if (ty >= 0 && alpha > 0f) {
			int a = Math.round(0xAA * alpha);
			g.fill(x + getWidth() - 3, ty, x + getWidth(), ty + th, (a << 24) | 0xFFFFFF);
		}
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, Component.literal(title));
	}
}
