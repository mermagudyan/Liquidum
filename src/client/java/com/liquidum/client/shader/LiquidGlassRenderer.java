package com.liquidum.client.shader;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.liquidum.LiquidumMod;
import com.liquidum.client.animation.EasingUtil;
import com.liquidum.client.config.LiquidumConfig;
import com.liquidum.client.debug.LiquidumDebugState;
import com.liquidum.client.mixin.PostChainAccessor;
import com.liquidum.client.mixin.PostPassAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * MC 26.x glass renderer built on the engine's PostChain system.
 *
 * Chain (assets/liquidum/post_effect/glass.json):
 *   main -> box blur X/Y x2 -> "blurred"
 *   glass pass samples main (sharp) + blurred, applies SDF panel with
 *   refraction and Fresnel rim
 *   -> blit back to minecraft:main.
 *
 * The panel rectangle is driven from Java every frame by swapping the pass's
 * "GlassConfig" UBO with our own mappable buffer (see PostPassAccessor).
 */
public class LiquidGlassRenderer {

	public static final String BUILD_ID = "b42";
	public static int pendingTiles() { return pendingCount; }

	private static final Identifier GLASS_CHAIN_ID =
		Identifier.fromNamespaceAndPath("liquidum", "glass");
	private static final Identifier OVERLAY_CHAIN_ID =
		Identifier.fromNamespaceAndPath("liquidum", "glass_overlay");

	private static boolean initialized = false;
	private static boolean errored = false;

	private static Object lastConfigs;
	private static PostChain loadedChain;
	private static PostChain loadedOverlayChain;
	private static Object lastOverlayConfigs;
	private static com.mojang.blaze3d.textures.GpuTextureView glassOutView;
	private static RenderTarget glassOutTarget;

	public static boolean DEBUG = false;
	private static boolean enabled = true;
	private static boolean buttonsGlass = true;
	private static boolean hotbarGlass = true;
	private static boolean slotsGlass = true;
	private static boolean inventorySlotsGlass = true;
	private static boolean healthGlass = true;
	private static boolean hungerGlass = true;
	private static boolean armorGlass = false;
	private static boolean xpGlass = false;
	private static boolean airGlass = false;
	private static float parallaxStrength = 1.0f;

	/** Sync runtime flags — каждый компонент независимо (§T P1 изоляция). */
	public static void applyConfig(LiquidumConfig c) {
		enabled = c.enabled;
		DEBUG = c.debugLogging && LiquidumDebugState.DEBUG_BUILD;
		buttonsGlass = c.buttonsGlass;
		hotbarGlass = c.hotbarGlass;
		slotsGlass = c.containerGlass;
		inventorySlotsGlass = c.inventorySlotsGlass;
		healthGlass = c.healthGlass;
		hungerGlass = c.hungerGlass;
		armorGlass = c.armorGlass;
		xpGlass = c.xpBarGlass;
		airGlass = c.airGlass;
		parallaxStrength = c.parallaxStrength;
		tabTransitionEnabled = c.tabTransition;
		appearanceMode = switch (c.glassAppearance == null ? "auto" : c.glassAppearance.toLowerCase()) {
			case "light" -> APPEAR_LIGHT;
			case "dark" -> APPEAR_DARK;
			default -> APPEAR_AUTO;
		};
		tintR = Math.max(0f, Math.min(1f, c.tintRed));
		tintG = Math.max(0f, Math.min(1f, c.tintGreen));
		tintB = Math.max(0f, Math.min(1f, c.tintBlue));
		tintStrength = Math.max(0f, Math.min(1f, c.tintStrength));
		luminanceDockEnabled = c.luminanceDockEnabled && c.dockAdaptive; // adaptive gate
		dockPadding = Math.max(0f, Math.min(8f, c.dockPadding));
		dockOuterPad = Math.max(0f, Math.min(12f, c.dockOuterPadding));
		dockCornerRadius = Math.max(0f, Math.min(12f, c.dockCornerRadius));
		dockRefraction = Math.max(0f, Math.min(0.2f, c.dockRefraction));
		dockDensity = Math.max(0f, Math.min(0.5f, c.dockDensity));
	}

	public static boolean isEnabled() {
		return enabled;
	}

	private static Object currentConfigs;

	/** The offscreen target the glass chain renders into (blitted by the GUI layer). */
	public static com.mojang.blaze3d.textures.GpuTextureView getGlassOutputView() {
		return glassOutView;
	}

	private static void resolveGlassOutput(PostChain chain, RenderTarget main) {
		// Engine rebuilds persistent targets from json descriptors every frame so Java resize alone never sticks: sync the descriptor sizes first
		for (var e : ((com.liquidum.client.mixin.PostChainAccessor) chain).liquidum$getPersistentTargets().entrySet()) {
			var t = e.getValue();
			String path = ((Identifier) e.getKey()).getPath();
			// Overlay blur mirrors base frost at 0.65x
			if (path.equals("overlay_blur") || path.equals("overlay_blur2")) {
				int qw = Math.max(1, (int)(main.width * 0.65f));
				int qh = Math.max(1, (int)(main.height * 0.65f));
				if (t.width != qw || t.height != qh) {
					t.resize(qw, qh);
				}
				syncTargetSize(chain, path, qw, qh);
				continue;
			}
			// Overlay low-frequency fields mirror the base pyramid
			if (path.equals("overlay_colorfield")) {
				int cw = Math.max(1, (int)(main.width * 0.25f));
				int ch = Math.max(1, (int)(main.height * 0.25f));
				if (t.width != cw || t.height != ch) {
					t.resize(cw, ch);
				}
				syncTargetSize(chain, path, cw, ch);
				continue;
			}
			if (path.equals("overlay_deepfield")) {
				int dw = Math.max(1, (int)(main.width * 0.125f));
				int dh = Math.max(1, (int)(main.height * 0.125f));
				if (t.width != dw || t.height != dh) {
					t.resize(dw, dh);
				}
				syncTargetSize(chain, path, dw, dh);
				continue;
			}
			// Blurred mip level for frost, quarter caused cubes on villager house
			if (path.equals("blurred")) {
				int qw = Math.max(1, (int)(main.width * 0.65f));
				int qh = Math.max(1, (int)(main.height * 0.65f));
				if (t.width != qw || t.height != qh) {
					t.resize(qw, qh);
				}
				syncTargetSize(chain, path, qw, qh);
				continue;
			}
			// Low frequency color field at quarter resolution
			if (path.equals("colorfield")) {
				int cw = Math.max(1, (int)(main.width * 0.25f));
				int ch = Math.max(1, (int)(main.height * 0.25f));
				if (t.width != cw || t.height != ch) {
					t.resize(cw, ch);
				}
				syncTargetSize(chain, path, cw, ch);
				continue;
			}
			// Deep field for large glass, one eighth resolution
			if (path.equals("deepfield")) {
				int dw = Math.max(1, (int)(main.width * 0.125f));
				int dh = Math.max(1, (int)(main.height * 0.125f));
				if (t.width != dw || t.height != dh) {
					t.resize(dw, dh);
				}
				syncTargetSize(chain, path, dw, dh);
				continue;
			}
			if (t.width != main.width || t.height != main.height) {
				t.resize(main.width, main.height);
			}
			if (path.equals("glassout")) {
				var view = t.getColorTextureView();
				if (view != null) glassOutView = view;
				glassOutTarget = t;
			}
		}
		logMipSizes(chain, main);
	}

	private static String lastMipLogBase = "";
	private static String lastMipLogOverlay = "";
	private static long lastMipLogNanos = 0L;
	private static void logMipSizes(PostChain chain, RenderTarget main) {
		try {
			var map = ((com.liquidum.client.mixin.PostChainAccessor) chain).liquidum$getPersistentTargets();
			StringBuilder sb = new StringBuilder(main.width + "x" + main.height);
			for (var e : map.entrySet()) {
				var t = e.getValue();
				sb.append(" ").append(e.getKey()).append("=").append(t.width).append("x").append(t.height);
			}
			String s = sb.toString();
			boolean overlay = chain == loadedOverlayChain;
			if (overlay && s.equals(lastMipLogOverlay)) return;
			if (!overlay && s.equals(lastMipLogBase)) return;
			if (overlay) lastMipLogOverlay = s;
			else lastMipLogBase = s;
			long now = System.nanoTime();
			if (now - lastMipLogNanos < 2_000_000_000L) return;
			lastMipLogNanos = now;
			LiquidumMod.LOGGER.info("[glass] MIP {}main={}", overlay ? "overlay " : "", s);
		} catch (Exception ignored) {
		}
	}

	// Engine sizes targets from json descriptors (orElse main size) so rewrite the record to match our resize, else it reallocates full
	private static boolean targetSyncWarned = false;
	private static void syncTargetSize(PostChain chain, String path, int w, int h) {
		try {
			var acc = ((com.liquidum.client.mixin.PostChainAccessor) chain);
			var internals = acc.liquidum$getInternalTargets();
			boolean need = false;
			for (var e : internals.entrySet()) {
				String p = ((Identifier) e.getKey()).getPath();
				if (!path.equals(p)) continue;
				var old = e.getValue();
				if (old.width().orElse(-1) != w || old.height().orElse(-1) != h) need = true;
			}
			if (!need) return;
			// Target map is immutable so replace it with a sized copy
			var copy = new java.util.HashMap<>(internals);
			for (var e : copy.entrySet()) {
				String p = ((Identifier) e.getKey()).getPath();
				if (!path.equals(p)) continue;
				var old = e.getValue();
				e.setValue(new net.minecraft.client.renderer.PostChainConfig.InternalTarget(
					java.util.Optional.of(w), java.util.Optional.of(h), old.persistent(), old.clearColor()));
			}
			acc.liquidum$setInternalTargets(copy);
		} catch (Exception ex) {
			if (!targetSyncWarned) {
				targetSyncWarned = true;
				LiquidumMod.LOGGER.warn("[glass] target descriptor sync failed, mip blur stays full-res");
			}
		}
	}

	/** S.8 scissor union: bounds of all glass in framebuffer px (bottom-origin), padded for blur/refraction spill. */
	private static int[] computeScissorUnion(RenderTarget main) {
		if (pendingCount == 0 && hudPanelArea == 0 && wellCellCount == 0) return null;
		float fw = main.width;
		float fh = main.height;
		float scale = fw / Math.max(1f, (float) pendingGuiW);
		// heuristic pad: blur radius ~4.2*4=16.8fb + refraction ~3fb + rim 2fb ≈22fb
		float pad = 22f * (scale / Math.max(1f, fw / 1920f * 4f + 1f));
		// simpler: 24 gui px * scale (covers frost spill)
		pad = 24f * scale;
		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
		float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		boolean has = false;
		for (int i = 0; i < pendingCount; i++) {
			float hw = pendW[i] * 0.5f * scale;
			float hh = pendH[i] * 0.5f * scale;
			float cx = (pendX[i] + pendW[i] * 0.5f) * scale;
			float cy = fh - (pendY[i] + pendH[i] * 0.5f) * scale;
			float x0 = cx - hw, x1 = cx + hw, y0 = cy - hh, y1 = cy + hh;
			minX = Math.min(minX, x0); maxX = Math.max(maxX, x1);
			minY = Math.min(minY, y0); maxY = Math.max(maxY, y1);
			has = true;
		}
		// Panel (if open) — hudPanelX/Y/W/H are gui px, bottom-origin conversion
		boolean panelOpen = hudPanelArea > 0 && Minecraft.getInstance().gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
		if (panelOpen) {
			float hw = hudPanelW * 0.5f * scale;
			float hh = hudPanelH * 0.5f * scale;
			float cx = (hudPanelX + hudPanelW * 0.5f) * scale;
			float cy = fh - (hudPanelY + hudPanelH * 0.5f) * scale;
			float x0 = cx - hw, x1 = cx + hw, y0 = cy - hh, y1 = cy + hh;
			minX = has ? Math.min(minX, x0) : x0; maxX = has ? Math.max(maxX, x1) : x1;
			minY = has ? Math.min(minY, y0) : y0; maxY = has ? Math.max(maxY, y1) : y1;
			has = true;
		}
		// Well cells (fallback when pendingCount≈0 but wells exist e.g. inventory GridWell only)
		for (int i = 0; i < wellCellCount; i++) {
			int b = i * 4;
			float hw = wellCells[b + 2] * 0.5f * scale;
			float hh = wellCells[b + 3] * 0.5f * scale;
			float cx = (wellCells[b] + wellCells[b + 2] * 0.5f) * scale;
			float cy = fh - (wellCells[b + 1] + wellCells[b + 3] * 0.5f) * scale;
			float x0 = cx - hw, x1 = cx + hw, y0 = cy - hh, y1 = cy + hh;
			if (!has) { minX = x0; maxX = x1; minY = y0; maxY = y1; has = true; }
			else { minX = Math.min(minX, x0); maxX = Math.max(maxX, x1); minY = Math.min(minY, y0); maxY = Math.max(maxY, y1); }
		}
		if (!has) return null;
		minX -= pad; minY -= pad; maxX += pad; maxY += pad;
		// Clamp to framebuffer
		minX = Math.max(0, minX); minY = Math.max(0, minY);
		maxX = Math.min(fw, maxX); maxY = Math.min(fh, maxY);
		int ix = (int) Math.floor(minX);
		int iy = (int) Math.floor(minY);
		int iw = (int) Math.ceil(maxX - minX);
		int ih = (int) Math.ceil(maxY - minY);
		if (iw <= 0 || ih <= 0) return null;
		// Fullscreen skip: if union covers ~90% of screen, scissor is overhead
		if (iw * ih > fw * fh * 0.90f) return null;
		return new int[]{ ix, iy, iw, ih };
	}

	/** Overlay tiles are popup sheets: TAIL composite samples the finished scene. */
	public static boolean hasPopupTiles() {
		for (int i = 0; i < pendingCount; i++) {
			if (pendMat[i] == MAT_POPUP) return true;
		}
		return false;
	}

	// Upper sheets present above the base window this frame
	public static boolean hasUpperSheets() {
		if (upperLevelCount <= 0) return false;
		float lo = upperLevels[0] - 0.001f;
		for (int i = 0; i < pendingCount; i++) {
			if (pendMat[i] == MAT_POPUP) continue;
			if (!isSheetMat(pendMat[i]) || pendElev[i] <= lo) continue;
			return true;
		}
		return false;
	}

	// Overlay runs for popups or upper sheets, levels recomputed fresh
	public static boolean hasOverlayWork() {
		computeUpperLevels();
		return hasPopupTiles() || hasUpperSheets();
	}

	// Sheets transmit over lower sheets, backing and cells stay opaque
	private static boolean isSheetMat(int m) {
		return m == MAT_CONTROL || m == MAT_ACTIVE || m == MAT_DENSE
			|| m == MAT_COMPANION || m == MAT_GROUP || m == MAT_CARD;
	}

	private static final float[] upperLevels = new float[8];
	private static int upperLevelCount = 0;

	// Every sheet elevation above the lowest is its own optical plane
	private static void computeUpperLevels() {
		upperLevelCount = 0;
		float base = Float.POSITIVE_INFINITY;
		for (int i = 0; i < pendingCount; i++) {
			if (pendMat[i] == MAT_POPUP || !isSheetMat(pendMat[i])) continue;
			base = Math.min(base, pendElev[i]);
		}
		if (!Float.isFinite(base)) return;
		for (int i = 0; i < pendingCount; i++) {
			if (pendMat[i] == MAT_POPUP || !isSheetMat(pendMat[i])) continue;
			float e = pendElev[i];
			if (e <= base + 0.001f) continue;
			boolean have = false;
			for (int k = 0; k < upperLevelCount; k++) {
				if (Math.abs(upperLevels[k] - e) <= 0.001f) { have = true; break; }
			}
			if (have || upperLevelCount >= upperLevels.length) continue;
			int at = upperLevelCount;
			while (at > 0 && upperLevels[at - 1] > e) { upperLevels[at] = upperLevels[at - 1]; at--; }
			upperLevels[at] = e;
			upperLevelCount++;
		}
	}

	/** Scissor union over popup and upper tiles, same pad as base. */
	private static int[] computeOverlayScissorUnion(RenderTarget main) {
		boolean popup = hasPopupTiles();
		boolean upperWin = upperLevelCount > 0;
		float upperLo = upperWin ? upperLevels[0] - 0.001f : Float.POSITIVE_INFINITY;
		if (!popup && !upperWin) return null;
		float fw = main.width;
		float fh = main.height;
		float scale = fw / Math.max(1f, (float) pendingGuiW);
		float pad = 24f * scale;
		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
		float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		boolean has = false;
		for (int i = 0; i < pendingCount; i++) {
			if (pendMat[i] != MAT_POPUP
				&& (!upperWin || !isSheetMat(pendMat[i]) || pendElev[i] <= upperLo)) continue;
			float hw = pendW[i] * 0.5f * scale;
			float hh = pendH[i] * 0.5f * scale;
			float cx = (pendX[i] + pendW[i] * 0.5f) * scale;
			float cy = fh - (pendY[i] + pendH[i] * 0.5f) * scale;
			float x0 = cx - hw, x1 = cx + hw, y0 = cy - hh, y1 = cy + hh;
			if (!has) { minX = x0; maxX = x1; minY = y0; maxY = y1; has = true; }
			else { minX = Math.min(minX, x0); maxX = Math.max(maxX, x1); minY = Math.min(minY, y0); maxY = Math.max(maxY, y1); }
		}
		if (!has) return null;
		minX -= pad; minY -= pad; maxX += pad; maxY += pad;
		minX = Math.max(0, minX); minY = Math.max(0, minY);
		maxX = Math.min(fw, maxX); maxY = Math.min(fh, maxY);
		int ix = (int) Math.floor(minX);
		int iy = (int) Math.floor(minY);
		int iw = (int) Math.ceil(maxX - minX);
		int ih = (int) Math.ceil(maxY - minY);
		if (iw <= 0 || ih <= 0) return null;
		if (iw * ih > fw * fh * 0.90f) return null;
		return new int[]{ ix, iy, iw, ih };
	}

