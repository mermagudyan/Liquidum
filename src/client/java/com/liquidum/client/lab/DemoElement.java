package com.liquidum.client.lab;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Lab canvas object, pure data without GL calls
public class DemoElement {
	public enum Type { PANEL, PILL, ROUND, AVATAR, SQUARE, SHARP, TRIANGLE, CIRCLE }

	public final UUID id = UUID.randomUUID();
	public float x;
	public float y;
	public float w;
	public float h;
	public int zOrder;
	public float elevation;
	public Type type;
	public String text = "";
	public boolean movable = true;
	public boolean visible = true;
	public boolean placed = false;
	public int mat = -1;
	public float corner = -1f;
	public int fuseGroup = -1;
	public boolean merge = true;

	public DemoElement(Type type, float x, float y, float w, float h, int zOrder, float elevation) {
		this.type = type;
		this.x = x;
		this.y = y;
		this.w = w;
		this.h = h;
		this.zOrder = zOrder;
		this.elevation = elevation;
	}

	public boolean contains(float px, float py) {
		if (type == Type.CIRCLE) {
			float cx = x + w * 0.5f;
			float cy = y + h * 0.5f;
			float r = Math.min(w, h) * 0.5f;
			float dx = px - cx;
			float dy = py - cy;
			return dx * dx + dy * dy <= r * r;
		}
		return px >= x && px <= x + w && py >= y && py <= y + h;
	}

	// Shader shape id: 0 is box, 1 is triangle SDF, 2 is true circle SDF, 5 is squircle
	public int shapeId() {
		if (type == Type.TRIANGLE) {
			return 1;
		}
		if (type == Type.CIRCLE) {
			return 2;
		}
		if (type == Type.PANEL) {
			return 5;
		}
		return 0;
	}

	// Greedy word wrap against inner width, cut overflowing lines
	public static List<String> wrapText(String text, int maxCharsPerLine, int maxLines) {
		List<String> out = new ArrayList<>();
		if (text == null || text.isEmpty() || maxCharsPerLine <= 0 || maxLines <= 0) {
			return out;
		}
		String[] words = text.split("\\s+");
		StringBuilder line = new StringBuilder();
		for (String word : words) {
			int extra = line.length() == 0 ? word.length() : word.length() + 1;
			if (line.length() + extra > maxCharsPerLine) {
				out.add(line.toString());
				if (out.size() >= maxLines) {
					return out;
				}
				line = new StringBuilder(word);
			} else if (line.length() == 0) {
				line.append(word);
			} else {
				line.append(' ').append(word);
			}
		}
		if (line.length() > 0 && out.size() < maxLines) {
			out.add(line.toString());
		}
		return out;
	}
}
