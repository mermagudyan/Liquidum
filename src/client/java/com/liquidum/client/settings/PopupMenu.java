package com.liquidum.client.settings;

import com.liquidum.client.shader.LiquidGlassRenderer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

// iPhone dropdown: matte glass sheet above all, rows behind fade out
public class PopupMenu {
	private static final int ITEM_H = 22;
	private static final int PAD_X = 16;
	private static final int PAD_Y = 8;
	private static final float POPUP_ELEV = 4f;
	private static final float POPUP_GROUP = 1000f;

	private boolean open;
	private float anim;
	private long nanos;
	private int ax;
	private int ay;
	private int aw;
	private int ah;
	private int tx;
	private int ty;
	private int tw;
	private int th;
	private int cx;
	private int cy;
	private int cw;
	private int ch;
	private List<String> items = List.of();
	private int selected;
	private IntConsumer onPick;

	public void open(SettingRow anchor, int vcx, int vcy, int x, int rowTop, int rowBottom, int w, List<String> items, int selected, IntConsumer onPick, int viewTop, int viewBottom) {
		if (items.isEmpty()) return;
		int h = items.size() * ITEM_H + PAD_Y * 2;
		int below = rowBottom + 2;
		int above = rowTop - 2 - h;
		int yy;
		if (below + h <= viewBottom) yy = below;
		else if (above >= viewTop) yy = above;
		else yy = Math.max(viewTop, viewBottom - h);
		this.ax = vcx - 8;
		this.ay = vcy - 8;
		this.aw = 16;
		this.ah = 16;
		this.tx = x;
		this.ty = yy;
		this.tw = w;
		this.th = h;
		this.items = new ArrayList<>(items);
		this.selected = Math.max(0, Math.min(selected, items.size() - 1));
		this.onPick = onPick;
		this.open = true;
		this.anim = 0f;
	}

	public boolean isOpen() {
		return open;
	}

	public void close() {
		open = false;
	}

	public void update() {
		long now = System.nanoTime();
		if (nanos == 0L) nanos = now;
		float dt = Math.min((now - nanos) / 1e9f, 0.1f);
		nanos = now;
		float want = open ? 1f : 0f;
		float rate = open ? 14.0f : 24.0f;
		anim += (want - anim) * (1f - (float) Math.exp(-rate * dt));
		if (Math.abs(want - anim) < 0.005f) {
			anim = want;
			if (!open) {
				items = List.of();
				onPick = null;
			}
		}
		float e = 1f - (1f - anim) * (1f - anim) * (1f - anim);
		cx = Math.round(ax + (tx - ax) * e);
		cy = Math.round(ay + (ty - ay) * e);
		cw = Math.max(8, Math.round(aw + (tw - aw) * e));
		ch = Math.max(8, Math.round(ah + (th - ah) * e));
	}

	public boolean visible() {
		return anim > 0.02f;
	}

	public void draw(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
		if (anim <= 0.02f || items.isEmpty()) return;
		LiquidGlassRenderer.submitSpriteTile(cx, cy, cw, ch, LiquidGlassRenderer.MAT_POPUP, POPUP_ELEV, POPUP_GROUP, 0.999f);
		int a = SettingRow.withAlpha(0xFFFFFFFF, anim);
		boolean replay = LiquidGlassRenderer.deferPopupReplay();
		org.joml.Matrix3x2f pose = replay ? new org.joml.Matrix3x2f(g.pose()) : null;
		for (int i = 0; i < items.size(); i++) {
			int iy = cy + PAD_Y + i * ITEM_H;
			String label = items.get(i);
			int color = SettingRow.withAlpha(0xFFFFFFFF, anim);
			if (replay) {
				// Crisp vector replay after glass, widget copy cancelled below
				LiquidGlassRenderer.stashPopupText(font, label, cx + 26, iy + 6, color, false, pose);
				if (i == selected) LiquidGlassRenderer.stashPopupCheck(RenderPipelines.GUI, cx + 13, iy + 7, a, pose);
				continue;
			}
			if (i == selected) {
				int qx = cx + 13;
				int qy = iy + 7;
				g.fill(RenderPipelines.GUI, qx, qy + 3, qx + 3, qy + 5, a);
				g.fill(RenderPipelines.GUI, qx + 3, qy + 1, qx + 9, qy + 3, a);
			}
			g.text(font, label, cx + 26, iy + 6, color, false);
		}
	}

	// True when consumed, pick on item hit, close on outside hit
	public boolean click(double mouseX, double mouseY) {
		if (!open) return false;
		if (mouseX >= tx && mouseX < tx + tw && mouseY >= ty && mouseY < ty + th) {
			int idx = (int) ((mouseY - ty - PAD_Y) / ITEM_H);
			IntConsumer cb = onPick;
			close();
			if (idx >= 0 && idx < items.size() && cb != null) cb.accept(idx);
			return true;
		}
		close();
		return true;
	}
}