	/** Called by ShaderManagerMixin after every (re)load of shader configs. */
	public static void onShaderConfigs(Object configs) {
		if (configs != lastConfigs && DEBUG) {
			LiquidumMod.LOGGER.info("[glass] shader configs changed: {} -> {} (chain will rebuild)",
				System.identityHashCode(lastConfigs), System.identityHashCode(configs));
		}
		if (configs != lastConfigs) {
			if (loadedChain != null) {
				try { loadedChain.close(); } catch (Exception ignored) { }
				loadedChain = null;
			}
			if (loadedOverlayChain != null) {
				try { loadedOverlayChain.close(); } catch (Exception ignored) { }
				loadedOverlayChain = null;
			}
			glassOutView = null;
			glassOutTarget = null;
			lastConfigs = null;
			lastOverlayConfigs = null;
			// F3+T must restore bliks even if light spring went NaN after 10s
			lightDirX = 0f; lightDirY = 1f; lightDirVelX = 0f; lightDirVelY = 0f;
			lightIntensity = 1f; lightIntensityVel = 0f; lightConfidence = 1f; lightDirNanos = 0L;
			worldLightX = 0f; worldLightY = 1f; worldLightZ = 0f; worldVelX = 0f; worldVelY = 0f; worldVelZ = 0f;
		}
		currentConfigs = configs;
	}

	private static GpuBuffer glassConfigBuffer;
	// Separate UBO per chain: vanilla closes bound buffers, sharing killed base after the first TAIL run
	private static GpuBuffer overlayConfigBuffer;

	private static final int MAX_PANELS = 128;
	/** Максимум GridWell-дескрипторов за кадр (12 сеток хватает для любого
	 *  ванильного контейнера: chest = content + inv + hotbar = 3 wells). */
	public static final int MAX_WELLS = 12;
	// GlassConfig UBO layout: rects, mats, wells, cutouts, then param blocks
	private static final int GLASS_CONFIG_BYTES =
		MAX_PANELS * 16 * 2 + MAX_WELLS * 3 * 16 + 16 * 35;

	/** Set true to dump the next screen's widget classes once (diagnostics). */
	public static boolean dumpWidgetClasses = true;

	/** When true, vanilla button sprites are skipped so glass becomes the button body. */
	public static boolean replaceVanillaButtonBackground() {
		return enabled && buttonsGlass;
	}

	/** When true, the hotbar background sprite is skipped so glass becomes the bar. */
	public static boolean replaceHotbarBackground() {
		return enabled && hotbarGlass;
	}

	/** When true, slots get glass — раздельно для инвентаря и контейнеров. */
	public static boolean replaceSlotTiles() {
		if (!enabled) return false;
		var s = Minecraft.getInstance().gui.screen();
		if (s instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen) return inventorySlotsGlass;
		if (s instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen) return inventorySlotsGlass;
		return slotsGlass;
	}

	// Tab transition: captured old frame slides up over the new content
	private static boolean tabAnimActive = false;
	private static long tabAnimStart = 0L;
	private static com.mojang.blaze3d.platform.NativeImage tabPrevImage;
	private static boolean tabTransitionEnabled = false;

	public static boolean isTabTransitionEnabled() {
		return tabTransitionEnabled;
	}

	public static int diagCount() {
		return debugCount;
	}

	public static void startTabTransition() {
		// WIP: the capture/slide transition conflicts with scroll spring and needs polishing — disabled by default (tabTransition in config).
		if (!tabTransitionEnabled) return;
		captureTabFrame();
		tabAnimActive = true;
		tabAnimStart = System.nanoTime();
	}

