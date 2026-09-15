package com.liquidum.client.config;

import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.Files;
import java.nio.file.Path;

// Single home for all Liquidum files inside the config dir
public final class LiquidumPaths {
	private LiquidumPaths() {
	}

	public static Path dir() {
		Path d = FabricLoader.getInstance().getConfigDir().resolve("liquidum");
		try {
			Files.createDirectories(d);
		} catch (Exception ignored) {
		}
		return d;
	}

	public static Path photosDir() {
		Path d = dir().resolve("backdrops");
		try {
			Files.createDirectories(d);
		} catch (Exception ignored) {
		}
		return d;
	}

	// New home first, legacy config root second with one-time move
	public static Path find(String name) {
		Path p = dir().resolve(name);
		try {
			if (Files.isRegularFile(p)) {
				return p;
			}
		} catch (Exception ignored) {
		}
		try {
			Path legacy = FabricLoader.getInstance().getConfigDir().resolve(name);
			if (Files.isRegularFile(legacy)) {
				try {
					Files.move(legacy, p);
					return p;
				} catch (Exception ignored) {
					return legacy;
				}
			}
		} catch (Exception ignored) {
		}
		return null;
	}
}
