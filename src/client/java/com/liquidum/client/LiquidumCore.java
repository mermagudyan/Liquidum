package com.liquidum.client;

import com.liquidum.LiquidumMod;
import com.liquidum.client.config.LiquidumConfig;
import com.liquidum.client.material.MaterialRegistry;

/**
 * Central lifecycle hub (ТЗ §B). Initializes GL-free services in order and
 * exposes them. Called from {@code onInitializeClient} — must not touch GL.
 */
public final class LiquidumCore {
	private static LiquidumConfig config;
	private static MaterialRegistry materials;
	private static boolean initialized = false;

	private LiquidumCore() {
	}

	public static void init() {
		if (initialized) {
			return;
		}
		LiquidumMod.LOGGER.info("Liquidum core initializing");
		config = LiquidumConfig.load();
		materials = new MaterialRegistry();
		materials.registerDefaults();
		initialized = true;
		LiquidumMod.LOGGER.info("Liquidum core initialized (materials={})", materials.defaultMaterial() != null ? "ready" : "empty");
	}

	public static LiquidumConfig getConfig() {
		if (config == null) {
			config = new LiquidumConfig();
		}
		return config;
	}

	public static MaterialRegistry getMaterials() {
		if (materials == null) {
			materials = new MaterialRegistry();
			materials.registerDefaults();
		}
		return materials;
	}

	public static boolean isEnabled() {
		return getConfig().enabled;
	}

	public static void reloadConfig() {
		config = LiquidumConfig.load();
	}
}
