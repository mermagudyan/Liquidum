package com.liquidum.client.debug;

import com.liquidum.client.LiquidumCore;
import com.liquidum.client.shader.LiquidGlassRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Liquidum Lab (F7): isolated subsystem testing + live material tuning.
 * Every processing stage and shader material param can be toggled/observed
 * independently; "Dump to log" prints the full state for the log PASS/FAIL workflow.
 */
public class LiquidumDebugScreen extends Screen {
	private static final int CONTENT_W = 280;
	private static final int ROW_H = 24;
	private static final int HEADER_H = 26;
	private static final int FOOTER_H = 32;
	private static final int SCROLLBAR_W = 6;
	private static final int SCROLLBAR_MIN_H = 32;
	private static final Identifier SCROLLER_SPRITE = Identifier.withDefaultNamespace("widget/scroller");
	private static final Identifier SCROLLER_BG_SPRITE = Identifier.withDefaultNamespace("widget/scroller_background");
	private int scrollOffset = 0;
	private int contentHeight = 0;
	private int visibleHeight = 0;
	private int contentX;
	private final List<Row> rows = new ArrayList<>();

	private static class Row { final List<net.minecraft.client.gui.components.AbstractWidget> widgets = new ArrayList<>(); int y; }

	public LiquidumDebugScreen() {
		super(Component.literal("Liquidum Lab"));
	}

