package com.liquidum.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Mod settings (ТЗ §Настройки). Data model + JSON persistence only — the YACL /
 * ModMenu UI is wired in a later phase. Loaded/saved from
 * {@code <configDir>/liquidum.json}.
 */
public class LiquidumConfig {
	// Global
	public boolean enabled = true;
	public boolean blurEnabled = true;
	public boolean refractionEnabled = true;
	public boolean fresnelEnabled = true;
	public boolean chromaticEnabled = false;
	public boolean luminanceDockEnabled = true;

	// Per-element
	public boolean hotbarGlass = true;
	public boolean inventorySlotsGlass = true;
	public boolean containerGlass = true;
	public boolean buttonsGlass = true;
	public boolean healthGlass = true;
	public boolean hungerGlass = true;
	public boolean armorGlass = true;
	public boolean xpBarGlass = true;
	public boolean crosshairGlow = false;

	// Quality
	public String blurQuality = "medium";      // low / medium / high
	public float downsampleScale = 0.25f;      // 0.25 / 0.5 / 1.0
	public float refractionStrength = 0.03f;   // 0.01 - 0.1
	public float fresnelIntensity = 0.3f;      // 0.1 - 0.5

	// Performance
	public boolean dynamicQuality = true;
	public boolean cacheBackground = true;
	public int maxFpsTarget = 60;
	public float parallaxStrength = 1.0f;   // 0 = off, ~1 = subtle iOS-like drift
	public boolean tabTransition = false;   // creative tab swipe (WIP, conflicts)

	// Appearance (§1–3 roadmap): режимы ОДНОГО материала, не RGB-подмена.
	public String glassAppearance = "auto";    // auto / light / dark
	public float tintRed = 0.62f;              // custom tint, 0..1
	public float tintGreen = 0.78f;
	public float tintBlue = 1.0f;
	public float tintStrength = 0.0f;          // 0 = off .. 1 = max (role-scaled)

	// Typography (§24–28)
	public String textStyle = "vanilla";       // vanilla / glass / monolith
	// Text role is resolved centrally per call-site; no per-screen field needed

	// Motion: Swipe + Elastic Overscroll (§4–12, §37 reduced-motion)
	public boolean swipeEnabled = true;
	public boolean elasticOverscrollEnabled = true;
	public float motionStrength = 1.0f;        // 0..1, scales settle/overscroll intensity
	public boolean reducedMotion = false;      // §37

	// Creative (§13–16)
	public boolean tabStackEnabled = false;
	public int tabStackVisibleCount = 5;       // how many tabs are visually prominent

	// HUD: Luminance Dock (§17–23) — §7 centralized params
	public boolean dockAdaptive = true;
	public float dockPadding = 3f;          // inner support padding (gui px)
	public float dockOuterPadding = 6f;     // outer refractive footprint extension
	public float dockCornerRadius = 6f;     // Dock Corner Radius (0=пиксельная, >0 сглаженные)
	public float dockRefraction = 0.04f;    // интенсивность внешней прослойки
	public float dockDensity = 0.18f;       // сила readability support внутри

	// Compatibility: Iris (§29–34)
	public String irisIntegration = "auto";    // auto / vanilla / iris_compat

	// Dev / debug
	public boolean debugLogging = true;
	public boolean crashOnError = false;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("liquidum.json");
	}

	public static LiquidumConfig load() {
		try {
			Path p = path();
			if (Files.exists(p)) {
				LiquidumConfig c = GSON.fromJson(Files.readString(p), LiquidumConfig.class);
				if (c != null) {
					return c;
				}
			}
		} catch (Exception ignored) {
			// Corrupt/missing config -> fall back to defaults.
		}
		return new LiquidumConfig();
	}

	public void save() {
		try {
			Path p = path();
			Files.createDirectories(p.getParent());
			Files.writeString(p, GSON.toJson(this));
		} catch (Exception ignored) {
			// Best-effort persistence.
		}
	}
}
