package com.liquidum.client.material;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry of named {@link LiquidumMaterial} variants. Backed by the 8 roadmap
 * presets after {@link #registerDefaults()}.
 */
public class MaterialRegistry {
	private final Map<String, LiquidumMaterial> materials = new HashMap<>();

	public void register(String name, LiquidumMaterial material) {
		materials.put(name, material);
	}

	public LiquidumMaterial get(String name) {
		return materials.get(name);
	}

	public LiquidumMaterial getOrDefault(String name, LiquidumMaterial fallback) {
		return materials.getOrDefault(name, fallback);
	}

	public LiquidumMaterial defaultMaterial() {
		return materials.getOrDefault("regular", new LiquidumMaterial());
	}

	public void registerDefaults() {
		materials.put("regular", MaterialPresets.regular());
		materials.put("clear", MaterialPresets.clear());
		materials.put("frosted", MaterialPresets.frosted());
		materials.put("dark", MaterialPresets.dark());
		materials.put("light", MaterialPresets.light());
		materials.put("performance", MaterialPresets.performance());
		materials.put("strong", MaterialPresets.strong());
		materials.put("weak", MaterialPresets.weak());
		materials.put("custom", MaterialPresets.regular().copy());
	}
}
