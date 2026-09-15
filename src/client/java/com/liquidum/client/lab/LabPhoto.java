package com.liquidum.client.lab;

import com.mojang.blaze3d.platform.NativeImage;
import com.liquidum.client.config.LiquidumPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;

// Backdrop photo from the config dir, best effort only
public final class LabPhoto {
	private LabPhoto() {
	}

	private static final String[] NAMES = {"liquidum_backdrop.png", "liquidum_backdrop.jpg", "liquidum_backdrop.jpeg", "liquidum_backdrop.bmp"};
	private static final String[] IMAGE_EXT = {".png", ".jpg", ".jpeg", ".bmp"};
	private static final Identifier ID = Identifier.fromNamespaceAndPath("liquidum", "lab-photo");

	private static DynamicTexture tex;
	private static int iw;
	private static int ih;
	private static String status = "none";
	private static String current;

	public static String status() {
		if (status.equals("none")) return com.liquidum.client.config.LiquidumLang.tr("нет файла");
		if (status.equals("broken")) return com.liquidum.client.config.LiquidumLang.tr("битый файл");
		return status;
	}

	public static boolean has() {
		return tex != null;
	}

	public static int imgW() {
		return iw;
	}

	public static int imgH() {
		return ih;
	}

	public static Identifier textureId() {
		return ID;
	}

	public static String currentName() {
		return current;
	}

	// Any image name inside the backdrops folder, legacy names still listed
	public static java.util.List<String> list() {
		var out = new java.util.ArrayList<String>();
		try (var stream = Files.list(LiquidumPaths.photosDir())) {
			stream.filter(Files::isRegularFile)
				.map(p -> p.getFileName().toString())
				.filter(LabPhoto::isImage)
				.sorted(String.CASE_INSENSITIVE_ORDER)
				.forEach(out::add);
		} catch (Exception ignored) {
		}
		for (String name : NAMES) {
			try {
				if (Files.isRegularFile(LiquidumPaths.dir().resolve(name)) && !out.contains(name)) {
					out.add(name);
				}
			} catch (Exception ignored) {
			}
		}
		return out;
	}

	private static boolean isImage(String name) {
		String lower = name.toLowerCase(java.util.Locale.ROOT);
		for (String ext : IMAGE_EXT) {
			if (lower.endsWith(ext)) {
				return true;
			}
		}
		return false;
	}

	private static Path resolvePhoto(String name) {
		try {
			Path p = LiquidumPaths.photosDir().resolve(name);
			if (Files.isRegularFile(p)) {
				return p;
			}
		} catch (Exception ignored) {
		}
		return LiquidumPaths.find(name);
	}

	public static void reload() {
		reload(null);
	}

	public static void reload(String name) {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null) {
			return;
		}
		var targets = new java.util.ArrayList<String>();
		if (name != null) {
			targets.add(name);
		} else {
			targets.addAll(list());
		}
		for (String target : targets) {
			Path p = resolvePhoto(target);
			if (p == null || !Files.isRegularFile(p)) {
				continue;
			}
			try (var in = Files.newInputStream(p)) {
				NativeImage img = NativeImage.read(in);
				DynamicTexture next = new DynamicTexture(() -> "lab-photo", img);
				next.upload();
				try {
					mc.getTextureManager().release(ID);
				} catch (Exception ignored) {
				}
				if (tex != null) {
					try {
						tex.close();
					} catch (Exception ignored) {
					}
				}
				mc.getTextureManager().register(ID, next);
				tex = next;
				iw = img.getWidth();
				ih = img.getHeight();
				current = target;
				status = target + " " + iw + "x" + ih;
				return;
			} catch (Exception e) {
				status = "broken";
				return;
			}
		}
		status = "none";
	}
}
