package com.liquidum.client.lab;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.liquidum.client.config.LiquidumPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// Demo scene persistence, config liquidum subfolder, best effort only
public class LabSceneStore {
	public static class Elem {
		public String type = "PANEL";
		public float x;
		public float y;
		public float w = 100;
		public float h = 60;
		public int zOrder;
		public float elevation = 1f;
		public String text = "";
		public int mat = -1;
		public float corner = -1f;
		public int fuseGroup = -1;
		public boolean merge = true;
	}

	public static class Data {
		public List<Elem> elements = new ArrayList<>();
		public int backdrop;
		public boolean dockRight;
		public int scenePreset;
		public String customHex = "5B7C4A";
		public boolean linkedCorner = true;
		public boolean flatBackdrop = false;
		public String photoName;
		public int photoFit;
		public java.util.List<String> history = new java.util.ArrayList<>();
		public Snap snapA = new Snap();
		public Snap snapB = new Snap();
	}

	// Material snapshot slot, plain values only
	public static class Snap {
		public boolean taken;
		public boolean hover = true;
		public boolean aberration = true;
		public boolean rim = true;
		public boolean frost = true;
		public float frostRadius = 7f;
		public boolean fusion = true;
		public float fusionRadius = 12f;
		public boolean parallax = true;
		public boolean lightManual;
		public float lightAngle = 2.16f;
		public float lightLevel = 0.8f;
		public boolean animOpen;
		public float cornerRadiusFraction = 0.18f;
		public float refraction = 9f;
		public float edgeWidth = 1f;
		public float fresnel = 0.65f;
		public float sharpnessMix = 0.18f;
		public float bodyBleed = 0.10f;
		public float edgeBleed = 0.65f;
		public float chroma = 0.45f;
		public float sunSpec = 1f;
		public float tintStrength;
		public float tintRed = 0.62f;
		public float tintGreen = 0.78f;
		public float tintBlue = 1f;
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static Path path() {
		return LiquidumPaths.dir().resolve("liquidum_lab.json");
	}

	public static Data load() {
		try {
			Path p = LiquidumPaths.find("liquidum_lab.json");
			if (p != null && Files.exists(p)) {
				Data d = GSON.fromJson(Files.readString(p), Data.class);
				if (d != null && d.elements != null) {
					return d;
				}
			}
		} catch (Exception ignored) {
			// Corrupt or missing scene, fall back to defaults.
		}
		return new Data();
	}

	public static void save(Data d) {
		try {
			Path p = path();
			Files.createDirectories(p.getParent());
			Files.writeString(p, GSON.toJson(d));
		} catch (Exception ignored) {
			// Best-effort persistence.
		}
	}

	public static DemoElement.Type parseType(String s) {
		try {
			return DemoElement.Type.valueOf(s);
		} catch (Exception ignored) {
			return DemoElement.Type.PANEL;
		}
	}
}