	@Override
	protected void init() {
		rows.clear();
		scrollOffset = 0;
		contentX = this.width / 2 - CONTENT_W / 2;
		int w = CONTENT_W;
		int x = contentX;
		int y = HEADER_H - scrollOffset;

		// Vanilla/Liquidum toggle — как F3+T
		rows.add(row(Button.builder(Component.literal(LiquidumCore.getConfig().enabled ? "Vanilla UI (выкл. мод)" : "Liquidum UI (вкл. мод)"), b -> {
			var cfg = LiquidumCore.getConfig();
			cfg.enabled = !cfg.enabled;
			cfg.save();
			LiquidumCore.reloadConfig();
			b.setMessage(Component.literal(cfg.enabled ? "Vanilla UI (выкл. мод)" : "Liquidum UI (вкл. мод)"));
			Minecraft.getInstance().reloadResourcePacks();
		}).bounds(x, y, w, 20).build())); y += ROW_H;
		rows.add(row(Button.builder(Component.literal("New Lab (F7)"), b -> {
			Minecraft.getInstance().gui.setScreen(new com.liquidum.client.lab.LiquidumLabScreen(null));
		}).bounds(x, y, w, 20).build())); y += ROW_H;
		rows.add(row(Button.builder(labelMode(), b -> {
			LiquidumDebugState.cycleMode();
			b.setMessage(labelMode());
		}).bounds(x, y, w, 20).build())); y += ROW_H;

		addToggleRow(x, y, w, "Hover", () -> LiquidumDebugState.hover, v -> LiquidumDebugState.hover = v); y += ROW_H;
		addToggleRow(x, y, w, "Aberration", () -> LiquidumDebugState.aberration, v -> LiquidumDebugState.aberration = v); y += ROW_H;
		addToggleRow(x, y, w, "Rim", () -> LiquidumDebugState.rim, v -> LiquidumDebugState.rim = v); y += ROW_H;
		addToggleRow(x, y, w, "Frost", () -> LiquidumDebugState.frost, v -> LiquidumDebugState.frost = v); y += ROW_H;
		addToggleRow(x, y, w, "Fuse", () -> LiquidumDebugState.fusion, v -> LiquidumDebugState.fusion = v); y += ROW_H;
		addToggleRow(x, y, w, "AnimOpen", () -> LiquidumDebugState.animOpen, v -> LiquidumDebugState.animOpen = v); y += ROW_H;
		addToggleRow(x, y, w, "CrashOnError", () -> LiquidumDebugState.crashOnError, v -> LiquidumDebugState.crashOnError = v); y += ROW_H;
		addToggleRow(x, y, w, "GeometryDebug", () -> LiquidumDebugState.debugGeometry, v -> LiquidumDebugState.debugGeometry = v); y += ROW_H;
		addToggleRow(x, y, w, "Probe", () -> LiquidumDebugState.probeShow, v -> {
			LiquidumDebugState.probeShow = v;
			if (v) {
				LiquidumDebugState.probeX = width / 2f;
				LiquidumDebugState.probeY = height / 2f;
			}
		}); y += ROW_H;
		addNumericRow(x, y, w, "ProbeSize", () -> LiquidumDebugState.probeDiameter, v -> LiquidumDebugState.probeDiameter = v, 32f, 400f, 8f, 120f); y += ROW_H;

		addNumericRow(x, y, w, "CornerRadius", () -> LiquidumDebugState.cornerRadiusFraction, v -> LiquidumDebugState.cornerRadiusFraction = v, 0f, 1f, 0.05f, 0.28f); y += ROW_H;
		addNumericRow(x, y, w, "Refraction", () -> LiquidumDebugState.refraction, v -> LiquidumDebugState.refraction = v, 0f, 200f, 5f, 20f); y += ROW_H;
		addNumericRow(x, y, w, "Fresnel", () -> LiquidumDebugState.fresnel, v -> LiquidumDebugState.fresnel = v, 0f, 5f, 0.1f, 0.65f); y += ROW_H;
		addNumericRow(x, y, w, "SharpnessMix", () -> LiquidumDebugState.sharpnessMix, v -> LiquidumDebugState.sharpnessMix = v, 0f, 1f, 0.05f, 0.14f); y += ROW_H;
		addNumericRow(x, y, w, "FrostRadius(blur)", () -> LiquidumDebugState.frostRadius, v -> LiquidumDebugState.frostRadius = v, 0f, 30f, 1f, 7f); y += ROW_H;
		addNumericRow(x, y, w, "FuseRadius(px)", () -> LiquidumDebugState.fusionRadius, v -> LiquidumDebugState.fusionRadius = v, 0f, 60f, 2f, 12f); y += ROW_H;
		addNumericRow(x, y, w, "SunSpec(off)", () -> LiquidumDebugState.sunSpec, v -> LiquidumDebugState.sunSpec = v, 0f, 2f, 0.1f, 0.0f); y += ROW_H;
		addNumericRow(x, y, w, "AnimMs", () -> LiquidumDebugState.animMillis, v -> LiquidumDebugState.animMillis = v, 50f, 1000f, 10f, 220f); y += ROW_H;

		rows.add(row(Button.builder(Component.literal("Dump state to log"), b -> {
			LiquidumDebugState.dump();
			LiquidGlassRenderer.dumpDiagnostics();
		}).bounds(x, y, w, 20).build())); y += ROW_H;

		rows.add(row(Button.builder(Component.literal("Close"), b -> onClose())
			.bounds(x, y, w, 20).build())); y += ROW_H;

		contentHeight = y - HEADER_H + scrollOffset;
		visibleHeight = this.height - HEADER_H - FOOTER_H;
		clampScroll();
		repositionRows();

		LiquidumDebugState.dump();
		LiquidGlassRenderer.dumpDiagnostics();
	}
	private Row row(net.minecraft.client.gui.components.AbstractWidget w) { Row r = new Row(); r.widgets.add(w); addRenderableWidget(w); return r; }
	private void addToggleRow(int x, int y, int w, String name, BooleanSupplier get, Consumer<Boolean> set) {
		Button b = Button.builder(labelToggle(name, get.getAsBoolean()), bt -> {
			set.accept(!get.getAsBoolean());
			bt.setMessage(labelToggle(name, get.getAsBoolean()));
		}).bounds(x, y, w, 20).build();
		Row r = new Row(); r.widgets.add(b); r.y = y; rows.add(r); addRenderableWidget(b);
	}
	private void addNumericRow(int x, int y, int w, String name, Supplier<Float> get, Consumer<Float> set, float min, float max, float step, float def) {
		Button value = Button.builder(Component.literal(name + ": " + fmt(get.get())), b -> {
			set.accept(def);
			b.setMessage(Component.literal(name + ": " + fmt(def)));
		}).bounds(x + 22, y, w - 44, 20).build();
		Button minus = Button.builder(Component.literal("-"), b -> {
			set.accept(clamp(get.get() - step, min, max));
			value.setMessage(Component.literal(name + ": " + fmt(get.get())));
		}).bounds(x, y, 20, 20).build();
		Button plus = Button.builder(Component.literal("+"), b -> {
			set.accept(clamp(get.get() + step, min, max));
			value.setMessage(Component.literal(name + ": " + fmt(get.get())));
		}).bounds(x + w - 20, y, 20, 20).build();
		Row r = new Row(); r.widgets.add(minus); r.widgets.add(value); r.widgets.add(plus); r.y = y; rows.add(r);
		addRenderableWidget(minus); addRenderableWidget(value); addRenderableWidget(plus);
	}
	private void repositionRows() {
		int y = HEADER_H - scrollOffset;
		for (Row r : rows) {
			for (var w : r.widgets) { w.setY(y); w.visible = y + ROW_H >= HEADER_H && y < this.height - FOOTER_H; }
			r.y = y; y += ROW_H;
		}
	}
	private void clampScroll() { int max = Math.max(0, contentHeight - visibleHeight); scrollOffset = Math.max(0, Math.min(max, scrollOffset)); }
	private int scrollerH() { int h = visibleHeight * visibleHeight / Math.max(1, contentHeight); return Math.max(SCROLLBAR_MIN_H, Math.min(h, visibleHeight - 8)); }
	private int scrollBarX() { return contentX + CONTENT_W + 10; }
	private int scrollBarY() { int max = Math.max(0, contentHeight - visibleHeight); if (max==0) return HEADER_H; return HEADER_H + scrollOffset * (visibleHeight - scrollerH()) / max; }

