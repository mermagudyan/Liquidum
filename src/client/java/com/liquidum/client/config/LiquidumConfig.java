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