	private static void captureTabFrame() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gameRenderer == null || hudPanelW <= 0 || pendingGuiW <= 0) return;
		RenderTarget main = mc.gameRenderer.mainRenderTarget();
		float scale = (float) main.width / pendingGuiW;
		int sx = Math.max(0, Math.round(hudPanelX * scale) - 2);
		int sy = Math.max(0, Math.round(hudPanelY * scale) - 2);
		int w = Math.min(Math.round(hudPanelW * scale) + 6, main.width - sx);
		int h = Math.min(Math.round(hudPanelH * scale) + 6, main.height - sy);
		if (w <= 0 || h <= 0) return;
		GpuDevice device = RenderSystem.getDevice();
		CommandEncoder enc = device.createCommandEncoder();
		GpuBuffer buf = device.createBuffer(() -> "liquidum uiprev capture",
			GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, (int) ((long) w * h * 4));
		try {
			enc.copyTextureToBuffer(main.getColorTexture(), buf, 0, () -> {
			}, 0, sx, sy, w, h);
			try (var view = buf.slice().map(true, false)) {
				ByteBuffer bytes = view.data();
				if (tabPrevImage == null || tabPrevImage.getWidth() != w || tabPrevImage.getHeight() != h) {
					if (tabPrevImage != null) tabPrevImage.close();
					tabPrevImage = new com.mojang.blaze3d.platform.NativeImage(w, h, false);
				}
				for (int yy = 0; yy < h; yy++) {
					for (int xx = 0; xx < w; xx++) {
						int i = (yy * w + xx) * 4;
						int abgr = (bytes.get(i) & 0xFF)
							| ((bytes.get(i + 1) & 0xFF) << 8)
							| ((bytes.get(i + 2) & 0xFF) << 16)
							| ((bytes.get(i + 3) & 0xFF) << 24);
						tabPrevImage.setPixelABGR(xx, yy, abgr);
					}
				}
			}
			if (loadedChain != null) {
				var t = ((PostChainAccessor) loadedChain).liquidum$getPersistentTargets()
					.get(Identifier.parse("minecraft:uiprev"));
				if (t != null) enc.writeToTexture(t.getColorTexture(), tabPrevImage, sx, sy, w, h);
			}
		} catch (Exception e) {
			if (DEBUG) LiquidumMod.LOGGER.warn("[glass] tab frame capture failed: {}", e.toString());
		} finally {
			buf.close();
		}
	}


	// One material, roles differ by params: bigger is calmer, smaller is livelier
	/** L1 BASE SURFACE — большая спокойная поверхность окна. */
	public static final int MAT_BASE = 0;
	/** Слот — минимальная единица сетки, тихий fill + тонкий edge. */
	public static final int MAT_SLOT = 1;
	/** L2 FUNCTIONAL GROUP — лёгкая локальная разница яркости, БЕЗ карточки. */
	public static final int MAT_GROUP = 2;
	/** L3 INTERACTIVE GLASS — поиск, вкладки, кнопки, скроллбар. */
	public static final int MAT_CONTROL = 3;
	/** Companion/sidebar (Recipe Book) — прозрачнее, меньше визуальной массы. */
	public static final int MAT_COMPANION = 4;
	/** Активное/выбранное состояние контрола. */
	public static final int MAT_ACTIVE = 5;
	/** Плотная HUD-панель (хотбар): решётка слотов + мягкое выделение. */
	public static final int MAT_DENSE = 6;
	/** Luminance Dock: адаптивная HUD-поверхность под hearts/food/armor/xp (очень лёгкая). */
	public static final int MAT_DOCK = 7;
	// Popup sheet: boosted matte, follows user frost and tint
	public static final int MAT_POPUP = 8;
	// Settings card: static full matte, ignores user frost and tint
	public static final int MAT_CARD = 9;

	/** One container slot: 18x18 gui px WELL (fusable+dense) - a recessed
	 *  cell etched into the frosted panel, no gaps: the whole inventory reads
	 *  as one continuous glass surface (user sketch). */
	public static void submitSlotWell(int x, int y) {
		if (!replaceSlotTiles()) return;
		if (slotTilesFrom < 0) slotTilesFrom = pendingCount;   // parallax range marker
		appendRect(x, y, 18, 18, MAT_SLOT);
	}

	/** Should this blit be replaced by our frosted glass panel? */
	public static boolean filterContainerPanel(Identifier texture) {
		if (!replaceSlotTiles()) return false;
		var screen = Minecraft.getInstance().gui.screen();
		if (!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen)) return false;
		boolean hit = texture.getPath().startsWith("textures/gui/container/");
		if (DEBUG && hit && filterLogCount++ < 5) {
			LiquidumMod.LOGGER.info("[glass] panel texture captured: {} {}x{} at {},{}",
				texture.getPath(), hudPanelW, hudPanelH, 0, 0);
		}
		return hit;
	}

	/** Recipe book panel texture → light glass base (coexists with uPanel). */
	public static boolean filterRecipeBookPanel(Identifier texture) {
		if (!replaceSlotTiles()) return false;
		var screen = Minecraft.getInstance().gui.screen();
		if (!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen)) return false;
		return texture.getPath().equals("textures/gui/recipe_book.png");
	}

	/** Recipe book panel: та же поверхность, что крупные области pause menu
	 *  (edge-зона рефракции сама размывается по большой площади). */
	public static void submitLightPanel(int x, int y, int w, int h) {
		if (w <= 0 || h <= 0) return;
		appendRect(x, y, w, h, MAT_COMPANION);
	}

	/** Book body rect (gui px) for tab bridging, reset every frame */
	private static int bookX0 = 0, bookY0 = 0, bookX1 = -1, bookY1 = -1;

	/** Recipe book body on layer 0 until content replay exists, records its rect for the active tab bridge */
	public static void submitBookPanel(int x, int y, int w, int h) {
		if (w <= 0 || h <= 0) return;
		bookX0 = x; bookY0 = y; bookX1 = x + w; bookY1 = y + h;
		appendRect(x, y, w, h, MAT_COMPANION, 0.0f, 1.0f);
	}

	/** Right-edge extension so the active tab lands solid inside the book */
	public static int tabBridge(int x, int y, int w, int h) {
		if (bookX1 <= bookX0) return 4;
		if (y + h <= bookY0 || y >= bookY1) return 0;
		if (x + w >= bookX0 + 4) return 0;
		return Math.min(12, (bookX0 + 4) - (x + w));
	}
	// Base sheet stays behind widgets and hotbar, never dims them
	public static void submitBasePanel(int x, int y, int w, int h) {
		if (w <= 0 || h <= 0) return;
		appendRect(x, y, w, h, MAT_BASE, -1f, 0f);
	}

	private static int filterLogCount = 0;

	/** Panel rect from the cancelled blit — keep the LARGEST per frame
	 *  (decorative container blits may precede the main panel).
	 *  hudPanelArea resets EVERY frame (resetFrame): a per-frame winner is
	 *  mandatory — with a cross-frame guard the rect went stale when opening
	 *  the recipe book shifted leftPos (same area, new X) and slots ended up
	 *  OUTSIDE the glass. */
	public static void submitPanelRect(int x, int y, int w, int h) {
		int area = w * h;
		if (area <= hudPanelArea) return;
		hudPanelArea = area;
		hudPanelX = x;
		hudPanelY = y;
		hudPanelW = w;
		hudPanelH = h;
	}

	/** Creative mode tabs: glass tile instead of the tab sprite. */
	public static boolean filterCreativeTab(Identifier sprite) {
		if (!replaceSlotTiles()) return false;
		var screen = Minecraft.getInstance().gui.screen();
		return screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
			&& sprite.getPath().contains("creative_inventory/tab");
	}

	public static void submitTabTile(int x, int y, int w, int h) {
		// iPhone: ещё компактнее — не mini-panel, навигационный чип
		appendRect(x + 4, y + 4, w - 8, h - 8, MAT_CONTROL);
	}

	/**
	 * Vanilla-спрайты внутри контейнерных экранов заменяются стеклом:
	 * поле поиска (widget/text_field*) и вкладки книги рецептов
	 * (recipe_book/tab*). Возвращает материал для submitSpriteTile или -1.
	 */
	public static int filterUiSprite(Identifier sprite) {
		if (!replaceSlotTiles()) return -1;
		var screen = Minecraft.getInstance().gui.screen();
		if (!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen)) return -1;
		String p = sprite.getPath();
		if (p.startsWith("widget/text_field")) return MAT_CONTROL;
		if (p.startsWith("recipe_book/tab")) {
			return p.contains("selected") ? MAT_ACTIVE : MAT_CONTROL;
		}
		// Выбранный рецепт: vanilla красная рамка (recipe_book/overlay*) заменяется состоянием ACTIVE того же материала.
		if (p.startsWith("recipe_book/overlay")) return MAT_ACTIVE;
		// Вкладки креатива: активная — тот же COMPANION что инвентарь (одна поверхность), неактивные — CONTROL; вся группа один слой высота 0 группа 0.
		if (p.startsWith("container/creative_inventory/tab_")) {
			return p.contains("selected") ? MAT_COMPANION : MAT_CONTROL;
		}
		// Трек скроллбара креатива — тихое стекло вместо белой полосы (сам ползунок-scroller остаётся ванильным — это ручка).
		if (p.equals("widget/scroller_background")) return MAT_SLOT;
		return -1;
	}

	/** Стеклянная плитка вместо vanilla-спрайта (поиск, вкладки книги).
	 *  НЕ входит в parallax-диапазон слотов — содержимое остаётся статичным. */
	public static void submitSpriteTile(int x, int y, int w, int h, int mat) {
		appendRect(x, y, w, h, mat);
	}
	public static void submitSpriteTile(int x, int y, int w, int h, int mat, float elev) {
		appendRect(x, y, w, h, mat, elev);
	}
	public static void submitSpriteTile(int x, int y, int w, int h, int mat, float elev, float group) {
		appendRect(x, y, w, h, mat, elev, group, 0f);
	}
	public static void submitSpriteTile(int x, int y, int w, int h, int mat, float elev, float group, float shapeW) {
		appendRect(x, y, w, h, mat, elev, group, shapeW);
	}

	/** L2 FUNCTIONAL GROUP: лёгкая локальная разница яркости над BASE,
	 *  без отдельной карточки/рамки. */
	public static void submitGroupRect(int x, int y, int w, int h) {
		if (w <= 0 || h <= 0) return;
		appendRect(x, y, w, h, MAT_GROUP);
	}

	// Recipe Book button keeps hitbox, glass body plus book glyph only
	private static boolean drawingBookIcon = false;
	private static int lastRecipeBookX = -1, lastRecipeBookY = -1, lastRecipeBookW = -1, lastRecipeBookH = -1;

	public static boolean isRecipeBookButton(net.minecraft.client.gui.components.AbstractWidget w) {
		return w != null && w.getX() == lastRecipeBookX && w.getY() == lastRecipeBookY
			&& w.getWidth() == lastRecipeBookW && w.getHeight() == lastRecipeBookH;
	}

	public static void drawRecipeBookButton(net.minecraft.client.gui.GuiGraphicsExtractor g,
	                                        com.mojang.blaze3d.pipeline.RenderPipeline pipeline,
	                                        int x, int y, int w, int h, boolean hovered) {
		if (drawingBookIcon) return;
		drawingBookIcon = true;
		lastRecipeBookX = x; lastRecipeBookY = y; lastRecipeBookW = w; lastRecipeBookH = h;
		try {
			// Full rect stays readable around the 16px glyph
			submitSpriteTile(x, y, w, h, MAT_CONTROL);
			int cx = x + (w - 16) / 2;
			int cy = y + (h - 16) / 2;
			// Glyph replays above glass like tab icons, direct draw only when deferral is off
			if (deferForeground()) deferTabIcon(new ItemStack(Items.BOOK), cx, cy, 0);
			else g.item(new ItemStack(Items.BOOK), cx, cy, 0);
		} finally {
			drawingBookIcon = false;
		}
	}

	// GridWell: one descriptor per slot grid, cells resolved in-shader
	private static final java.util.List<Object[]> deferredSprites = new java.util.ArrayList<>();
	private static final java.util.List<Object[]> deferredBlits = new java.util.ArrayList<>();
	private static final java.util.List<Object[]> deferredTabIcons = new java.util.ArrayList<>();
	private static final java.util.List<Object[]> deferredTexts = new java.util.ArrayList<>();
	private static final java.util.List<Object[]> deferredHudItems = new java.util.ArrayList<>();
	private static final java.util.List<Object[]> deferredHudDecor = new java.util.ArrayList<>();
	private static final java.util.List<Object[]> deferredBlits9 = new java.util.ArrayList<>();
	private static final java.util.List<Object[]> deferredSeqTexts = new java.util.ArrayList<>();
	private static Object pendingMapDraw;
	public static boolean isInForegroundReplay() { return inForegroundReplay; }

	public static void deferBlitSprite(com.mojang.blaze3d.pipeline.RenderPipeline pipeline, net.minecraft.resources.Identifier sprite, int x, int y, int w, int h) {
		if (deferredSprites.size() >= 32) return;
		deferredSprites.add(new Object[]{pipeline, sprite, x, y, w, h});
	}
	public static void deferBlit(com.mojang.blaze3d.pipeline.RenderPipeline pipeline, net.minecraft.resources.Identifier tex, int x, int y, float u, float v, int w, int h, int texW, int texH) {
		if (deferredBlits.size() >= 32) return;
		deferredBlits.add(new Object[]{pipeline, tex, x, y, u, v, w, h, texW, texH});
	}
	public static void deferTabIcon(net.minecraft.world.item.ItemStack stack, int x, int y, int seed) {
		if (deferredTabIcons.size() >= 32) return;
		deferredTabIcons.add(new Object[]{stack.copy(), x, y, seed});
	}
	public static void deferText(net.minecraft.client.gui.Font font, net.minecraft.network.chat.Component text, int x, int y, int color, boolean shadow) {
		if (deferredTexts.size() >= 32) return;
		deferredTexts.add(new Object[]{font, text.copy(), x, y, color, shadow});
	}
	public static void deferHudItem(net.minecraft.world.entity.LivingEntity e, net.minecraft.world.item.ItemStack stack, int x, int y, int seed) {
		if (deferredHudItems.size() >= 16) return;
		deferredHudItems.add(new Object[]{e, stack.copy(), x, y, seed});
	}
	public static void deferHudDecor(net.minecraft.client.gui.Font font, net.minecraft.world.item.ItemStack stack, int x, int y) {
		if (deferredHudDecor.size() >= 16) return;
		deferredHudDecor.add(new Object[]{font, stack.copy(), x, y});
	}
	public static void deferBlitSprite9(com.mojang.blaze3d.pipeline.RenderPipeline pipeline, net.minecraft.resources.Identifier sprite,
	                                    int u0, int v0, int sw, int sh, int x, int y, int w, int h) {
		if (deferredBlits9.size() >= 32) return;
		deferredBlits9.add(new Object[]{pipeline, sprite, u0, v0, sw, sh, x, y, w, h});
	}
	public static void deferSeqText(net.minecraft.client.gui.Font font, net.minecraft.util.FormattedCharSequence text, int x, int y, int color, boolean shadow) {
		if (deferredSeqTexts.size() >= 32) return;
		deferredSeqTexts.add(new Object[]{font, text, x, y, color, shadow});
	}
	public static void captureMap(net.minecraft.client.renderer.state.MapRenderState state) {
		pendingMapDraw = state;
	}
	public static final int SHAPE_HEART = 3;
	public static final int SHAPE_MEAT = 4;
	private static final long[] iconKeys = new long[64];
	private static int iconKeyCount = 0;
	// Icon glass follows the sprite halo, rim only without frost
	public static void submitIconTile(int x, int y, int w, int h, int shape) {
		if (!enabled || !luminanceDockEnabled) return;
		if (shape == SHAPE_HEART && !healthGlass) return;
		if (shape == SHAPE_MEAT && !hungerGlass) return;
		if (shape == 0 && !armorGlass) return;
		if (shape == 2 && !airGlass) return;
		long key = ((long) x << 32) | (y & 0xffffffffL);
		for (int i = 0; i < iconKeyCount; i++) if (iconKeys[i] == key) return;
		if (iconKeyCount >= iconKeys.length) return;
		iconKeys[iconKeyCount++] = key;
		appendRect(x - 1, y - 1, w + 2, h + 2, MAT_DOCK, 0f, 0f, (float) shape);
	}
	public static void replayHudBar(net.minecraft.client.gui.GuiGraphicsExtractor g) {
		if (deferredHudItems.isEmpty() && deferredHudDecor.isEmpty()) return;
		boolean old = inForegroundReplay;
		inForegroundReplay = true;
		try {
			boolean show = hotbarCollapse < 0.5f;
			if (show && !deferredHudItems.isEmpty()) LiquidumLayers.beginItems(g);
			for (Object[] a : deferredHudItems) {
				if (!show) break;
				// Buttons sit above items, clipped ones stay under blurred glass
				int ix = (Integer) a[2], iy = (Integer) a[3];
				if (clipHudUnderButtons() && isUnderElevatedTile(ix, iy, 16, 16)) continue;
				g.item((net.minecraft.world.entity.LivingEntity) a[0], (net.minecraft.world.item.ItemStack) a[1], (Integer) a[2], (Integer) a[3], (Integer) a[4]);
			}
			if (show && !deferredHudDecor.isEmpty()) LiquidumLayers.beginText(g);
			for (Object[] a : deferredHudDecor) {
				if (!show) break;
				int ix = (Integer) a[2], iy = (Integer) a[3];
				if (clipHudUnderButtons() && isUnderElevatedTile(ix, iy, 16, 16)) continue;
				g.itemDecorations((net.minecraft.client.gui.Font) a[0], (net.minecraft.world.item.ItemStack) a[1], (Integer) a[2], (Integer) a[3]);
			}
			// Ring only over our glass bar, never over the vanilla bar
			if (show && hudTilesFrom >= 0) drawHotbarSelection(g);
		} finally {
			inForegroundReplay = old;
			deferredHudItems.clear();
			deferredHudDecor.clear();
		}
	}
	public static void replayDeferredSprites(net.minecraft.client.gui.GuiGraphicsExtractor g) {
		if (deferredSprites.isEmpty() && deferredBlits.isEmpty() && deferredBlits9.isEmpty() && deferredTabIcons.isEmpty() && deferredTexts.isEmpty() && deferredSeqTexts.isEmpty() && pendingMapDraw == null && deferredHudItems.isEmpty() && deferredHudDecor.isEmpty()) return;
		boolean canFg = deferForeground();
		boolean canHud = !deferredHudItems.isEmpty() || !deferredHudDecor.isEmpty();
		boolean canText = enabled;
		if (!canFg && !canHud && !canText) {
			deferredSprites.clear(); deferredBlits.clear(); deferredBlits9.clear(); deferredTabIcons.clear(); deferredTexts.clear();
			deferredSeqTexts.clear(); pendingMapDraw = null;
			deferredHudItems.clear(); deferredHudDecor.clear();
			return;
		}
		if (!canFg && !canHud) {
			deferredSprites.clear(); deferredBlits.clear(); deferredBlits9.clear(); deferredTabIcons.clear();
			pendingMapDraw = null;
			deferredHudItems.clear(); deferredHudDecor.clear();
		}
		boolean old = inForegroundReplay;
		inForegroundReplay = true;
		try {
			if (canFg && (!deferredSprites.isEmpty() || !deferredBlits.isEmpty())) LiquidumLayers.beginInventoryObjects(g);
			if (canFg) {
			for (Object[] a : deferredSprites) {
				g.blitSprite((com.mojang.blaze3d.pipeline.RenderPipeline) a[0], (net.minecraft.resources.Identifier) a[1], (Integer) a[2], (Integer) a[3], (Integer) a[4], (Integer) a[5]);
			}
			for (Object[] a : deferredBlits) {
				g.blit((com.mojang.blaze3d.pipeline.RenderPipeline) a[0], (net.minecraft.resources.Identifier) a[1], (Integer) a[2], (Integer) a[3], (Float) a[4], (Float) a[5], (Integer) a[6], (Integer) a[7], (Integer) a[8], (Integer) a[9]);
			}
			for (Object[] a : deferredBlits9) {
				g.blitSprite((com.mojang.blaze3d.pipeline.RenderPipeline) a[0], (net.minecraft.resources.Identifier) a[1], (Integer) a[2], (Integer) a[3], (Integer) a[4], (Integer) a[5], (Integer) a[6], (Integer) a[7], (Integer) a[8], (Integer) a[9]);
			}
			if (pendingMapDraw != null) {
				g.map((net.minecraft.client.renderer.state.MapRenderState) pendingMapDraw);
			}
			}
			if (canFg && !deferredTabIcons.isEmpty()) LiquidumLayers.beginItems(g);
			if (canFg) {
			for (Object[] a : deferredTabIcons) {
				g.item((net.minecraft.world.item.ItemStack) a[0], (Integer) a[1], (Integer) a[2], (Integer) a[3]);
			}
			}
		if (canHud && hotbarCollapse < 0.5f && !deferredHudItems.isEmpty()) LiquidumLayers.beginItems(g);
		for (Object[] a : deferredHudItems) {
			if (hotbarCollapse >= 0.5f) break;
			int ix = (Integer) a[2], iy = (Integer) a[3];
			if (clipHudUnderButtons() && isUnderElevatedTile(ix, iy, 16, 16)) continue;
			g.item((net.minecraft.world.entity.LivingEntity) a[0], (net.minecraft.world.item.ItemStack) a[1], (Integer) a[2], (Integer) a[3], (Integer) a[4]);
		}
		if (canText && (!deferredTexts.isEmpty() || !deferredSeqTexts.isEmpty())) LiquidumLayers.beginText(g);
		if (canText) {
			for (Object[] a : deferredTexts) {
				g.text((net.minecraft.client.gui.Font) a[0], (net.minecraft.network.chat.Component) a[1], (Integer) a[2], (Integer) a[3], (Integer) a[4], (Boolean) a[5]);
			}
			for (Object[] a : deferredSeqTexts) {
				g.text((net.minecraft.client.gui.Font) a[0], (net.minecraft.util.FormattedCharSequence) a[1], (Integer) a[2], (Integer) a[3], (Integer) a[4], (Boolean) a[5]);
			}
			}
		if (canHud && hotbarCollapse < 0.5f && !deferredHudDecor.isEmpty()) LiquidumLayers.beginText(g);
		for (Object[] a : deferredHudDecor) {
			if (hotbarCollapse >= 0.5f) break;
			int ix = (Integer) a[2], iy = (Integer) a[3];
			if (clipHudUnderButtons() && isUnderElevatedTile(ix, iy, 16, 16)) continue;
			g.itemDecorations((net.minecraft.client.gui.Font) a[0], (net.minecraft.world.item.ItemStack) a[1], (Integer) a[2], (Integer) a[3]);
		}
		} finally {
			inForegroundReplay = old;
			deferredSprites.clear();
			deferredBlits.clear();
			deferredBlits9.clear();
			deferredTabIcons.clear();
			deferredTexts.clear();
			deferredSeqTexts.clear();
			pendingMapDraw = null;
			deferredHudItems.clear();
			deferredHudDecor.clear();
			iconKeyCount = 0;
		}
	}

	private static final float[] wells = new float[MAX_WELLS * 12];
	private static int wellCount = 0;
	
	public static boolean submitGridWell(int x, int y, int cellW, int cellH,
	                                  int pitchX, int pitchY, int cols, int rows, int hover) {
		if (wellCount >= MAX_WELLS || cols <= 0 || rows <= 0) return false;
		if (wellCellCount + cols * rows > wellCells.length / 4) {
			// Not enough parallax slots — skip this well entirely to keep UBO/wellCells in sync
			return false;
		}
		int o = wellCount++ * 12;
		wells[o] = x; wells[o + 1] = y;
		wells[o + 2] = cellW; wells[o + 3] = cellH;
		wells[o + 4] = pitchX; wells[o + 5] = pitchY;
		wells[o + 6] = cols; wells[o + 7] = rows;
		wells[o + 8] = hover < 0 ? -1 : (hover % cols);
		wells[o + 9] = hover < 0 ? -1 : (hover / cols);
		// Java-side реестр ячеек для parallax/hover-запросов (в UBO не идёт).
		for (int r = 0; r < rows; r++) {
			for (int c = 0; c < cols; c++) {
				int b = wellCellCount * 4;
				wellCells[b] = x + c * pitchX;
				wellCells[b + 1] = y + r * pitchY;
				wellCells[b + 2] = cellW;
				wellCells[b + 3] = cellH;
				wellCellCount++;
			}
		}
		return true;
	}

	/** Ячейки GridWell для itemParallax: {x,y,w,h} на ячейку. S.13 512 для больших креатив-сеток. */
	private static final int[] wellCells = new int[512 * 4];
	private static int wellCellCount = 0;

	// Furnace FX: flame spill + ProcessChannel (координаты gui px).
	private static float fxFlameX, fxFlameY, fxLit = -1f;
	private static float fxChX0, fxChY, fxChLen, fxCook = -1f;

	/** Семантический адаптер печки: реальные значения из AbstractFurnaceMenu. */
	public static void setFurnaceFx(float flameX, float flameY, float litProgress,
	                                float chX0, float chY, float chLen, float cookProgress) {
		fxFlameX = flameX; fxFlameY = flameY; fxLit = litProgress;
		fxChX0 = chX0; fxChY = chY; fxChLen = chLen; fxCook = cookProgress;
	}

	// Appearance states of one material, AUTO reads player surroundings
	private static final int APPEAR_AUTO = 0, APPEAR_LIGHT = 1, APPEAR_DARK = 2;
	private static int appearanceMode = APPEAR_AUTO;
	private static float darkSmooth = 0f;         // сглаженный 0..1
	private static long darkLastNanos = 0L;
	private static float tintR = 0.62f, tintG = 0.78f, tintB = 1.0f, tintStrength = 0f;

	private static float envDarkness(Minecraft mc) {
		if (mc.level == null || mc.player == null) return darkSmooth;
		float d = mc.level.getSkyDarken() / 15f * 0.85f;
		var key = mc.level.dimension();
		if (key == net.minecraft.world.level.Level.NETHER) d = Math.max(d, 0.60f);
		else if (key == net.minecraft.world.level.Level.END) d = Math.max(d, 0.70f);
		// Локальный свет (факелы/ламы) осветляет окружение — стекло светлее.
		int local = mc.level.getMaxLocalRawBrightness(mc.player.blockPosition());
		d *= 1f - 0.55f * (local / 15f);
		return Math.max(0f, Math.min(1f, d));
	}

	private static void updateAppearance(Minecraft mc) {
		float target = switch (appearanceMode) {
			case APPEAR_LIGHT -> 0f;
			case APPEAR_DARK -> 1f;
			default -> envDarkness(mc);
		};
		long now = System.nanoTime();
		if (darkLastNanos == 0L) {
			darkSmooth = target;
		} else {
			float dt = Math.min((now - darkLastNanos) / 1e9f, 0.1f);
			darkSmooth += (target - darkSmooth) * (1f - (float) Math.exp(-2.5 * dt));
		}
		darkLastNanos = now;
	}
	private static long lightDirNanos = 0L;
	private static void updateLightDir(Minecraft mc) {
		// Manual light wins, Lab sculpts the rim without the world
		if (LiquidumDebugState.lightManual) {
			lightDirX = (float) Math.cos(LiquidumDebugState.lightAngle);
			lightDirY = (float) Math.sin(LiquidumDebugState.lightAngle);
			lightIntensity = Math.max(0f, Math.min(1f, LiquidumDebugState.lightLevel));
			lightConfidence = 1f;
			smoothSun(lightIntensity, lightIntensity * 0.98f, lightIntensity * 0.94f);
			return;
		}
		if (mc.level == null) { lightDirX = 0f; lightDirY = 1f; lightIntensity = 0.18f; lightConfidence = 0.28f; worldLightX = 0f; worldLightY = 1f; worldLightZ = 0f; worldVelX=0f; worldVelY=0f; worldVelZ=0f; filteredWx=0f; filteredWy=1f; filteredWz=0f; smoothSun(0.50f, 0.58f, 0.75f); return; }
		long now = System.nanoTime();
		float dt = lightDirNanos == 0 ? 0.016f : Math.min((now - lightDirNanos)/1e9f, 1f/30f);
		lightDirNanos = now;
		float blockWeight = 0f, skyWeight = 0f;
		float targetInt = 0f;
		float rawWx = 0f, rawWy = 1f, rawWz = 0f;
		float blockLenN = 0f, skyLenN = 0f;
		float confRaw = 0.28f;
		try {
			var player = mc.player;
			var level = mc.level;
			if (player != null) {
				var pos = player.blockPosition();
				java.util.function.Function<net.minecraft.core.BlockPos, Float> sampleBlock = p2 -> {
					try { return (float) level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, p2) / 15f; }
					catch (Exception ex) { try { return (float) level.getMaxLocalRawBrightness(p2) / 15f; } catch (Exception e2) { return 0f; } }
				};
				java.util.function.Function<net.minecraft.core.BlockPos, Float> sampleSky = p2 -> {
					try { return (float) level.getBrightness(net.minecraft.world.level.LightLayer.SKY, p2) / 15f; }
					catch (Exception ex) { return 0f; }
				};
				float bx1 = sampleBlock.apply(pos.east()), bx0 = sampleBlock.apply(pos.west());
				float by1 = sampleBlock.apply(pos.above()), by0 = sampleBlock.apply(pos.below());
				float bz1 = sampleBlock.apply(pos.south()), bz0 = sampleBlock.apply(pos.north());
				float sx1 = sampleSky.apply(pos.east()), sx0 = sampleSky.apply(pos.west());
				float sy1 = sampleSky.apply(pos.above()), sy0 = sampleSky.apply(pos.below());
				float sz1 = sampleSky.apply(pos.south()), sz0 = sampleSky.apply(pos.north());
				float gbx = bx1 - bx0, gby = by1 - by0, gbz = bz1 - bz0;
				float gsx = sx1 - sx0, gsy = sy1 - sy0, gsz = sz1 - sz0;
				float bx1_2 = sampleBlock.apply(pos.east(2)), bx0_2 = sampleBlock.apply(pos.west(2));
				float by1_2 = sampleBlock.apply(pos.above(2)), by0_2 = sampleBlock.apply(pos.below(2));
				float bz1_2 = sampleBlock.apply(pos.south(2)), bz0_2 = sampleBlock.apply(pos.north(2));
				gbx = gbx * 0.65f + (bx1_2 - bx0_2) * 0.35f;
				gby = gby * 0.65f + (by1_2 - by0_2) * 0.35f;
				gbz = gbz * 0.65f + (bz1_2 - bz0_2) * 0.35f;
				gsx = gsx * 0.65f + (sampleSky.apply(pos.east(2)) - sampleSky.apply(pos.west(2))) * 0.35f;
				gsy = gsy * 0.65f + (sampleSky.apply(pos.above(2)) - sampleSky.apply(pos.below(2))) * 0.35f;
				gsz = gsz * 0.65f + (sampleSky.apply(pos.south(2)) - sampleSky.apply(pos.north(2))) * 0.35f;
				blockLenN = (float)Math.sqrt(gbx*gbx + gby*gby + gbz*gbz);
				skyLenN = (float)Math.sqrt(gsx*gsx + gsy*gsy + gsz*gsz);
				float skyDark = level.getSkyDarken() / 15f;
				float skyFactor = 1f - skyDark;
				float sunAngle = 0f;
				try {
					try { var m = level.getClass().getMethod("getSunAngle", float.class); sunAngle = ((Number)m.invoke(level, 0f)).floatValue() * 2f * (float)Math.PI; }
					catch (Exception e2) { var m2 = level.getClass().getMethod("getTimeOfDay", long.class); sunAngle = ((Number)m2.invoke(level, 0L)).floatValue() / 24000f * 2f * (float)Math.PI; }
				} catch (Exception ignored) {}
				float sunX = (float)Math.cos(sunAngle) * 0.3f;
				float sunY = (float)Math.sin(sunAngle) * 0.9f + 0.3f;
				float sunZ = 0f;
				skyWeight = skyFactor * (0.7f + 0.3f * skyLenN);
				blockWeight = (1f - skyFactor * 0.6f) * (0.6f + 0.4f * blockLenN);
				if (level.dimension() == net.minecraft.world.level.Level.NETHER || level.dimension() == net.minecraft.world.level.Level.END) {
					skyWeight = 0f; blockWeight = 1f; sunX = 0f; sunY = -1f; sunZ = 0f;
				}
				float wx = sunX * skyWeight + (blockLenN > 1e-4f ? gbx / blockLenN * blockWeight : 0f);
				float wy = sunY * skyWeight + (blockLenN > 1e-4f ? gby / blockLenN * blockWeight : 0f);
				float wz = sunZ * skyWeight + (blockLenN > 1e-4f ? gbz / blockLenN * blockWeight : 0f);
				float wlen = (float)Math.sqrt(wx*wx + wy*wy + wz*wz);
				if (wlen > 1e-4f) { wx/=wlen; wy/=wlen; wz/=wlen; }
				else { wx = worldLightX; wy = worldLightY; wz = worldLightZ; }
				// 80-120ms low-pass before spring (ChatGPT) — smooth discrete 0-15 steps
			float lpA = 1f - (float)Math.exp(-dt / 0.10f);
			filteredWx += (wx - filteredWx) * lpA;
			filteredWy += (wy - filteredWy) * lpA;
			filteredWz += (wz - filteredWz) * lpA;
			rawWx = filteredWx; rawWy = filteredWy; rawWz = filteredWz;
				float gradLen = (float)Math.sqrt(gbx*gbx+gby*gby+gbz*gbz + gsx*gsx+gsy*gsy+gsz*gsz);
				float c = Math.min(1f, gradLen * 2.5f);
				c = Math.max(0.08f, c);
				if (skyWeight < 0.15f && blockLenN < 0.08f) c = Math.max(0.06f, c * 0.45f);
				confRaw = c;
				float skyInt = 1f - skyDark;
				float localBlock = sampleBlock.apply(pos);
				targetInt = 0.08f + 0.92f * Math.max(skyInt, localBlock);
				targetInt *= (0.35f + 0.65f * c);
				updateSunTarget(level, sunX, sunY, skyWeight, blockWeight, localBlock, targetInt);
			} else {
				rawWx = 0f; rawWy = 1f; rawWz = 0f;
				smoothSun(0.55f, 0.62f, 0.80f);
			}
		} catch (Exception e) {
			rawWx = 0f; rawWy = 1f; rawWz = 0f;
			targetInt = 0.08f + 0.92f * (1f - mc.level.getSkyDarken()/15f) * 0.9f;
			confRaw = 0.28f;
			smoothSun(0.70f, 0.75f, 0.85f);
		}
		boolean weak = blockLenN < 0.08f && skyLenN < 0.07f;
		if (!weak) {
			float omega = 10.0f, zeta = 0.98f;
			float ax = omega*omega*(rawWx - worldLightX) - 2f*zeta*omega*worldVelX;
			float ay = omega*omega*(rawWy - worldLightY) - 2f*zeta*omega*worldVelY;
			float az = omega*omega*(rawWz - worldLightZ) - 2f*zeta*omega*worldVelZ;
			worldVelX += ax * dt; worldVelY += ay * dt; worldVelZ += az * dt;
			worldLightX += worldVelX * dt; worldLightY += worldVelY * dt; worldLightZ += worldVelZ * dt;
			float wl = (float)Math.sqrt(worldLightX*worldLightX + worldLightY*worldLightY + worldLightZ*worldLightZ);
			if (wl > 1e-4f) { worldLightX/=wl; worldLightY/=wl; worldLightZ/=wl; }
			if (Float.isNaN(worldLightX) || Float.isInfinite(worldLightX)) { worldLightX=rawWx; worldLightY=rawWy; worldLightZ=rawWz; worldVelX=0; worldVelY=0; worldVelZ=0; }
		} else {
			worldVelX *= 0.92f; worldVelY *= 0.92f; worldVelZ *= 0.92f;
		}
		{
			float omegaI = 10.0f, zetaI = 0.98f;
			float ai = omegaI*omegaI*(targetInt - lightIntensity) - 2f*zetaI*omegaI*lightIntensityVel;
			lightIntensityVel += ai * dt;
			lightIntensity += lightIntensityVel * dt;
			lightIntensity = Math.max(0f, Math.min(1f, lightIntensity));
			if (Float.isNaN(lightIntensity)) { lightIntensity = targetInt; lightIntensityVel=0; }
		}
		lightConfidence = confRaw;
		float tx = 0f, ty = 1f;
		try {
			var cam = mc.gameRenderer.mainCamera();
			if (cam != null) {
				org.joml.Vector3f right = new org.joml.Vector3f(1,0,0), up = new org.joml.Vector3f(0,1,0);
				try { Object r = cam.getClass().getMethod("getRightVector").invoke(cam); if (r instanceof org.joml.Vector3f) right=(org.joml.Vector3f)r; else if (r instanceof org.joml.Vector3fc) right=new org.joml.Vector3f((org.joml.Vector3fc)r);} catch(Exception e){ try{Object r2=cam.getClass().getMethod("rightVector").invoke(cam); if(r2 instanceof org.joml.Vector3f) right=(org.joml.Vector3f)r2;}catch(Exception ex){}}
				try { Object u = cam.getClass().getMethod("getUpVector").invoke(cam); if (u instanceof org.joml.Vector3f) up=(org.joml.Vector3f)u; else if (u instanceof org.joml.Vector3fc) up=new org.joml.Vector3f((org.joml.Vector3fc)u);} catch(Exception e){ try{Object u2=cam.getClass().getMethod("upVector").invoke(cam); if(u2 instanceof org.joml.Vector3f) up=(org.joml.Vector3f)u2;}catch(Exception ex){} try{Object f=cam.getClass().getMethod("forwardVector").invoke(cam); if(f instanceof org.joml.Vector3f){var fwd=(org.joml.Vector3f)f; right=new org.joml.Vector3f(fwd).cross(new org.joml.Vector3f(0,1,0)).normalize(); up=new org.joml.Vector3f(right).cross(fwd).normalize();}}catch(Exception ex2){}}
				tx = worldLightX * right.x() + worldLightY * right.y() + worldLightZ * right.z();
				ty = worldLightX * up.x() + worldLightY * up.y() + worldLightZ * up.z();
				float tlen=(float)Math.sqrt(tx*tx+ty*ty); if(tlen>1e-4f){tx/=tlen; ty/=tlen;}
			}
		} catch(Exception ignored){ tx=worldLightX; ty=worldLightY; }
		lightDirX = tx; lightDirY = ty;
		if (Float.isNaN(lightDirX)||Float.isInfinite(lightDirX)) { lightDirX=0f; lightDirY=1f; }
	}

	private static float clamp01(float v) {
		return v < 0f ? 0f : (v > 1f ? 1f : v);
	}

	/** Exponential smoothing for the WOW sun colour (no popping at sunset). */
	private static void smoothSun(float r, float g, float b) {
		long now = System.nanoTime();
		if (sunNanos == 0L) {
			sunR = r; sunG = g; sunB = b; sunNanos = now;
			return;
		}
		float dt = Math.min((now - sunNanos) / 1e9f, 0.1f);
		sunNanos = now;
		float k = 1f - (float) Math.exp(-3.0 * dt);
		sunR += (r - sunR) * k;
		sunG += (g - sunG) * k;
		sunB += (b - sunB) * k;
	}

	/**
	 * WOW sun colour target: noon white → horizon orange → moonlight blue,
	 * torch/lava warmth where block light dominates, Nether red / End violet.
	 * Brightness follows targetInt so the specular dies at night instead of
	 * glowing white in the dark.
	 */
	private static void updateSunTarget(Object level, float sunX, float sunY,
										float skyWeight, float blockWeight,
										float localBlock, float targetInt) {
		boolean nether = false, end = false;
		try {
			var dim = ((net.minecraft.world.level.Level) level).dimension();
			nether = dim == net.minecraft.world.level.Level.NETHER;
			end = dim == net.minecraft.world.level.Level.END;
		} catch (Exception ignored) {}
		float tr, tg, tb;
		if (nether) {
			tr = 1f; tg = 0.42f; tb = 0.22f;
		} else if (end) {
			tr = 0.72f; tg = 0.58f; tb = 1f;
		} else {
			float elev = Math.max(-1f, Math.min(1f, sunY));
			float dayT = clamp01((elev + 0.12f) / 0.6f);
			float lowT = clamp01(1f - Math.abs(elev - 0.12f) / 0.35f);
			tr = 0.55f + 0.45f * dayT;
			tg = 0.65f + 0.33f * dayT;
			tb = 0.90f + 0.04f * dayT;
			tr += (1f - tr) * lowT * 0.9f;
			tg *= 1f - lowT * 0.35f;
			tb *= 1f - lowT * 0.62f;
			float bw = blockWeight / (blockWeight + skyWeight + 1e-4f);
			float torch = bw * clamp01(localBlock * 1.2f);
			tr += (1f - tr) * torch * 0.85f;
			tg += (0.60f - tg) * torch * 0.7f;
			tb += (0.30f - tb) * torch * 0.7f;
		}
		float lum = 0.25f + 0.75f * clamp01(targetInt);
		smoothSun(tr * lum, tg * lum, tb * lum);
	}

	/** Площадь panel-rect текущего кадра (для cap групп semantic adapter). */
	/** Площадь panel-rect текущего кадра (для cap групп semantic adapter). */
	public static int panelArea() {
		return hudPanelArea;
	}

	// L4: models extracted pre-blur get cancelled and replayed sharp post-glass

	/** Откладывать ли foreground-модели: только когда стекло реально
	 *  заменяет фон контейнерного экрана. Во время replay — НИКОГДА
	 *  (иначе mixin отменяет собственное переигрывание). §B: для креатива
	 *  проверяем inventorySlotsGlass, а не containerGlass, иначе нижние табы
	 *  уходили под стекло (мылились). */
	public static boolean deferForeground() {
		if (inForegroundReplay) return false;
		if (!enabled) return false;
		var s = Minecraft.getInstance().gui.screen();
		if (!(s instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen)) return false;
		// Per-screen glass gate (§T P1 изоляция): creative/inventory use inventorySlotsGlass
		if (s instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen
			|| s instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen) {
			return inventorySlotsGlass;
		}
		return slotsGlass;
	}

	private static Object[] pendingEntityDraw;
	private static Object[] pendingBookDraw;
	private static Object[] pendingBannerDraw;

	/** Selection/hover highlight (gui px, centre) for the FOREGROUND ring that
	 *  replaces the cancelled vanilla slot highlight (P0 — stays aligned with
	 *  items because it is drawn above the glass from real slot geometry). */
	private static int selHX = -1, selHY = -1, selSX = -1, selSY = -1;
	public static void setSelectionHighlight(int hx, int hy, int sx, int sy) {
		selHX = hx; selHY = hy; selSX = sx; selSY = sy;
	}
	/** Vanilla slot centres (gui px) captured for the geometry debug overlay. */
	private static int[] debugSlotCentres = new int[0];
	public static void setDebugSlotCentres(int[] centres) { debugSlotCentres = centres; }

	/**
	 * RE-ENTRANCY GUARD (P0 fix): replay идёт через тот же метод
	 * GuiGraphicsExtractor.entity(...) — без guard'а mixin перехватывал
	 * собственный replay и отменял его, поэтому модели ПРОПАДАЛИ полностью
	 * (cancel срабатывал дважды, а рисование — ни разу).
	 */
	private static boolean inForegroundReplay = false;

	public static void captureEntity(Object state, float scale, Object pivot,
	                                 Object pivotRot, Object animRot, int x, int y, int w, int h) {
		pendingEntityDraw = new Object[] { state, scale, pivot, pivotRot, animRot, x, y, w, h };
	}

	public static void captureBook(Object model, Object texture,
	                               float f1, float f2, float f3, int x, int y, int w, int h) {
		pendingBookDraw = new Object[] { model, texture, f1, f2, f3, x, y, w, h };
	}

	public static void captureBanner(Object model, Object dye, Object patterns,
	                                 int x, int y, int w, int h) {
		pendingBannerDraw = new Object[] { model, dye, patterns, x, y, w, h };
	}

	/** Переиграть отложенные модели в widget-фазе (вызывается из ScreenMixin
	 *  на extractRenderState TAIL — после blur-маркера). */
	public static void replayForeground(net.minecraft.client.gui.GuiGraphicsExtractor g) {
		if (inForegroundReplay) return;
		boolean any = pendingEntityDraw != null || pendingBookDraw != null || pendingBannerDraw != null;
		boolean sel = deferForeground() && (selHX >= 0 || selSX >= 0);
		boolean dbg = deferForeground() && LiquidumDebugState.debugGeometry && debugSlotCentres.length >= 2;
		if (!any && !sel && !dbg) return;
		if (!deferForeground()) {   // мод выключен — отложенные вызовы отбрасываем
			pendingEntityDraw = null; pendingBookDraw = null; pendingBannerDraw = null;
			return;
		}
		inForegroundReplay = true;
		try {
			if (pendingEntityDraw != null) {
				Object[] a = pendingEntityDraw;
				g.entity((net.minecraft.client.renderer.entity.state.EntityRenderState) a[0],
					(Float) a[1], (org.joml.Vector3fc) a[2],
					(org.joml.Quaternionfc) a[3], (org.joml.Quaternionfc) a[4],
					(Integer) a[5], (Integer) a[6], (Integer) a[7], (Integer) a[8]);
			}
			if (pendingBookDraw != null) {
				Object[] a = pendingBookDraw;
				g.book((net.minecraft.client.model.object.book.BookModel) a[0],
					(Identifier) a[1], (Float) a[2], (Float) a[3], (Float) a[4],
					(Integer) a[5], (Integer) a[6], (Integer) a[7], (Integer) a[8]);
			}
			if (pendingBannerDraw != null) {
				Object[] a = pendingBannerDraw;
				g.bannerPattern((net.minecraft.client.model.object.banner.BannerFlagModel) a[0],
					(net.minecraft.world.item.DyeColor) a[1],
					(net.minecraft.world.level.block.entity.BannerPatternLayers) a[2],
					(Integer) a[3], (Integer) a[4], (Integer) a[5], (Integer) a[6]);
			}
			if (sel) drawSelectionRing(g);
			if (dbg) drawGeometryDebug(g);
		} catch (Exception e) {
			if (DEBUG) LiquidumMod.LOGGER.warn("[glass] foreground replay failed: {}", e.toString());
		} finally {
			inForegroundReplay = false;
			pendingEntityDraw = null;
			pendingBookDraw = null;
			pendingBannerDraw = null;
		}
	}

	/** Sharp selection/hover ring drawn ABOVE the glass from real slot geometry
	 *  (P0): it aligns with the replayed-sharp item, replacing the cancelled
	 *  vanilla background highlight that refraction used to push off-slot. */
	private static void drawSelectionRing(net.minecraft.client.gui.GuiGraphicsExtractor g) {
		int c = 0xFF5BD0FF;                 // cool cyan — clearly "glass control"
		int t = 2;                          // thickness (gui px)
		if (selHX >= 0) ring(g, selHX - 10, selHY - 10, 20, 20, t, c);
		if (selSX >= 0) ring(g, selSX - 10, selSY - 10, 20, 20, t, 0xFFFFD24A);
	}

	private static void ring(net.minecraft.client.gui.GuiGraphicsExtractor g, int x, int y, int w, int h, int t, int color) {
		g.fill(RenderPipelines.GUI, x, y, x + w, y + t, color);
		g.fill(RenderPipelines.GUI, x, y + h - t, x + w, y + h, color);
		g.fill(RenderPipelines.GUI, x, y, x + t, y + h, color);
		g.fill(RenderPipelines.GUI, x + w - t, y, x + w, y + h, color);
	}

	/** GEOMETRY DEBUG (P0): red cross = vanilla Slot centre, green cross =
	 *  GridWell shader cell centre. If they sit on top of each other, the
	 *  coordinate systems match. */
	private static void drawGeometryDebug(net.minecraft.client.gui.GuiGraphicsExtractor g) {
		int s = 7;
		for (int i = 0; i + 1 < debugSlotCentres.length; i += 2) {
			int cx = debugSlotCentres[i], cy = debugSlotCentres[i + 1];
			cross(g, cx, cy, s, 0xFFFF3B30);
		}
		for (int w = 0; w < wellCount; w++) {
			int o = w * 12;
			float ox = wells[o], oy = wells[o + 1];
			int cols = (int) wells[o + 6], rows = (int) wells[o + 7];
			float px = wells[o + 4], py = wells[o + 5];
			for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
				int cx = (int) (ox + c * px);
				int cy = (int) (oy + r * py);
				cross(g, cx, cy, s, 0xFF36E036);
			}
		}
	}

	private static void cross(net.minecraft.client.gui.GuiGraphicsExtractor g, int cx, int cy, int s, int color) {
		g.fill(RenderPipelines.GUI, cx - s, cy - 1, cx + s, cy + 1, color);
		g.fill(RenderPipelines.GUI, cx - 1, cy - s, cx + 1, cy + s, color);
	}


	// Home strip morph: 0 full bar, 1 iPhone strip, smoothed every submit
	private static float hotbarCollapse = 0f;
	private static long hotbarCollapseNanos = 0L;

	// Pause and Done sheets collapse the bar, game and chat keep it full
	private static boolean hotbarMinimizedTarget() {
		var s = Minecraft.getInstance().gui.screen();
		if (s instanceof net.minecraft.client.gui.screens.PauseScreen) return true;
		if (s instanceof net.minecraft.client.gui.screens.options.OptionsScreen) return true;
		return s instanceof net.minecraft.client.gui.screens.options.OptionsSubScreen;
	}

	/** Vanilla hotbar geometry: solid 182x22 panel (same footprint as vanilla),
	 *  selected cell reads as a slot well, offhand tile on the LEFT.
	 *  §12-14: no hotbar in Spectator/MainMenu/HideGUI, Creative keeps it (§1). */
	public static void submitHotbar(int guiW, int guiH, int selSlot, boolean hasOffhand) {
		if (!replaceHotbarBackground()) return;
		if (!shouldRenderHotbarEdge()) return;
		var mcH = Minecraft.getInstance();
		if (mcH.player != null && mcH.player.isSpectator()) return; // §13 (Creative hotbar остаётся per P0 §1)
		if (mcH.level == null) return; // §14
		long now = System.nanoTime();
		float want = hotbarMinimizedTarget() ? 1f : 0f;
		if (hotbarCollapseNanos == 0L) hotbarCollapse = want;
		else {
			float dt = Math.min((now - hotbarCollapseNanos) / 1e9f, 0.1f);
			hotbarCollapse += (want - hotbarCollapse) * (1f - (float) Math.exp(-12.0 * dt));
			if (Math.abs(want - hotbarCollapse) < 0.002f) hotbarCollapse = want;
		}
		hotbarCollapseNanos = now;
		float k = hotbarCollapse;
		pendingGuiW = Math.max(1, guiW);
		pendingGuiH = Math.max(1, guiH);
		if (hudTilesFrom < 0) hudTilesFrom = pendingCount;
		float fx = guiW / 2 - 91, fy = guiH - 22;
		float sx = guiW / 2f - 55, sy = guiH - 9;
		int x0 = Math.round(fx + (sx - fx) * k);
		int y0 = Math.round(fy + (sy - fy) * k);
		int bw = Math.round(182 + (110 - 182) * k);
		int bh = Math.max(2, Math.round(22 + (4 - 22) * k));
		boolean full = k < 0.5f;
		if (full && hasOffhand) {
			appendRect(Math.round(fx) - 30, Math.round(fy), 24, 22, MAT_SLOT);            // offhand: LEFT, centred on the item
		}
		// Hotbar shares the dock mask system, submitted dense for now
		appendRect(x0, y0, bw, bh, full ? MAT_DENSE : MAT_ACTIVE);
		hudSelTargetX = -1f;
		hudSelCenterYGui = y0 + 11;
		// Selected cell is a real slot well, it covers the bar body
		if (full && selSlot >= 0 && selSlot < 9) {
			submitSlotWell(Math.round(fx) + 2 + selSlot * 20, Math.round(fy) + 2);
		}
	}

	// Soft white frame for the selected hotbar cell White ring glide state, exponential smoothing like hudSelX
	private static float selRingX = -1f;
	private static long selRingNanos = 0L;

	public static void drawHotbarSelection(net.minecraft.client.gui.GuiGraphicsExtractor g) {
		if (!replaceHotbarBackground()) return;
		if (hotbarCollapse >= 0.5f) return;
		var mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || mc.player.isSpectator()) return;
		int sel = mc.player.getInventory().getSelectedSlot();
		if (sel < 0 || sel > 8) return;
		float tx = g.guiWidth() / 2 - 89 + sel * 20;
		long now = System.nanoTime();
		if (selRingX < 0 || selRingNanos == 0L) selRingX = tx;
		else {
			float dt = Math.min((now - selRingNanos) / 1e9f, 0.1f);
			selRingX += (tx - selRingX) * (1f - (float) Math.exp(-14.0 * dt));
			if (Math.abs(tx - selRingX) < 0.05f) selRingX = tx;
		}
		selRingNanos = now;
		int cx = Math.round(selRingX);
		int cy = g.guiHeight() - 22 + 2;
		ring(g, cx - 1, cy - 1, 20, 20, 1, 0x88FFFFFF);
		ring(g, cx, cy, 18, 18, 1, 0xFFFFFFFF);
	}

	private static boolean isHideGui() {
		try {
			var mc = Minecraft.getInstance();
			var f = mc.options.getClass().getField("hideGui");
			Object v = f.get(mc.options);
			if (v instanceof Boolean) return (Boolean) v;
			try { return (Boolean) v.getClass().getMethod("get").invoke(v); } catch (Exception e) { return false; }
		} catch (Exception e) { return false; }
	}
	private static boolean shouldRenderHud() {
		var mc = Minecraft.getInstance();
		if (mc.level == null) return false;
		if (isHideGui()) return false;
		var p = mc.player;
		if (p == null) return false;
		var s = mc.gui.screen();
		if (s instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen) return false;
		return true;
	}

	public static boolean shouldRenderHotbarEdge() {
		var mc = Minecraft.getInstance();
		if (mc.level == null) return false;
		if (isHideGui()) return false;
		var p = mc.player;
		if (p == null) return false;
		if (p.isSpectator()) return false;
		return true;
	}

	public static void submitLuminanceDock(int guiW, int guiH) {
		if (!luminanceDockEnabled || !enabled) return;
		if (!shouldRenderHud()) return;
		var mc = Minecraft.getInstance();
		var p = mc.player;
		if (p == null) return;
		// §T P1 изоляция: каждый Dock-элемент — свой toggle, без кросс-поломок как у ReGlass #9
		if (com.liquidum.client.compat.LiquidumOptOut.isOptedOut(mc.gui.screen())) return;
		boolean isCreative = p.isCreative();
		boolean isSpectator = p.isSpectator();
		if (isSpectator) return; // §13
		pendingGuiW = Math.max(pendingGuiW, Math.max(1, guiW));
		pendingGuiH = Math.max(pendingGuiH, Math.max(1, guiH));
		if (hudTilesFrom < 0) hudTilesFrom = pendingCount;
		int hw = guiW / 2;
		int hh = guiH;
		var mc2 = Minecraft.getInstance();
		var p2 = mc2.player;
		if (p2 == null) return;
		boolean xpShow = !p2.isCreative() && !p2.isSpectator() && xpGlass
			&& (p2.totalExperience > 0 || p2.experienceLevel > 0 || p2.experienceProgress > 0);
		if (xpShow) {
			appendRect(hw - 92, hh - 30, 184, 6, MAT_DOCK);
		}
	}

	private static boolean luminanceDockEnabled = true;
	private static float dockPadding = 5f, dockOuterPad = 8f, dockCornerRadius = 9f, dockRefraction = 0.06f, dockDensity = 0.22f;
	private static float lightDirX = 0f, lightDirY = 1f, lightIntensity = 1f;
	private static float lightDirVelX = 0f, lightDirVelY = 0f, lightIntensityVel = 0f, lightConfidence = 1f;
	// world-space smoothed light (for spring, before screen projection)
	private static float worldLightX = 0f, worldLightY = 1f, worldLightZ = 0f;
	private static float filteredWx = 0f, filteredWy = 1f, filteredWz = 0f;
	private static float worldVelX = 0f, worldVelY = 0f, worldVelZ = 0f;
	// WOW sun colour (linear-ish 0..1), exponentially smoothed like darkSmooth. Noon white / sunset orange / torch warm / Nether red / End violet.
	private static float sunR = 1f, sunG = 0.98f, sunB = 0.94f;
	private static long sunNanos = 0L;

	private static float mainW() {
		Minecraft mc = Minecraft.getInstance();
		return mc.gameRenderer != null && mc.gameRenderer.mainRenderTarget() != null
			? mc.gameRenderer.mainRenderTarget().width : (float) pendingGuiW;
	}

	private static void appendRect(int x, int y, int w, int h, int mat) {
		appendRect(x, y, w, h, mat, 0f, 0f);
	}
	private static void appendRect(int x, int y, int w, int h, int mat, float elev) {
		appendRect(x, y, w, h, mat, elev, 0f);
	}
	private static void appendRect(int x, int y, int w, int h, int mat, float elev, float group) {
		appendRect(x, y, w, h, mat, elev, group, 0f);
	}
	private static void appendRect(int x, int y, int w, int h, int mat, float elev, float group, float shapeW) {
		if (pendingCount >= MAX_PANELS || w <= 0 || h <= 0) return;
		pendX[pendingCount] = x;
		pendY[pendingCount] = y;
		pendW[pendingCount] = w;
		pendH[pendingCount] = h;
		pendMat[pendingCount] = mat;
		pendElev[pendingCount] = elev;
		pendGrp[pendingCount] = group;
		pendShapeW[pendingCount] = semanticShape(mat, shapeW);
		pendingCount++;
	}
	// Semantic surfaces, never framebuffer-size auto-detect
	private static float semanticShape(int mat, float shapeW) {
		float sid = (float) Math.floor(shapeW + 0.0001);
		if (sid > 0.5 || shapeW - sid >= 0.25) return shapeW;
		if (mat == MAT_BASE || mat == MAT_COMPANION || mat == MAT_GROUP || mat == MAT_CARD || mat == MAT_POPUP) return 5f;
		return shapeW;
	}
	private static int debugCount = 0;

	private static boolean frameDone = false;

	// Widget rects collected during Screen.extractRenderState (GUI units), consumed at draw.
	private static final int[] pendX = new int[MAX_PANELS];
	private static final int[] pendY = new int[MAX_PANELS];
	private static final int[] pendW = new int[MAX_PANELS];
	private static final int[] pendH = new int[MAX_PANELS];
	private static final int[] pendMat = new int[MAX_PANELS];
	private static final float[] pendElev = new float[MAX_PANELS];
	private static final float[] pendGrp = new float[MAX_PANELS];
	private static final float[] pendShapeW = new float[MAX_PANELS];
	private static int pendingCount = 0;
	private static int pendingGuiW = 1;
	private static int pendingGuiH = 1;

	// Overlay content cutouts (gui px rects), TAIL run keeps them sharp
	private static final int MAX_CUTS = 16;
	private static final int[] cutRect = new int[MAX_CUTS * 4];
	private static int cutCount = 0;

	public static void submitOverlayCutout(int x, int y, int w, int h) {
		if (cutCount >= MAX_CUTS || w <= 0 || h <= 0) return;
		int b = cutCount * 4;
		cutRect[b] = x;
		cutRect[b + 1] = y;
		cutRect[b + 2] = w;
		cutRect[b + 3] = h;
		cutCount++;
	}

	// Left-aligned label stays sharp above popup glass
	public static void submitTextCutout(net.minecraft.client.gui.Font font, String text, int x, int y) {
		if (font == null || text == null || text.isEmpty()) return;
		submitOverlayCutout(x - 3, y - 3, font.width(text) + 6, font.lineHeight + 6);
	}

	// Centered label uses the same x0 math as centeredText
	public static void submitCenteredCutout(net.minecraft.client.gui.Font font, String text, int cx, int y) {
		if (font == null || text == null || text.isEmpty()) return;
		int w = font.width(text);
		submitOverlayCutout(cx - w / 2 - 3, y - 3, w + 6, font.lineHeight + 6);
	}

	// Tooltip box keeps its own sharp rect above popup glass
	public static void submitTooltipCutout(net.minecraft.client.gui.Font font,
	                                        java.util.List<net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent> lines,
	                                        int x, int y,
	                                        net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner positioner,
	                                        int guiW, int guiH) {
		if (font == null || lines == null || lines.isEmpty() || positioner == null) return;
		int w = 0;
		int h = lines.size() == 1 ? -2 : 0;
		for (var c : lines) {
			if (c == null) continue;
			w = Math.max(w, c.getWidth(font));
			h += c.getHeight(font);
		}
		if (w <= 0 || h <= 0) return;
		var pos = positioner.positionTooltip(guiW, guiH, x, y, w, h);
		submitOverlayCutout(pos.x() - 12, pos.y() - 12, w + 24, h + 24);
	}

	// Screens with custom extractRenderState bypass ScreenMixin, HUD sizes may be stale or never set (main menu)
	public static void setPendingGuiSize(int w, int h) {
		pendingGuiW = Math.max(1, w);
		pendingGuiH = Math.max(1, h);
	}

	// Post-glass vector replay: stashed popup rows drawn crisp after overlay
	private static final java.util.List<Object[]> popupReplayTexts = new java.util.ArrayList<>();
	private static final java.util.List<Object[]> popupReplayChecks = new java.util.ArrayList<>();

	public static boolean hasPopupReplay() {
		return !popupReplayTexts.isEmpty() || !popupReplayChecks.isEmpty();
	}

	// Draw index window for the late replay range, refreshed every frame
	public static int replayCap = Integer.MAX_VALUE;
	public static int replayStart = -1;

	// Widget copy cancelled at extract, replay owns the pixels
	public static boolean deferPopupReplay() {
		return enabled && hasPopupTiles();
	}

	public static void stashPopupText(net.minecraft.client.gui.Font font, String label,
	                                  int x, int y, int color, boolean shadow, org.joml.Matrix3x2f pose) {
		if (popupReplayTexts.size() >= 32 || font == null || label == null) return;
		net.minecraft.util.FormattedCharSequence seq = net.minecraft.locale.Language.getInstance()
			.getVisualOrder(net.minecraft.network.chat.FormattedText.of(label));
		popupReplayTexts.add(new Object[]{ font, seq, pose, x, y, color, shadow });
	}

	public static void stashPopupCheck(com.mojang.blaze3d.pipeline.RenderPipeline pipeline,
	                                   int qx, int qy, int color, org.joml.Matrix3x2f pose) {
		if (popupReplayChecks.size() >= 16 || pipeline == null) return;
		popupReplayChecks.add(new Object[]{ pipeline, pose, qx, qy + 3, qx + 3, qy + 5, color });
		popupReplayChecks.add(new Object[]{ pipeline, pose, qx + 3, qy + 1, qx + 9, qy + 3, color });
	}

	// Late overlay point owns these, returns replayed element count
	public static int submitPopupReplay(net.minecraft.client.renderer.state.gui.GuiRenderState state) {
		if (!hasPopupReplay()) return 0;
		state.nextStratum();
		int n = 0;
		for (Object[] r : popupReplayTexts) {
			final org.joml.Matrix3x2f pose = (org.joml.Matrix3x2f) r[2];
			var st = new net.minecraft.client.renderer.state.gui.GuiTextRenderState(
				(net.minecraft.client.gui.Font) r[0], (net.minecraft.util.FormattedCharSequence) r[1], pose,
				(Integer) r[3], (Integer) r[4], (Integer) r[5],
				0, (Boolean) r[6], false, null);
			var prepared = st.ensurePrepared();
			state.addText(st);
			// Glyph visit mirrors vanilla prepareText, lands in our stratum
			prepared.visit(new net.minecraft.client.gui.Font.GlyphVisitor() {
				@Override
				public void acceptRenderable(net.minecraft.client.gui.font.TextRenderable renderable) {
					state.addGlyphToCurrentLayer(new net.minecraft.client.renderer.state.gui.GlyphRenderState(pose, renderable, null));
				}
			});
			n++;
		}
		for (Object[] r : popupReplayChecks) {
			state.addGuiElement(new net.minecraft.client.renderer.state.gui.ColoredRectangleRenderState(
				(com.mojang.blaze3d.pipeline.RenderPipeline) r[0],
				net.minecraft.client.gui.render.TextureSetup.noTexture(),
				(org.joml.Matrix3x2f) r[1], (Integer) r[2], (Integer) r[3],
				(Integer) r[4], (Integer) r[5], (Integer) r[6], (Integer) r[6], null));
			n++;
		}
		return n;
	}
	// Button elevation: previous frame CONTROL/ACTIVE tiles, replay clips HUD items under them Wells and slots never stored, items belong inside them
	private static final int ELEV_MAX = 64;
	private static final int[] elevX = new int[ELEV_MAX];
	private static final int[] elevY = new int[ELEV_MAX];
	private static final int[] elevW = new int[ELEV_MAX];
	private static final int[] elevH = new int[ELEV_MAX];
	private static final float[] elevE = new float[ELEV_MAX];
	private static final int OWNER_MAX = 128;
	private static final int[] ownerX = new int[OWNER_MAX];
	private static final int[] ownerY = new int[OWNER_MAX];
	private static final int[] ownerW = new int[OWNER_MAX];
	private static final int[] ownerH = new int[OWNER_MAX];
	private static final float[] ownerE = new float[OWNER_MAX];
	private static int ownerCount = 0;
	private static int elevCount = 0;
	private static String elevScreen = "";

	/** Snapshot button tiles once the full frame's batch is known (draw time). */
	private static void snapshotElevTiles(Minecraft mc) {
		elevCount = 0;
		ownerCount = 0;
		String cls = "";
		try {
			var s = mc.gui.screen();
			if (s != null) cls = s.getClass().getName();
		} catch (Exception ignored) {}
		elevScreen = cls;
		if (cls.isEmpty()) return;
		for (int i = 0; i < pendingCount && elevCount < ELEV_MAX; i++) {
			int m = pendMat[i];
			if (m != MAT_CONTROL && m != MAT_ACTIVE) continue;
			if (pendW[i] <= 0 || pendH[i] <= 0) continue;
			elevX[elevCount] = pendX[i];
			elevY[elevCount] = pendY[i];
			elevW[elevCount] = pendW[i];
			elevH[elevCount] = pendH[i];
			elevE[elevCount] = pendElev[i];
			elevCount++;
		}
		for (int i = 0; i < pendingCount && ownerCount < OWNER_MAX; i++) {
			if (pendW[i] <= 0 || pendH[i] <= 0) continue;
			ownerX[ownerCount] = pendX[i];
			ownerY[ownerCount] = pendY[i];
			ownerW[ownerCount] = pendW[i];
			ownerH[ownerCount] = pendH[i];
			ownerE[ownerCount] = pendElev[i];
			ownerCount++;
		}
	}

	// Fail-open on screen change, stale tiles never erase items
	public static boolean isUnderElevatedTile(int x, int y, int w, int h) {		if (!enabled) return false;
		if (elevCount == 0 || w <= 0 || h <= 0) return false;
		String cls = "";
		try {
			var s = Minecraft.getInstance().gui.screen();
			if (s != null) cls = s.getClass().getName();
		} catch (Exception ignored) {}
		if (!elevScreen.equals(cls)) return false;
		for (int i = 0; i < elevCount; i++) {
			int x0 = elevX[i] + 2, y0 = elevY[i] + 2;
			int x1 = elevX[i] + elevW[i] - 2, y1 = elevY[i] + elevH[i] - 2;
			if (x1 <= x0 || y1 <= y0) continue;
			if (x < x1 && x + w > x0 && y < y1 && y + h > y0) return true;
		}
		return false;
	}
	// Owner elevation for a text point, previous frame snapshot, -1 when homeless
	public static float ownerElevFor(int x, int y) {
		if (!enabled || ownerCount == 0) return -1f;
		String cls = "";
		try {
			var s = Minecraft.getInstance().gui.screen();
			if (s != null) cls = s.getClass().getName();
		} catch (Exception ignored) {}
		if (!elevScreen.equals(cls)) return -1f;
		float owner = -1f;
		for (int i = 0; i < ownerCount; i++) {
			int x0 = ownerX[i], y0 = ownerY[i];
			int x1 = x0 + ownerW[i], y1 = y0 + ownerH[i];
			if (x1 <= x0 || y1 <= y0) continue;
			if (x >= x0 && x < x1 && y >= y0 && y < y1) owner = ownerE[i];
		}
		return owner;
	}

	// Per-plane text stash, replayed sharp after its own glass like popup rows
	private static final java.util.List<Object[]> planeTexts = new java.util.ArrayList<>();

	public static boolean hasPlaneTexts() {
		return !planeTexts.isEmpty();
	}

	public static boolean stashPlaneText(net.minecraft.client.gui.Font font,
	                                     net.minecraft.util.FormattedCharSequence seq,
	                                     org.joml.Matrix3x2f pose,
	                                     int x, int y, int color, boolean shadow, float owner) {
		if (planeTexts.size() >= 128 || font == null || seq == null || pose == null) return false;
		planeTexts.add(new Object[]{ font, seq, new org.joml.Matrix3x2f(pose), x, y, color, shadow, owner });
		return true;
	}

	// Covered by a strictly higher sheet, popup glass excluded by design
	private static boolean coveredByHigher(int x, int y, float owner) {
		for (int i = 0; i < pendingCount; i++) {
			if (pendMat[i] == MAT_POPUP) continue;
			if (pendElev[i] <= owner + 0.001f) continue;
			int x0 = pendX[i], y0 = pendY[i];
			if (x >= x0 && x < x0 + pendW[i] && y >= y0 && y < y0 + pendH[i]) return true;
		}
		return false;
	}

	// Late overlay point owns these, same mesh surgery as popup replay
	public static int submitPlaneReplay(net.minecraft.client.renderer.state.gui.GuiRenderState state) {
		int n = 0;
		boolean opened = false;
		for (Object[] r : planeTexts) {
			int x = (Integer) r[3], y = (Integer) r[4];
			float owner = (Float) r[7];
			if (coveredByHigher(x, y, owner)) continue;
			if (!opened) { state.nextStratum(); opened = true; }
			final org.joml.Matrix3x2f pose = (org.joml.Matrix3x2f) r[2];
			var st = new net.minecraft.client.renderer.state.gui.GuiTextRenderState(
				(net.minecraft.client.gui.Font) r[0], (net.minecraft.util.FormattedCharSequence) r[1], pose,
				x, y, (Integer) r[5],
				0, (Boolean) r[6], false, null);
			var prepared = st.ensurePrepared();
			state.addText(st);
			prepared.visit(new net.minecraft.client.gui.Font.GlyphVisitor() {
				@Override
				public void acceptRenderable(net.minecraft.client.gui.font.TextRenderable renderable) {
					state.addGlyphToCurrentLayer(new net.minecraft.client.renderer.state.gui.GlyphRenderState(pose, renderable, null));
				}
			});
			n++;
		}
		return n;
	}
	// Options sheets keep HUD items visible, Done stays above via strata order
	public static boolean clipHudUnderButtons() {
		var s = Minecraft.getInstance().gui.screen();
		if (s instanceof net.minecraft.client.gui.screens.options.OptionsScreen) return false;
		if (s instanceof net.minecraft.client.gui.screens.options.OptionsSubScreen) return false;
		return true;
	}
	// Options sheets leave HUD items in the background, Done glass covers them
	public static boolean keepHudItemsBackground() {
		var s = Minecraft.getInstance().gui.screen();
		if (s instanceof net.minecraft.client.gui.screens.options.OptionsScreen) return true;
		return s instanceof net.minecraft.client.gui.screens.options.OptionsSubScreen;
	}
	/** Index of the first HUD-submitted tile this frame; animation skips HUD tiles. */
	private static int hudTilesFrom = -1;
	/** Index of the first container-SLOT tile this frame — parallax range marker. */
	private static int slotTilesFrom = -1;
	/** Animated selected-slot ring: current X (fb px), target X, bar centre Y (gui). */
	private static float hudSelX = -1f;
	private static float hudSelTargetX = -1f;
	private static int hudSelCenterYGui = -1;
	private static long hudSelNanos = 0L;
	/** Hotbar slot grid origin (fb px), exact — fed to uGrid.x. */
	private static float hudGridX = -1f;
	/** Container panel rect (gui px) captured from the cancelled blit. */
	private static float hudPanelX = 0f, hudPanelY = 0f, hudPanelW = 0f, hudPanelH = 0f;
	private static int hudPanelArea = 0;

	// ─── Parallax (roadmap: параллакс-иконки) ───
	/** Exponentially smoothed mouse (window px) — oily, no jitter. */
	private static float parX = -1f, parY = -1f;
	private static long parNanos = 0L;

	private static void updateParallaxMouse() {
		Minecraft mc = Minecraft.getInstance();
		// Mouse in fb px so parallax and shader share one space
		var main = mc.gameRenderer != null ? mc.gameRenderer.mainRenderTarget() : null;
		float fbW = main != null ? main.width : mainW();
		float fbH = main != null ? main.height : fbW * 9f/16f;
		float winW = Math.max(1f, (float) mc.getWindow().getWidth());
		float winH = Math.max(1f, (float) mc.getWindow().getHeight());
		double mx = mc.mouseHandler.xpos() * fbW / winW;
		double my = mc.mouseHandler.ypos() * fbH / winH;
		long now = System.nanoTime();
		if (parX < 0 || parNanos == 0L) {
			parX = (float) mx;
			parY = (float) my;
		} else {
			float dt = Math.min((now - parNanos) / 1e9f, 0.1f);
			float k = 1f - (float) Math.exp(-10.0 * dt);
			parX += (mx - parX) * k;
			parY += (my - parY) * k;
		}
		parNanos = now;
	}

	/**
	 * Parallax shift (gui px) for an item at absolute gui coords: the icon
	 * drifts TOWARD the smoothed mouse. Tight falloff: full pull under the
	 * cursor, faint at one slot away, still past two slots.
	 * Returns null when the item is outside our SLOT tiles (tabs and other
	 * decorations stay static) or parallax is off.
	 */
	public static float[] itemParallax(int x, int y) {
		if (parallaxStrength <= 0) return null;
		// Lab master switch
		if (!LiquidumDebugState.parallax) return null;
		boolean haveTiles = slotTilesFrom >= 0 && slotTilesFrom < pendingCount;
		if (!haveTiles && wellCellCount == 0) return null;
		updateParallaxMouse();
		float scale = mainW() / pendingGuiW;
		float mgx = parX / scale, mgy = parY / scale;
		// GATE: parallax activates ONLY when the cursor is over a slot cell — hovering tabs/buttons must not pull neighbouring items.
		boolean cursorOnSlot = false;
		for (int i = slotTilesFrom < 0 ? pendingCount : slotTilesFrom; i < pendingCount; i++) {
			if (mgx >= pendX[i] - 1 && mgx <= pendX[i] + pendW[i] + 1
				&& mgy >= pendY[i] - 1 && mgy <= pendY[i] + pendH[i] + 1) {
				cursorOnSlot = true;
				break;
			}
		}
		if (!cursorOnSlot && wellCellCount > 0) {
			for (int i = 0; i < wellCellCount; i++) {
				int b = i * 4;
				if (mgx >= wellCells[b] - 1 && mgx <= wellCells[b] + wellCells[b + 2] + 1
					&& mgy >= wellCells[b + 1] - 1 && mgy <= wellCells[b + 1] + wellCells[b + 3] + 1) {
					cursorOnSlot = true;
					break;
				}
			}
		}
		if (!cursorOnSlot) return null;
		boolean inTile = false;
		// Only SLOT tiles/wells — tab/decoration tiles stay static.
		for (int i = slotTilesFrom < 0 ? pendingCount : slotTilesFrom; i < pendingCount; i++) {
			if (x >= pendX[i] - 2 && x <= pendX[i] + pendW[i] + 2
				&& y >= pendY[i] - 2 && y <= pendY[i] + pendH[i] + 2) {
				inTile = true;
				break;
			}
		}
		if (!inTile && wellCellCount > 0) {
			for (int i = 0; i < wellCellCount; i++) {
				int b = i * 4;
				if (x >= wellCells[b] - 2 && x <= wellCells[b] + wellCells[b + 2] + 2
					&& y >= wellCells[b + 1] - 2 && y <= wellCells[b + 1] + wellCells[b + 3] + 2) {
					inTile = true;
					break;
				}
			}
		}
		if (!inTile) return null;
		float ix = (x + 8) * scale, iy = (y + 8) * scale;
		float dx = parX - ix, dy = parY - iy;
		float dist = (float) Math.sqrt(dx * dx + dy * dy);
		float radius = 40.0f * scale;
		float t = Math.max(0f, Math.min(1f, dist / radius));
		float fall = (1f - t) * (1f - t) * (1f - t);
		if (fall <= 0.02f) return null;
		float amt = 1.5f * fall * parallaxStrength;
		float inv = 1f / Math.max(dist, 1e-3f);
		return new float[] { dx * inv * amt / scale, dy * inv * amt / scale };
	}

	/**
	 * Called from ScreenMixin at extractRenderState TAIL with visible widget bounds.
	 * APPENDS to this frame's batch: layered UI extracts several screens per
	 * frame (title + invisible realms/notification overlays) and a later
	 * zero-button screen must NOT wipe tiles submitted by the one below it.
	 * Snapshot elev here (batch full at extract TAIL), replay stays early.
	 */
	public static void submitWidgets(int guiW, int guiH, List<int[]> rects) {
		pendingGuiW = Math.max(1, guiW);
		pendingGuiH = Math.max(1, guiH);
		for (int[] r : rects) {
			int mat = r.length >= 5 ? r[4] : MAT_CONTROL;
			float grp = r.length >= 6 ? (float) r[5] : 0f;
			// Base plane until content replay exists, upper sheets would refract own text
			appendRect(r[0], r[1], r[2], r[3], mat, 0f, grp);
		}
		snapshotElevTiles(Minecraft.getInstance());
	}

	/** Called at frame end (GameRenderer.render TAIL) to re-arm the guard and drop stale rects.
	 *  NOTE: hudSelX is NOT reset — the ring glides smoothly between slots. */
	public static void resetFrame() {
		frameDone = false;
		pendingCount = 0;
		hudTilesFrom = -1;
		slotTilesFrom = -1;
		hudGridX = -1f;
		// Panel area re-wins every frame from current screen geometry
		hudPanelArea = 0;
		bookX0 = 0; bookY0 = 0; bookX1 = -1; bookY1 = -1;
		blurMarkerSeen = false;
		pendingEntityDraw = null;
		pendingBookDraw = null;
		pendingBannerDraw = null;
		deferredSprites.clear();
		deferredBlits.clear();
		deferredTabIcons.clear();
		deferredTexts.clear();
		popupReplayTexts.clear();
		popupReplayChecks.clear();
		planeTexts.clear();
		deferredHudItems.clear();
		deferredHudDecor.clear();
		iconKeyCount = 0;
		deferredBlits9.clear();
		deferredSeqTexts.clear();
		pendingMapDraw = null;
		wellCount = 0;
		wellCellCount = 0;
		cutCount = 0;
		fxLit = -1f;
		fxCook = -1f;
		// §15 state leakage: при выходе из мира / hide HUD сбрасываем HUD-кольцо и dock
		if (!shouldRenderHotbarEdge() && !shouldRenderHud()) {
			hudSelX = -1f; hudSelTargetX = -1f; hudSelNanos = 0L; hudSelCenterYGui = -1;
			hotbarCollapse = 0f; hotbarCollapseNanos = 0L; selRingX = -1f; selRingNanos = 0L;
		}
	}

	/**
	 * Frame-scoped flag: true once the blur-stratum marker
	 * (GuiRenderState.blurBeforeThisStratum) has been requested this frame —
	 * by us or directly by a foreign screen, vanilla throws on a second call.
	 */
	private static boolean blurMarkerSeen = false;

	public static void setBlurMarkerSeen() {
		blurMarkerSeen = true;
	}

	// Ground truth beats the shadow flag: foreign screens may mark directly
	public static boolean isBlurMarkerSet() {
		if (blurMarkerSeen) return true;
		try {
			var mc = Minecraft.getInstance();
			if (mc.gameRenderer != null) {
				var guiState = mc.gameRenderer.gameRenderState().guiRenderState;
				if (guiState != null && ((com.liquidum.client.mixin.GuiRenderStateAccessor) guiState).liquidum$getFirstStratumAfterBlur() != Integer.MAX_VALUE) {
					blurMarkerSeen = true;
					return true;
				}
			}
		} catch (Exception ignored) {}
		return false;
	}

	/** Run at vanilla's blur-before-stratum point, at most once per frame. */
	public static void applyOncePerFrame() {
		if (frameDone || !enabled) return;
		frameDone = true;
		submitProbeTile();
		renderGlassPostChain();
	}

	// Debug probe renders on every screen without widget backing
	public static void submitProbeTile() {
		if (!com.liquidum.client.debug.LiquidumDebugState.probeShow) return;
		int d = Math.max(16, Math.round(com.liquidum.client.debug.LiquidumDebugState.probeDiameter));
		int x = Math.round(com.liquidum.client.debug.LiquidumDebugState.probeX - d * 0.5f);
		int y = Math.round(com.liquidum.client.debug.LiquidumDebugState.probeY - d * 0.5f);
		appendRect(x, y, d, d, MAT_CONTROL, 2f, 0f, 2.0f);
	}

	/**
	 * Resolve the glass chain without the poisoned-cache race:
	 * vanilla's getPostChain caches failures permanently, so on null we read
	 * the config map directly and load the chain ourselves (safe to retry).
	 * The chain is rebuilt whenever the configs instance changes (F3+T).
	 */
	private static PostChain resolveChain(Minecraft mc) {
		var sm = mc.getShaderManager();
		Object configs = currentConfigs;
		if (configs == null) return null;

		if (loadedChain != null && configs == lastConfigs) return loadedChain;

		// Fast path: vanilla cache РІР‚вЂќ but only until it fails once (its failure is cached AND it logs an ERROR per attempt; direct load is quiet).
		PostChain chain = null;
		if (resolveFailures == 0) {
			chain = sm.getPostChain(GLASS_CHAIN_ID,
				java.util.Set.of(PostChain.MAIN_TARGET_ID));
		}
		if (chain == null) {
			// Direct load: read config from the map and build the chain ourselves.
			try {
				Object cfg = ((com.liquidum.client.mixin.ConfigsAccessor) configs).liquidum$getPostChains()
					.get(GLASS_CHAIN_ID);
				if (cfg == null) return null;
				chain = PostChain.load((net.minecraft.client.renderer.PostChainConfig) cfg,
					mc.getTextureManager(),
					java.util.Set.of(PostChain.MAIN_TARGET_ID),
					GLASS_CHAIN_ID,
					((com.liquidum.client.mixin.ShaderManagerAccessor) sm).liquidum$getProjection(),
					((com.liquidum.client.mixin.ShaderManagerAccessor) sm).liquidum$getProjectionMatrixBuffer());
				if (DEBUG && debugCount == 0) LiquidumMod.LOGGER.info("[glass] chain direct-loaded");
				resolveFailures = 0;
			} catch (Exception e) {
				resolveFailures++;
				if (resolveFailures == 1 || resolveFailures % 60 == 0) {
					LiquidumMod.LOGGER.warn("[glass] direct load pending (x{}): {}", resolveFailures, e.toString());
				}
				return null;
			}
		}
		loadedChain = chain;
		lastConfigs = configs;
		return chain;
	}

	private static int overlayResolveFailures = 0;

	// Separate instance: first process() call binds main fresh at TAIL (finished scene)
	private static PostChain resolveOverlayChain(Minecraft mc) {
		var sm = mc.getShaderManager();
		Object configs = currentConfigs;
		if (configs == null) return null;
		if (loadedOverlayChain != null && configs == lastOverlayConfigs) return loadedOverlayChain;
		PostChain chain = null;
		if (overlayResolveFailures == 0) {
			try {
				chain = sm.getPostChain(OVERLAY_CHAIN_ID,
					java.util.Set.of(PostChain.MAIN_TARGET_ID));
			} catch (Exception ignored) { chain = null; }
		}
		if (chain == null) {
			try {
				Object cfg = ((com.liquidum.client.mixin.ConfigsAccessor) configs).liquidum$getPostChains()
					.get(OVERLAY_CHAIN_ID);
				if (cfg == null) return null;
				chain = PostChain.load((net.minecraft.client.renderer.PostChainConfig) cfg,
					mc.getTextureManager(),
					java.util.Set.of(PostChain.MAIN_TARGET_ID),
					OVERLAY_CHAIN_ID,
					((com.liquidum.client.mixin.ShaderManagerAccessor) sm).liquidum$getProjection(),
					((com.liquidum.client.mixin.ShaderManagerAccessor) sm).liquidum$getProjectionMatrixBuffer());
				overlayResolveFailures = 0;
			} catch (Exception e) {
				overlayResolveFailures++;
				if (overlayResolveFailures == 1 || overlayResolveFailures % 60 == 0) {
					LiquidumMod.LOGGER.warn("[glass] overlay load pending (x{}): {}", overlayResolveFailures, e.toString());
				}
				return null;
			}
		}
		loadedOverlayChain = chain;
		lastOverlayConfigs = configs;
		return chain;
	}

	/** Apply the glass effect to the main framebuffer via the PostChain. */
	public static void renderGlassPostChain() {
		if (!enabled) {
			logFrame(LiquidumDebugState.mode, pendingCount, false, "disabled");
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		// A failed getPostChain lookup is cached permanently inside CompilationCache, so never query before client resources have finished loading.
		if (mc.gui == null || mc.gui.overlay() != null) return;
		if (!initialized) init();
		if (!initialized || errored || mc.gameRenderer == null) return;
		RenderTarget main = mc.gameRenderer.mainRenderTarget();
		if (main == null) return;

		if (pendingCount == 0 && wellCount == 0 && hudPanelArea == 0) { // no glass at all -> zero cost
			logFrame(LiquidumDebugState.mode, pendingCount, false, "skip pend=0/mask=0");
			return;
		}

		PostChain chain = resolveChain(mc);
		if (chain == null) {
			logFrame(LiquidumDebugState.mode, pendingCount, false, "chain=null");
			// A genuine load failure (not "not ready yet") is fragile → surface it.
			if (LiquidumDebugState.crashOnError && resolveFailures > 0) {
				throw new RuntimeException(
					"Liquidum glass chain failed to load (glass.json / post/glass shader missing or invalid?)");
			}
			return;
		}

		try {
			resolveGlassOutput(chain, main);
			computeUpperLevels();
			hookGlassUniform(chain);
			float cutElev = upperLevelCount > 0 ? upperLevels[0] - 0.001f : Float.POSITIVE_INFINITY;
			writePanelUniform(mc, main, 0f, true, cutElev, Float.POSITIVE_INFINITY);

			// Scissor glass work to panel/tile union, saves fillrate
			int[] scissor = computeScissorUnion(main);
			boolean scissorOn = false;
			if (scissor != null) {
				try {
					GlStateManager._enableScissorTest();
					GlStateManager._scissorBox(scissor[0], scissor[1], scissor[2], scissor[3]);
					scissorOn = true;
					if (DEBUG && debugCount % 600 == 0) {
						LiquidumMod.LOGGER.info("[glass] scissor {} {} {} {} (pend={} panel={}x{} wells={})",
							scissor[0], scissor[1], scissor[2], scissor[3], pendingCount, (int)hudPanelW, (int)hudPanelH, wellCount);
					}
				} catch (Throwable t) {
					LiquidumMod.LOGGER.warn("[glass] scissor enable failed: {}", t.toString());
				}
			}
			try {
				long passStart = System.nanoTime();
				chain.process(main, GraphicsResourceAllocator.UNPOOLED);
				float passMs = (System.nanoTime() - passStart) / 1_000_000f;
				passMsSmooth = passMsSmooth <= 0f ? passMs : passMsSmooth + (passMs - passMsSmooth) * 0.1f;
			} finally {
				if (scissorOn) {
					try { GlStateManager._disableScissorTest(); } catch (Throwable ignored) {}
				}
			}
		consecutiveErrors = 0;
		logFrame(LiquidumDebugState.mode, pendingCount, true, "ran");
		// Upper sheets run at TAIL over the finished scene (plane 3).
			boolean dbg = DEBUG && (debugCount < 3 || debugCount % 600 == 0);
			if (dbg) LiquidumMod.LOGGER.info("[glass] postchain #{}: main={}x{} glassout={}x{} out={}",
				debugCount, main.width, main.height,
				glassOutTarget != null ? glassOutTarget.width : -1,
				glassOutTarget != null ? glassOutTarget.height : -1,
				glassOutView != null);
		} catch (Throwable t) {
			// Resource reloads can transiently invalidate the chain; retry next frame instead of latching off forever.
			consecutiveErrors++;
			LiquidumMod.LOGGER.error("[glass] post chain process failed (attempt {})", consecutiveErrors, t);
			if (LiquidumDebugState.crashOnError) {
				throw new RuntimeException("Liquidum glass chain process failed", t);
			}
		}
		debugCount++;
	}

	// TAIL composite over the finished scene: popup and upper sheets sample it, cutouts keep own text sharp.
	public static void renderOverlayPostChain() {
		if (!enabled) return;
		computeUpperLevels();
		boolean popup = hasPopupTiles();
		boolean upper = hasUpperSheets();
		if (!popup && !upper) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui == null || mc.gui.overlay() != null) return;
		if (!initialized || errored || mc.gameRenderer == null) return;
		RenderTarget main = mc.gameRenderer.mainRenderTarget();
		if (main == null) return;
		PostChain chain = resolveOverlayChain(mc);
		if (chain == null) return;
		try {
			resolveGlassOutput(chain, main);
			hookOverlayUniform(chain);
			if (upper) {
				for (int k = 0; k < upperLevelCount; k++) {
					float e = upperLevels[k];
					GpuBuffer levelBuf = overlayLevelBuffer(k);
					hookGlassUniformInto(chain, levelBuf);
					writePanelUniform(mc, main, 3f, false, e - 0.001f, e + 0.001f, levelBuf);
					runOverlayOnce(main, chain);
				}
			}
			if (popup) {
				hookGlassUniformInto(chain, overlayBuffer());
				writePanelUniform(mc, main, 2f);
				runOverlayOnce(main, chain);
			}
			if (cutCount != lastCutLogged) {
				lastCutLogged = cutCount;
				if (cutCount > 0) {
					LiquidumMod.LOGGER.info("[glass] overlay cuts={} main={}x{} cut0=[{},{},{}x{}]", cutCount,
						main.width, main.height, cutRect[0], cutRect[1], cutRect[2], cutRect[3]);
				} else {
					LiquidumMod.LOGGER.info("[glass] overlay cuts=0");
				}
			}
			if (DEBUG && debugCount % 600 == 0) {
				StringBuilder lsb = new StringBuilder("[glass] overlay ran pend=").append(pendingCount).append(" upper=").append(upperLevelCount);
				for (int k = 0; k < upperLevelCount; k++) lsb.append(String.format(java.util.Locale.ROOT, " %.2f", upperLevels[k]));
				LiquidumMod.LOGGER.info(lsb.toString());
			}
		} catch (Throwable t) {
			LiquidumMod.LOGGER.error("[glass] overlay process failed", t);
		}
	}

	// One overlay chain run with tile-union scissor, shared by popup and upper passes
	private static void runOverlayOnce(RenderTarget main, PostChain chain) {
		int[] scissor = computeOverlayScissorUnion(main);
		boolean scissorOn = false;
		if (scissor != null) {
			try {
				GlStateManager._enableScissorTest();
				GlStateManager._scissorBox(scissor[0], scissor[1], scissor[2], scissor[3]);
				scissorOn = true;
			} catch (Throwable t) {
				LiquidumMod.LOGGER.warn("[glass] overlay scissor failed: {}", t.toString());
			}
		}
		try {
			chain.process(main, GraphicsResourceAllocator.UNPOOLED);
		} finally {
			if (scissorOn) {
				try { GlStateManager._disableScissorTest(); } catch (Throwable ignored) {}
			}
		}
	}

	private static int consecutiveErrors = 0;
	private static int resolveFailures = 0;
	private static int lastCutLogged = -1;
	private static float passMsSmooth;
	private static int lastLoggedCount = -1;
	private static int diagCount = 0;
	private static boolean lastRan = false;
	private static int lastPend = -1;
	private static String lastNote = "";

	/** Per-frame trace: logs on state TRANSITIONS (not every frame) + startup burst. */
	private static void logFrame(int mode, int pend, boolean ran, String note) {
		if (!DEBUG) return;
		diagCount++;
		boolean burst = diagCount < 20;
		boolean slow = diagCount % 600 == 0;
		boolean transition = pend != lastPend || ran != lastRan || !note.equals(lastNote);
		if (transition && diagCount > 20 && diagCount - lastTransitionFrame > 15) {
			LiquidumMod.LOGGER.info("[glass] TRANSITION @frame#{}: {} -> mode={}({}) pend={} ran={} {} frost={}",
				diagCount, lastSummary, mode, LiquidumDebugState.modeName(), pend, ran, note, LiquidumDebugState.frostRadius);
			lastTransitionFrame = diagCount;
		}
		if (burst || slow || (transition && Math.abs(diagCount - lastTransitionLog) > 30)) {
			if (transition) lastTransitionLog = diagCount;
			lastSummary = String.format("pend=%d ran=%s %s", pend, ran, note);
			LiquidumMod.LOGGER.info("[glass] frame #{}: mode={}({}) pend={} ran={} {}",
				diagCount, mode, LiquidumDebugState.modeName(), pend, ran, note);
		}
		lastRan = ran;
		lastPend = pend;
		lastNote = note;
	}
	private static int lastTransitionFrame = -100;
	private static int lastTransitionLog = -100;
	private static String lastSummary = "";

	/**
	 * Replace the engine-created static "GlassConfig" UBO of the glass pass with
	 * our own mappable one. Re-done whenever the chain was rebuilt (F3+T).
	 * Each chain gets its own buffer object (lifecycles are independent).
	 */
	private static void hookGlassUniform(PostChain chain) {
		hookGlassUniformInto(chain, false);
	}

	private static void hookOverlayUniform(PostChain chain) {
		hookGlassUniformInto(chain, true);
	}

	private static void hookGlassUniformInto(PostChain chain, boolean overlay) {
		hookGlassUniformInto(chain, overlay ? overlayBuffer() : glassBuffer());
	}

	private static GpuBuffer glassBuffer() {
		GpuDevice device = RenderSystem.getDevice();
		if (glassConfigBuffer == null || glassConfigBuffer.isClosed()) {
			glassConfigBuffer = device.createBuffer(() -> "liquidum glass config",
				GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_DST, GLASS_CONFIG_BYTES);
		}
		return glassConfigBuffer;
	}

	private static GpuBuffer overlayBuffer() {
		GpuDevice device = RenderSystem.getDevice();
		if (overlayConfigBuffer == null || overlayConfigBuffer.isClosed()) {
			overlayConfigBuffer = device.createBuffer(() -> "liquidum overlay config",
				GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_DST, GLASS_CONFIG_BYTES);
		}
		return overlayConfigBuffer;
	}

	private static final GpuBuffer[] overlayLevelBuffers = new GpuBuffer[8];

	private static GpuBuffer overlayLevelBuffer(int level) {
		GpuDevice device = RenderSystem.getDevice();
		if (overlayLevelBuffers[level] == null || overlayLevelBuffers[level].isClosed()) {
			overlayLevelBuffers[level] = device.createBuffer(() -> "liquidum overlay level " + level,
				GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_DST, GLASS_CONFIG_BYTES);
		}
		return overlayLevelBuffers[level];
	}

	private static void hookGlassUniformInto(PostChain chain, GpuBuffer buf) {
		boolean hooked = false;
		for (var pass : ((PostChainAccessor) chain).liquidum$getPasses()) {
			RenderPipeline pipeline = ((PostPassAccessor) pass).liquidum$getPipeline();
			Identifier frag = pipeline != null ? pipeline.getFragmentShader() : null;
			if (DEBUG && debugCount == 0 && frag != null) {
				LiquidumMod.LOGGER.info("[glass] pass frag shader = {} (match key = post/glass)", frag);
			}
			// MC resolves the JSON "fragment_shader": "liquidum:post/glass" to "<ns>:shaders/post/glass" — match by exact path, not by substring.
			if (frag != null && frag.getPath().equals("post/glass")) {
				((PostPassAccessor) pass).liquidum$getCustomUniforms().put("GlassConfig", buf);
				hooked = true;
				break;
			}
		}
		if (!hooked && DEBUG && debugCount == 0) {
			LiquidumMod.LOGGER.warn("[glass] glass pass not found for uniform hook");
		}
	}

	/** Write widget-derived panel rects (std140: vec4[N] + vec4 + int). */
	private static void writePanelUniform(Minecraft mc, RenderTarget main) {
		writePanelUniform(mc, main, 0f);
	}

	private static void writePanelUniform(Minecraft mc, RenderTarget main, float plane) {
		writePanelUniform(mc, main, plane, plane < 0.5f || plane > 1.5f, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY);
	}

	// Upper run shares tiles but drops wells and panel, it samples their composite
	private static void writePanelUniform(Minecraft mc, RenderTarget main, float plane, boolean withBase, float lo, float hi) {
		writePanelUniform(mc, main, plane, withBase, lo, hi, plane > 1.5f ? overlayBuffer() : glassBuffer());
	}

	// One snapshot buffer per upper optical plane, passes never share state
	private static void writePanelUniform(Minecraft mc, RenderTarget main, float plane, boolean withBase, float lo, float hi, GpuBuffer ubo) {
		if (ubo == null || ubo.isClosed()) {
			LiquidumMod.LOGGER.warn("[glass] EFFECT SKIP: uniform buffer missing/closed");
			return;
		}
		float w = main.width;
		float h = main.height;
		// GUI units -> main framebuffer pixels
		float scale = w / (float) pendingGuiW;

		float[] floats = new float[MAX_PANELS * 4];
		float[] mats = new float[MAX_PANELS * 4];   // parallel material-ID array
		float animP = openProgress();
		// Rise: tiles slide up a little while growing (easeOutCubic "выезд").
		float rise = (1.0f - animP) * 8.0f * scale;
		for (int i = 0; i < pendingCount; i++) {
			// HUD tiles never animate, screen tiles grow from half size
			boolean animate = !(hudTilesFrom >= 0 && i >= hudTilesFrom);
			float p = animate ? (0.5f + 0.5f * animP) : 1.0f;
			float r = animate ? rise : 0.0f;
			float hw = Math.max(2f, pendW[i] * 0.5f * scale) * p;
			float hh = Math.max(2f, pendH[i] * 0.5f * scale) * p;
			// Widget Y grows downward; framebuffer texCoord v=0 is the BOTTOM row, so mirror vertically.
			float cx = (pendX[i] + pendW[i] * 0.5f) * scale;
			float cy = h - (pendY[i] + pendH[i] * 0.5f) * scale + r;
			setRect(floats, i, cx, cy, hw, hh);
			mats[i * 4] = pendMat[i];
			mats[i * 4 + 1] = pendElev[i];
			mats[i * 4 + 2] = pendGrp[i];
			mats[i * 4 + 3] = pendShapeW[i];
		}
		int count = pendingCount;

		// Material params come from LiquidumDebugState (live-tunable from the Lab).
		float cornerRadiusFraction = LiquidumDebugState.cornerRadiusFraction;
		float refraction = LiquidumDebugState.refraction;
		float fresnel = LiquidumDebugState.fresnel;
		float sharpnessMix = LiquidumDebugState.sharpnessMix;

		try (var mapped = ubo.map(false, true)) {
			ByteBuffer bb = mapped.data().order(ByteOrder.nativeOrder());
			bb.asFloatBuffer().put(floats);
			// uMats: parallel array right after uRects (std140 vec4 stride).
			int matsOff = MAX_PANELS * 16;
			for (int i = 0; i < MAX_PANELS; i++) {
				bb.putFloat(matsOff + i * 16, mats[i * 4]);
				bb.putFloat(matsOff + i * 16 + 4, mats[i * 4 + 1]);
				bb.putFloat(matsOff + i * 16 + 8, mats[i * 4 + 2]);
				bb.putFloat(matsOff + i * 16 + 12, mats[i * 4 + 3]);
			}
			int floatBytes = MAX_PANELS * 32 + MAX_WELLS * 3 * 16;   // tail after uWells
			// uWells: 12 grid-дескрипторов × 3 vec4, конвертация gui → fb px (bottom-origin): центр ячейки [0][0], шаг со знаком −Y.
			int wellsOff = MAX_PANELS * 32;
			for (int wi = 0; wi < wellCount; wi++) {
				int s = wi * 12;
				float cellW = wells[s + 2] * scale, cellH = wells[s + 3] * scale;
				float orgX = (wells[s] + wells[s + 2] * 0.5f) * scale;
				float orgY = h - (wells[s + 1] + wells[s + 3] * 0.5f) * scale;
				bb.putFloat(wellsOff + wi * 48, orgX);
				bb.putFloat(wellsOff + wi * 48 + 4, orgY);
			bb.putFloat(wellsOff + wi * 48 + 8, cellW * 0.5f - 0.5f * scale); // inner half
			bb.putFloat(wellsOff + wi * 48 + 12, cellH * 0.5f - 0.5f * scale);
				bb.putFloat(wellsOff + wi * 48 + 16, wells[s + 4] * scale);       // pitchX
				bb.putFloat(wellsOff + wi * 48 + 20, wells[s + 5] * scale);       // pitchY
				bb.putFloat(wellsOff + wi * 48 + 24, wells[s + 6]);               // cols
				bb.putFloat(wellsOff + wi * 48 + 28, wells[s + 7]);               // rows
				bb.putFloat(wellsOff + wi * 48 + 32, wells[s + 8]);               // hoverCol
				bb.putFloat(wellsOff + wi * 48 + 36, wells[s + 9]);               // hoverRow
				// +40, +44 padding
			}
			bb.putFloat(floatBytes, cornerRadiusFraction);
			bb.putFloat(floatBytes + 4, refraction);
			bb.putFloat(floatBytes + 8, fresnel);
			bb.putFloat(floatBytes + 12, sharpnessMix);
			bb.putFloat(floatBytes + 16, count);
			// uMeta.y = SDF fusion radius (0 = hard union).
			bb.putFloat(floatBytes + 20,
				LiquidumDebugState.fusion ? LiquidumDebugState.fusionRadius : 0f);
			// uMeta ring centre with smoothed glide, -1 hides it
			if (hudSelTargetX >= 0) {
				long now = System.nanoTime();
				if (hudSelX < 0 || hudSelNanos == 0L) {
					hudSelX = hudSelTargetX;
				} else {
					float dt = Math.min((now - hudSelNanos) / 1e9f, 0.1f);
					hudSelX += (hudSelTargetX - hudSelX) * (1f - (float) Math.exp(-14.0 * dt));
				}
				hudSelNanos = now;
			} else {
				hudSelX = -1f;
				hudSelNanos = 0L;
			}
			bb.putFloat(floatBytes + 24,
				hudSelCenterYGui > 0 ? h - hudSelCenterYGui * scale : -1f);
			bb.putFloat(floatBytes + 28, hudSelX);
			// uRing = selected-slot ring half-size in fb px (scales with gui scale): hugs the 20x22 cell (half 10x11 gui) + 1px margin, thin crisp line.
			int ringOff = floatBytes + 64;
			bb.putFloat(ringOff, 11.0f * scale);
			bb.putFloat(ringOff + 4, 12.0f * scale);
			bb.putFloat(ringOff + 8, 1.1f * scale);   // ring line half-width
			bb.putFloat(ringOff + 12, 4.0f * scale);  // corner radius
			// uGrid = exact hotbar slot grid (fb px): origin, pitch, right edge.
			int gridOff = floatBytes + 80;
			bb.putFloat(gridOff, hudGridX);
			bb.putFloat(gridOff + 4, 20.0f * scale);
			bb.putFloat(gridOff + 8, hudGridX >= 0 ? 181.0f * scale : 0.0f);
			bb.putFloat(gridOff + 12, 0.0f);
			// uPanel gated by open container screen, no ghost panels elsewhere
			boolean panelScreenOpen = mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
			int panelOff = floatBytes + 96;
			if (withBase && hudPanelArea > 0 && panelScreenOpen) {
				bb.putFloat(panelOff, (hudPanelX + hudPanelW * 0.5f) * scale);
				bb.putFloat(panelOff + 4, h - (hudPanelY + hudPanelH * 0.5f) * scale);
				bb.putFloat(panelOff + 8, hudPanelW * 0.5f * scale);
				bb.putFloat(panelOff + 12, hudPanelH * 0.5f * scale);
			} else {
				bb.putFloat(panelOff + 8, 0.0f);
			}
		// uPar = parallax: smoothed mouse (fb px, bottom-origin) + strength.
		updateParallaxMouse();
		int parOff = floatBytes + 112;
		bb.putFloat(parOff, parX);
		bb.putFloat(parOff + 4, h - parY);
			bb.putFloat(parOff + 8, LiquidumDebugState.parallax ? 0.35f * scale * parallaxStrength : 0f);
			            // time for walk — static in dark/main-menu/pause, smooth fractional (no float jitter at 100+ fps)
            float timeVal = 0f;
            boolean isPause = false;
            try { isPause = mc.isPaused(); } catch (Exception e) { isPause = mc.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen; }
            if (mc.level != null && !isPause && lightConfidence > 0.55f && lightIntensity > 0.35f) {
                timeVal = (float)((System.nanoTime() % 100_000_000_000L) / 1_000_000_000.0);
            } else {
                timeVal = lightDirX * 3.0f; // static angle from light dir, no drift
            }
            bb.putFloat(parOff + 12, timeVal);
			// uAnim = tab transition (active, progress 0..1).
			int animOff = floatBytes + 128;
			if (tabAnimActive) {
				float p = (System.nanoTime() - tabAnimStart) / 250_000_000.0f;
				if (p >= 1f) tabAnimActive = false;
				bb.putFloat(animOff, 1f);
				bb.putFloat(animOff + 4, Math.min(p, 1f));
			} else {
				bb.putFloat(animOff, 0f);
				bb.putFloat(animOff + 4, 0f);
			}
		bb.putFloat(animOff + 8, 0f);
		bb.putFloat(animOff + 12, 0f);
		// uWellMeta = (wellCount, cookFill, tintStrength, sunSpec).
		int wellMetaOff = floatBytes + 144;
		bb.putFloat(wellMetaOff, withBase ? wellCount : 0);
		bb.putFloat(wellMetaOff + 4, fxCook >= 0 ? fxCook : 0f);
		bb.putFloat(wellMetaOff + 8, tintStrength);
		bb.putFloat(wellMetaOff + 12, com.liquidum.client.debug.LiquidumDebugState.sunSpec);
			// uFxFlame = (x, y, litIntensity, radius) — fb px / 0..1.
			int fxFlameOff = floatBytes + 160;
			if (fxLit >= 0) {
				bb.putFloat(fxFlameOff, fxFlameX * scale);
				bb.putFloat(fxFlameOff + 4, h - fxFlameY * scale);
				bb.putFloat(fxFlameOff + 8, fxLit);
				bb.putFloat(fxFlameOff + 12, 14f * scale);
			} else {
				bb.putFloat(fxFlameOff, 0f); bb.putFloat(fxFlameOff + 4, 0f);
				bb.putFloat(fxFlameOff + 8, 0f); bb.putFloat(fxFlameOff + 12, 1f);
			}
			// uFxChannel = (x0, yCentre, length, halfHeight) — fb px.
			int fxChOff = floatBytes + 176;
			if (fxCook >= 0) {
				bb.putFloat(fxChOff, fxChX0 * scale);
				bb.putFloat(fxChOff + 4, h - fxChY * scale);
				bb.putFloat(fxChOff + 8, fxChLen * scale);
				bb.putFloat(fxChOff + 12, 3.5f * scale);
			} else {
				bb.putFloat(fxChOff, 0f); bb.putFloat(fxChOff + 4, 0f);
				bb.putFloat(fxChOff + 8, 0f); bb.putFloat(fxChOff + 12, 0f);
			}
			// uTone = (darkness 0..1 сглаженный, tintR, tintG, tintB).
			updateAppearance(mc);
			updateLightDir(mc);
			int toneOff = floatBytes + 192;
			bb.putFloat(toneOff, darkSmooth);
			bb.putFloat(toneOff + 4, tintR);
			bb.putFloat(toneOff + 8, tintG);
			bb.putFloat(toneOff + 12, tintB);
			// uDockParams = (outerPad, cornerRadius, refraction, density) — fb px / 0..1 ( §7 ).
			int dockOff = floatBytes + 208;
			bb.putFloat(dockOff, dockOuterPad * scale);
			bb.putFloat(dockOff + 4, dockCornerRadius * scale);
			bb.putFloat(dockOff + 8, dockRefraction);
			bb.putFloat(dockOff + 12, dockDensity);
		int lightOff = floatBytes + 224;
		bb.putFloat(lightOff, lightDirX);
		bb.putFloat(lightOff + 4, lightDirY);
		bb.putFloat(lightOff + 8, lightIntensity);
		bb.putFloat(lightOff + 12, lightConfidence);
		// uSun = WOW sun colour (smoothed) + spec master from the Lab.
		int sunOff = floatBytes + 240;
		bb.putFloat(sunOff, sunR);
		bb.putFloat(sunOff + 4, sunG);
		bb.putFloat(sunOff + 8, sunB);
		bb.putFloat(sunOff + 12, com.liquidum.client.debug.LiquidumDebugState.sunSpec);
		// uBleed = (bodyBleed, edgeBleed, chroma, plane).
		int bleedOff = floatBytes + 256;
		bb.putFloat(bleedOff, LiquidumDebugState.bodyBleed);
		bb.putFloat(bleedOff + 4, LiquidumDebugState.edgeBleed);
		bb.putFloat(bleedOff + 8, LiquidumDebugState.chroma);
		bb.putFloat(bleedOff + 12, plane);
		// uCut = overlay content cutouts, gui px to fb px bottom-origin
		int cutOff = floatBytes + 272;
		for (int i = 0; i < MAX_CUTS; i++) {
			if (i < cutCount) {
				int b = i * 4;
				bb.putFloat(cutOff + i * 16, (cutRect[b] + cutRect[b + 2] * 0.5f) * scale);
				// Cutout centre uses height magnitude, half height passes through
				bb.putFloat(cutOff + i * 16 + 4, h - (cutRect[b + 1] + Math.abs(cutRect[b + 3]) * 0.5f) * scale);
				bb.putFloat(cutOff + i * 16 + 8, cutRect[b + 2] * 0.5f * scale);
				bb.putFloat(cutOff + i * 16 + 12, cutRect[b + 3] * 0.5f * scale);
			} else {
				bb.putFloat(cutOff + i * 16, 0f);
				bb.putFloat(cutOff + i * 16 + 4, 0f);
				bb.putFloat(cutOff + i * 16 + 8, 0f);
				bb.putFloat(cutOff + i * 16 + 12, 0f);
			}
		}
		// uLayer = upper run elevation window, raw gui elev floats
		int layerOff = floatBytes + 528;
		bb.putFloat(layerOff, lo);
		bb.putFloat(layerOff + 4, hi);
		bb.putFloat(layerOff + 8, LiquidumDebugState.edgeWidth);
		bb.putFloat(layerOff + 12, com.liquidum.client.interaction.ButtonInteractionHandler.pressLevel());
		// uRefl = face reflection (strength, width pow, tint mix, sharp boost)
		bb.putFloat(layerOff + 16, LiquidumDebugState.reflection);
		bb.putFloat(layerOff + 20, 2.0f);
		bb.putFloat(layerOff + 24, 0.55f);
		bb.putFloat(layerOff + 28, 1.0f);
			int screenOff = floatBytes + 32; // after count's 16-byte slot
			bb.putFloat(screenOff, w);
			bb.putFloat(screenOff + 4, h);
			// zw = mouse in main framebuffer px, bottom-origin (hover FX) — window→fb scale!
			double mx = mc.mouseHandler.xpos() * w / mc.getWindow().getWidth();
			double my = mc.mouseHandler.ypos() * h / mc.getWindow().getHeight();
			bb.putFloat(screenOff + 8, (float) mx);
			bb.putFloat(screenOff + 12, (float) (h - my));
			// flags = (mode, hover, edgeFX, frostRadius); edgeFX: +2 aberration, +1 rim
			int flagsOff = screenOff + 16;
			bb.putFloat(flagsOff, soloMode(LiquidumDebugState.mode));
			bb.putFloat(flagsOff + 4, LiquidumDebugState.hover ? 1f : 0f);
			bb.putFloat(flagsOff + 8, (LiquidumDebugState.aberration ? 2f : 0f) + (LiquidumDebugState.rim ? 1f : 0f));
			bb.putFloat(flagsOff + 12, LiquidumDebugState.frost ? LiquidumDebugState.frostRadius : 0f);
		}

		if (DEBUG && (count != lastLoggedCount)) {
			float eMin = Float.POSITIVE_INFINITY, eMax = Float.NEGATIVE_INFINITY;
			for (int i = 0; i < count; i++) {
				eMin = Math.min(eMin, pendElev[i]);
				eMax = Math.max(eMax, pendElev[i]);
			}
			StringBuilder sb = new StringBuilder(String.format(
				"[glass] EFFECT(%d): tiles=%d gui=%dx%d scale=%.2f animP=%.2f elev=[%.2f,%.2f] upper=%d",
				debugCount, count, pendingGuiW, pendingGuiH, scale, openProgress(), eMin, eMax, upperLevelCount));
			int shown = Math.min(count, 6);
			for (int i = 0; i < shown; i++) {
				sb.append(String.format(" | #%d[%.0f,%.0f %.0fx%.0f]",
					i, floats[i * 4], floats[i * 4 + 1], floats[i * 4 + 2] * 2, floats[i * 4 + 3] * 2));
			}
			if (count > shown) sb.append(" | +").append(count - shown).append(" more");
			// RAW gui-unit rects as reported by the widgets (ground truth).
			sb.append(" RAW:");
			for (int i = 0; i < Math.min(count, 6); i++) {
				sb.append(String.format(" #%d[%d,%d %dx%d]", i, pendX[i], pendY[i], pendW[i], pendH[i]));
			}
			LiquidumMod.LOGGER.info(sb.toString());
			lastLoggedCount = count;
		}
	}

	private static void setRect(float[] out, int index, float cx, float cy, float halfW, float halfH) {
		int o = index * 4;
		out[o] = cx;
		out[o + 1] = cy;
		out[o + 2] = halfW;
		out[o + 3] = halfH;
	}

	private static long lastTime = System.nanoTime();

	/** One-line pipeline state for the Lab status readout, no side effects. */
	// Solo stage overrides the debug mode for isolated layer checks
	private static int soloMode(int base) {
		return switch (LiquidumDebugState.soloStage) {
			case 1 -> 0;
			case 2 -> 1;
			case 3 -> 7;
			case 4 -> 8;
			case 5 -> 9;
			case 6 -> 10;
			case 7 -> 11;
			case 8 -> 12;
			case 9 -> 13;
			case 10 -> 14;
			case 11 -> 15;
			case 12 -> 16;
			case 13 -> 17;
			case 14 -> 18;
			case 15 -> 19;
			default -> base;
		};
	}
	public static String labStatus() {
		if (!enabled) {
			return "выключено";
		}
		if (errored) {
			return "ошибка цепочки";
		}
		if (!initialized) {
			return "загрузка";
		}
		if (lastRan) {
			return passMsSmooth > 0f
				? String.format(java.util.Locale.ROOT, "работает · %.1f мс", passMsSmooth)
				: "работает";
		}
		return lastNote.isEmpty() ? "ожидание" : lastNote;
	}

	/** Full subsystem dump for the Lab (called from the debug screen). */
	public static void dumpDiagnostics() {		Minecraft mc = Minecraft.getInstance();
		var sm = mc.getShaderManager();
		PostChain chain = loadedChain;
		LiquidumMod.LOGGER.info("[lab] === DIAGNOSTICS ===");
		LiquidumMod.LOGGER.info("[lab] initialized={} errored={} loadedChain={} configsMatch={}",
			initialized, errored, chain != null, currentConfigs == lastConfigs);
		LiquidumMod.LOGGER.info("[lab] overlay={} guiScreen={} pendingTiles={}",
			mc.gui != null && mc.gui.overlay() != null, mc.gui.screen() != null, pendingCount);
		if (chain != null) {
			int passes = ((com.liquidum.client.mixin.PostChainAccessor) chain).liquidum$getPasses().size();
			LiquidumMod.LOGGER.info("[lab] chain passes={}", passes);
			var t = ((com.liquidum.client.mixin.PostChainAccessor) chain).liquidum$getPersistentTargets()
				.get(net.minecraft.resources.Identifier.parse("minecraft:glassout"));
			if (t != null) LiquidumMod.LOGGER.info("[lab] glassout={}x{} viewCached={}", t.width, t.height, glassOutView != null);
			// List every persistent target to prove blur mip levels exist
			for (var e : ((com.liquidum.client.mixin.PostChainAccessor) chain).liquidum$getPersistentTargets().entrySet()) {
				var tg = e.getValue();
				LiquidumMod.LOGGER.info("[lab] target {}={}x{}", e.getKey(), tg.width, tg.height);
			}
		}
		LiquidumMod.LOGGER.info("[lab] glassConfigBuffer={} (closed={})",
			glassConfigBuffer != null, glassConfigBuffer != null && glassConfigBuffer.isClosed());
		LiquidumMod.LOGGER.info("[lab] tiles={} wells={} wellCells={} panel=[{},{},{}x{}] area={} tabAnim={}",
			pendingCount, wellCount, wellCellCount,
			Math.round(hudPanelX), Math.round(hudPanelY), Math.round(hudPanelW), Math.round(hudPanelH), hudPanelArea, tabAnimActive);
		int shown = Math.min(pendingCount, 8);
		for (int i = 0; i < shown; i++) {
			LiquidumMod.LOGGER.info("[lab] tile#{}=[{},{},{}x{}] mat={} elev={} grp={} shapeW={}",
				i, pendX[i], pendY[i], pendW[i], pendH[i], pendMat[i], pendElev[i], pendGrp[i], pendShapeW[i]);
		}
		LiquidumMod.LOGGER.info("[lab] light=({},{}) int={} conf={} dark={} passMs={}",
			lightDirX, lightDirY, lightIntensity, lightConfidence, darkSmooth, passMsSmooth);
		LiquidumMod.LOGGER.info("[lab] === END ===");
	}

	private static void init() {
		if (initialized || errored) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui == null || mc.gui.overlay() != null) return;
		try {
			if (resolveChain(mc) != null) {
				initialized = true;
				LiquidumMod.LOGGER.info("Liquidum PostChain renderer initialized");
			}
			// else: retry next frame, no poisoning
		} catch (Exception e) {
			errored = true;
			LiquidumMod.LOGGER.error("Failed to initialize Liquidum PostChain", e);
		}
	}

	public static void startAnimation(boolean open) {
		// Open animates tiles from centres, close is instant with nothing to animate
		if (open) {
			Object screen = Minecraft.getInstance().gui.screen();
			if (screen == lastAnimatedScreen) return;
			lastAnimatedScreen = screen;
			animStartNanos = System.nanoTime();
		} else {
			animStartNanos = 0L;
			lastAnimatedScreen = null;
		}
	}

	private static long animStartNanos = 0L;
	private static Object lastAnimatedScreen;

	/** Open-animation progress 0..1, eased; 1.0 when disabled/finished. */
	private static float openProgress() {
		if (!LiquidumDebugState.animOpen || animStartNanos == 0L) return 1.0f;
		float ms = Math.max(LiquidumDebugState.animMillis, 1.0f);
		float t = (System.nanoTime() - animStartNanos) / (ms * 1_000_000.0f);
		return EasingUtil.easeOutCubic(EasingUtil.clamp(t, 0.0f, 1.0f));
	}
}