	private void addToggle(int x, int y, int w, String name, BooleanSupplier get, Consumer<Boolean> set) {
		addRenderableWidget(Button.builder(labelToggle(name, get.getAsBoolean()), b -> {
			set.accept(!get.getAsBoolean());
			b.setMessage(labelToggle(name, get.getAsBoolean()));
		}).bounds(x, y, w, 20).build());
	}

	/** Row: [ - ]  Name: value  [ + ]  (click value to reset to default). */
	private void addNumeric(int x, int y, int w, String name, Supplier<Float> get, Consumer<Float> set,
	                        float min, float max, float step, float def) {
		Button value = Button.builder(Component.literal(name + ": " + fmt(get.get())), b -> {
			set.accept(def);
			b.setMessage(Component.literal(name + ": " + fmt(def)));
		}).bounds(x + 22, y, w - 44, 20).build();
		addRenderableWidget(Button.builder(Component.literal("-"), b -> {
			set.accept(clamp(get.get() - step, min, max));
			value.setMessage(Component.literal(name + ": " + fmt(get.get())));
		}).bounds(x, y, 20, 20).build());
		addRenderableWidget(value);
		addRenderableWidget(Button.builder(Component.literal("+"), b -> {
			set.accept(clamp(get.get() + step, min, max));
			value.setMessage(Component.literal(name + ": " + fmt(get.get())));
		}).bounds(x + w - 20, y, 20, 20).build());
	}

	private static float clamp(float v, float min, float max) {
		return Math.max(min, Math.min(max, v));
	}

	private static String fmt(float v) {
		return String.format(java.util.Locale.ROOT, "%.2f", v);
	}

	private Component labelMode() {
		return Component.literal("Mode: " + LiquidumDebugState.modeName());
	}

	private Component labelToggle(String name, boolean on) {
		return Component.literal(name + ": " + (on ? "ON" : "OFF"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
