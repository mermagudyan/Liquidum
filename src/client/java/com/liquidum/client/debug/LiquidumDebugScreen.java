package com.liquidum.client.debug;

import com.liquidum.client.shader.LiquidGlassRenderer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Liquidum Lab (F7): isolated subsystem testing + live material tuning.
 * Every processing stage and shader material param can be toggled/observed
 * independently; "Dump to log" prints the full state for the log PASS/FAIL workflow.
 */
public class LiquidumDebugScreen extends Screen {

	public LiquidumDebugScreen() {
		super(Component.literal("Liquidum Lab"));
	}

	@Override
	protected void init() {
		int w = 280;
		int x = this.width / 2 - w / 2;
		int y = Math.max(30, this.height / 2 - 175);
		int step = 24;

		addRenderableWidget(Button.builder(labelMode(), b -> {
			LiquidumDebugState.cycleMode();
			b.setMessage(labelMode());
		}).bounds(x, y, w, 20).build());
		y += step;

		addToggle(x, y, w, "Hover", () -> LiquidumDebugState.hover, v -> LiquidumDebugState.hover = v); y += step;
		addToggle(x, y, w, "Aberration", () -> LiquidumDebugState.aberration, v -> LiquidumDebugState.aberration = v); y += step;
		addToggle(x, y, w, "Rim", () -> LiquidumDebugState.rim, v -> LiquidumDebugState.rim = v); y += step;
		addToggle(x, y, w, "Frost", () -> LiquidumDebugState.frost, v -> LiquidumDebugState.frost = v); y += step;
		addToggle(x, y, w, "Fuse", () -> LiquidumDebugState.fusion, v -> LiquidumDebugState.fusion = v); y += step;
		addToggle(x, y, w, "AnimOpen", () -> LiquidumDebugState.animOpen, v -> LiquidumDebugState.animOpen = v); y += step;
		addToggle(x, y, w, "CrashOnError", () -> LiquidumDebugState.crashOnError, v -> LiquidumDebugState.crashOnError = v); y += step;

		addNumeric(x, y, w, "CornerRadius", () -> LiquidumDebugState.cornerRadiusFraction, v -> LiquidumDebugState.cornerRadiusFraction = v, 0f, 1f, 0.05f, 0.35f); y += step;
		addNumeric(x, y, w, "Refraction", () -> LiquidumDebugState.refraction, v -> LiquidumDebugState.refraction = v, 0f, 200f, 5f, 40f); y += step;
		addNumeric(x, y, w, "Fresnel", () -> LiquidumDebugState.fresnel, v -> LiquidumDebugState.fresnel = v, 0f, 5f, 0.1f, 1f); y += step;
		addNumeric(x, y, w, "SharpnessMix", () -> LiquidumDebugState.sharpnessMix, v -> LiquidumDebugState.sharpnessMix = v, 0f, 1f, 0.05f, 0.08f); y += step;
		addNumeric(x, y, w, "FrostRadius(blur)", () -> LiquidumDebugState.frostRadius, v -> LiquidumDebugState.frostRadius = v, 0f, 30f, 1f, 10f); y += step;
		addNumeric(x, y, w, "FuseRadius(px)", () -> LiquidumDebugState.fusionRadius, v -> LiquidumDebugState.fusionRadius = v, 0f, 60f, 2f, 18f); y += step;
		addNumeric(x, y, w, "AnimMs", () -> LiquidumDebugState.animMillis, v -> LiquidumDebugState.animMillis = v, 50f, 1000f, 10f, 220f); y += step;

		addRenderableWidget(Button.builder(Component.literal("Dump state to log"), b -> {
			LiquidumDebugState.dump();
			LiquidGlassRenderer.dumpDiagnostics();
		}).bounds(x, y, w, 20).build());
		y += step;

		addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
			.bounds(x, y, w, 20).build());

		LiquidumDebugState.dump();
		LiquidGlassRenderer.dumpDiagnostics();
	}

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
