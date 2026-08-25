package com.liquidum.client.shader;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
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

	private static final Identifier GLASS_CHAIN_ID =
		Identifier.fromNamespaceAndPath("liquidum", "glass");

	private static boolean initialized = false;
	private static boolean errored = false;

	private static Object lastConfigs;
	private static PostChain loadedChain;
	private static com.mojang.blaze3d.textures.GpuTextureView glassOutView;
	private static RenderTarget glassOutTarget;

	public static boolean DEBUG = false;
	private static boolean enabled = true;
	private static boolean buttonsGlass = true;
	private static boolean hotbarGlass = true;
	private static boolean slotsGlass = true;
	private static float parallaxStrength = 1.0f;

	/** Sync runtime flags from the loaded config. */
	public static void applyConfig(LiquidumConfig c) {
		enabled = c.enabled;
		DEBUG = c.debugLogging;
		buttonsGlass = c.buttonsGlass;
		hotbarGlass = c.hotbarGlass;
		slotsGlass = c.containerGlass;
		parallaxStrength = c.parallaxStrength;
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
		// Persistent targets are NOT auto-sized to main by the engine. If the
		// size drifts (or stays at the 1x1 default) the glass pass renders into
		// a single pixel and the GUI blit stretches it -> flat color "following
		// the world". Force it to match main, and re-fetch the view EVERY frame
		// (PostChain recreates the target's texture/view on resize, so a cached
		// view goes stale = flat stretched colors).
		for (var e : ((com.liquidum.client.mixin.PostChainAccessor) chain).liquidum$getPersistentTargets().entrySet()) {
			var t = e.getValue();
			if (t.width != main.width || t.height != main.height) {
				int ow = t.width, oh = t.height;
				t.resize(main.width, main.height);
				if (DEBUG && debugCount % 600 == 0)
					LiquidumMod.LOGGER.info("[glass] resized glassout {}x{} -> {}x{}",
						ow, oh, main.width, main.height);
			}
			var view = t.getColorTextureView();
			if (view != null) {
				if (view != glassOutView && DEBUG && debugCount % 600 == 0) {
					LiquidumMod.LOGGER.info("[glass] glassout view -> {}x{}", t.width, t.height);
				}
				glassOutView = view;
			}
			glassOutTarget = t;
			return; // single persistent target
		}
		glassOutTarget = null;
		glassOutView = null;
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
			glassOutView = null;
			glassOutTarget = null;
			lastConfigs = null;
		}
		currentConfigs = configs;
	}

	private static GpuBuffer glassConfigBuffer;

	private static final int MAX_PANELS = 128;
	// rects[128] + params + count(pad) + screen + flags + ring + grid + panel + par
	private static final int GLASS_CONFIG_BYTES = MAX_PANELS * 16 + 16 * 8;

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

	/** When true, container/inventory slots get dense glass tiles. */
	public static boolean replaceSlotTiles() {
		return enabled && slotsGlass;
	}

	/** One container slot: 18x18 gui px at (x, y), dense, fusion-exempt —
	 *  adjacent slots keep hard edges (iOS widget grid), no metaball merging. */
	public static void submitSlotTile(int x, int y) {
		if (!replaceSlotTiles()) return;
		if (slotTilesFrom < 0) slotTilesFrom = pendingCount;   // parallax range marker
		appendRect(x, y, 18, 18, false, true);
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

	private static int filterLogCount = 0;

	/** Panel rect from the cancelled blit — keep the LARGEST per frame
	 *  (decorative container blits may precede the main panel). */
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
		// Tab sprites include a "leg" that overlaps the panel — cutting it
		// aligns the glass tile with the visible tab head and its item icon.
		appendRect(x, y, w, Math.max(h - 6, 8), false, true);
	}

	/** Vanilla hotbar geometry: solid 182x22 panel (same footprint as vanilla),
	 *  offhand tile on the LEFT when holding an item. Selected slot ring
	 *  (uMeta.w/z) glides between slots. */
	public static void submitHotbar(int guiW, int guiH, int selSlot, boolean hasOffhand) {
		if (!replaceHotbarBackground()) return;
		pendingGuiW = Math.max(1, guiW);
		pendingGuiH = Math.max(1, guiH);
		if (hudTilesFrom < 0) hudTilesFrom = pendingCount;
		int x0 = guiW / 2 - 91;
		int y0 = guiH - 23;
		if (hasOffhand) {
			appendRect(x0 - 30, y0, 24, 22, false, true);     // offhand: LEFT, centred on the item
		}
		appendRect(x0, y0, 182, 22, true, true);              // bar: vanilla footprint
		// Animated ring target (fb px); -1 = no selection this frame.
		// Vanilla slot grid: slot i spans [x0+1+20i, x0+21+20i].
		float scl = ((float) mainW() / pendingGuiW);
		hudSelTargetX = selSlot < 0 ? -1f : (x0 + 11 + selSlot * 20) * scl;
		hudSelCenterYGui = y0 + 11;
	}

	private static float mainW() {
		Minecraft mc = Minecraft.getInstance();
		return mc.gameRenderer != null && mc.gameRenderer.mainRenderTarget() != null
			? mc.gameRenderer.mainRenderTarget().width : (float) pendingGuiW;
	}

	private static void appendRect(int x, int y, int w, int h, boolean fusable, boolean dense) {
		if (pendingCount >= MAX_PANELS || w <= 0 || h <= 0) return;
		pendX[pendingCount] = x;
		pendY[pendingCount] = y;
		pendW[pendingCount] = w;
		pendH[pendingCount] = h;
		pendFuse[pendingCount] = fusable;
		pendDense[pendingCount] = dense;
		pendingCount++;
	}
	private static int debugCount = 0;

	private static boolean frameDone = false;

	// Widget rects collected during Screen.extractRenderState (GUI units), consumed at draw.
	private static final int[] pendX = new int[MAX_PANELS];
	private static final int[] pendY = new int[MAX_PANELS];
	private static final int[] pendW = new int[MAX_PANELS];
	private static final int[] pendH = new int[MAX_PANELS];
	private static final boolean[] pendFuse = new boolean[MAX_PANELS];
	private static final boolean[] pendDense = new boolean[MAX_PANELS];
	private static int pendingCount = 0;
	private static int pendingGuiW = 1;
	private static int pendingGuiH = 1;
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
		double mx = mc.mouseHandler.xpos(), my = mc.mouseHandler.ypos();
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
	 * drifts TOWARD the smoothed mouse. Near-only: radius ~60 gui px around
	 * the cursor, amplitude ≤1.2 gui px (sub-pixel steps stay invisible).
	 * Returns null when the item is outside our SLOT tiles (tabs and other
	 * decorations stay static) or parallax is off.
	 */
	public static float[] itemParallax(int x, int y) {
		if (parallaxStrength <= 0 || pendingCount == 0 || slotTilesFrom < 0) return null;
		updateParallaxMouse();
		float scale = mainW() / pendingGuiW;
		float mgx = parX / scale, mgy = parY / scale;
		// GATE: parallax activates ONLY when the cursor is over a slot cell —
		// hovering tabs/buttons must not pull neighbouring items.
		boolean cursorOnSlot = false;
		for (int i = slotTilesFrom; i < pendingCount; i++) {
			if (mgx >= pendX[i] - 1 && mgx <= pendX[i] + pendW[i] + 1
				&& mgy >= pendY[i] - 1 && mgy <= pendY[i] + pendH[i] + 1) {
				cursorOnSlot = true;
				break;
			}
		}
		if (!cursorOnSlot) return null;
		boolean inTile = false;
		// Only SLOT tiles (from slotTilesFrom on) — tab/decoration tiles stay static.
		for (int i = slotTilesFrom; i < pendingCount; i++) {
			if (x >= pendX[i] - 2 && x <= pendX[i] + pendW[i] + 2
				&& y >= pendY[i] - 2 && y <= pendY[i] + pendH[i] + 2) {
				inTile = true;
				break;
			}
		}
		if (!inTile) return null;
		float ix = (x + 8) * scale, iy = (y + 8) * scale;
		float dx = parX - ix, dy = parY - iy;
		float dist = (float) Math.sqrt(dx * dx + dy * dy);
		float radius = 60.0f * scale;                       // near-only: ~60 gui px
		float t = Math.max(0f, Math.min(1f, dist / radius));
		float fall = 1f - t * t * (3f - 2f * t);
		if (fall <= 0.02f) return null;
		float amt = 1.2f * fall * parallaxStrength;         // constant amplitude, near-only
		float inv = 1f / Math.max(dist, 1e-3f);
		return new float[] { dx * inv * amt / scale, dy * inv * amt / scale };
	}

	/**
	 * Called from ScreenMixin at extractRenderState TAIL with visible widget bounds.
	 * APPENDS to this frame's batch: layered UI extracts several screens per
	 * frame (title + invisible realms/notification overlays) and a later
	 * zero-button screen must NOT wipe tiles submitted by the one below it.
	 */
	public static void submitWidgets(int guiW, int guiH, List<int[]> rects) {
		pendingGuiW = Math.max(1, guiW);
		pendingGuiH = Math.max(1, guiH);
		for (int[] r : rects) {
			appendRect(r[0], r[1], r[2], r[3], true, false);
		}
	}

	/** Called at frame end (GameRenderer.render TAIL) to re-arm the guard and drop stale rects.
	 *  NOTE: hudSelX is NOT reset — the ring glides smoothly between slots. */
	public static void resetFrame() {
		frameDone = false;
		pendingCount = 0;
		hudTilesFrom = -1;
		slotTilesFrom = -1;
		hudGridX = -1f;
		hudPanelArea = 0;
		blurMarkerSeen = false;
	}

	/**
	 * Frame-scoped flag: true once the blur-stratum marker
	 * (GuiRenderState.blurBeforeThisStratum) has been requested this frame —
	 * by vanilla (extractBlurredBackground) or by us (ScreenMixin fallback).
	 * The engine throws on a second call within one frame.
	 */
	private static boolean blurMarkerSeen = false;

	public static boolean isBlurMarkerSeen() {
		return blurMarkerSeen;
	}

	public static void setBlurMarkerSeen() {
		blurMarkerSeen = true;
	}

	/** Run at vanilla's blur-before-stratum point, at most once per frame. */
	public static void applyOncePerFrame() {
		if (frameDone || !enabled) return;
		frameDone = true;
		renderGlassPostChain();
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

		// Fast path: vanilla cache РІР‚вЂќ but only until it fails once (its failure
		// is cached AND it logs an ERROR per attempt; direct load is quiet).
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

	/** Apply the glass effect to the main framebuffer via the PostChain. */
	public static void renderGlassPostChain() {
		if (!enabled) {
			logFrame(LiquidumDebugState.mode, pendingCount, false, "disabled");
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		// A failed getPostChain lookup is cached permanently inside CompilationCache,
		// so never query before client resources have finished loading.
		if (mc.gui == null || mc.gui.overlay() != null) return;
		if (!initialized) init();
		if (!initialized || errored || mc.gameRenderer == null) return;
		RenderTarget main = mc.gameRenderer.mainRenderTarget();
		if (main == null) return;

		if (pendingCount == 0) { // no widgets this frame -> no glass, zero cost
			logFrame(LiquidumDebugState.mode, pendingCount, false, "skip pend=0");
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
			hookGlassUniform(chain);
			writePanelUniform(mc, main);

			chain.process(main, GraphicsResourceAllocator.UNPOOLED);
			consecutiveErrors = 0;
			logFrame(LiquidumDebugState.mode, pendingCount, true, "ran");


			boolean dbg = DEBUG && (debugCount < 3 || debugCount % 600 == 0);
			if (dbg) LiquidumMod.LOGGER.info("[glass] postchain #{}: main={}x{} glassout={}x{} out={}",
				debugCount, main.width, main.height,
				glassOutTarget != null ? glassOutTarget.width : -1,
				glassOutTarget != null ? glassOutTarget.height : -1,
				glassOutView != null);
		} catch (Throwable t) {
			// Resource reloads can transiently invalidate the chain; retry next
			// frame instead of latching off forever.
			consecutiveErrors++;
			LiquidumMod.LOGGER.error("[glass] post chain process failed (attempt {})", consecutiveErrors, t);
			if (LiquidumDebugState.crashOnError) {
				throw new RuntimeException("Liquidum glass chain process failed", t);
			}
		}
		debugCount++;
	}

	private static int consecutiveErrors = 0;
	private static int resolveFailures = 0;
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
			LiquidumMod.LOGGER.info("[glass] TRANSITION @frame#{}: {} -> mode={}({}) pend={} ran={} {}",
				diagCount, lastSummary, mode, LiquidumDebugState.modeName(), pend, ran, note);
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
	 */
	private static void hookGlassUniform(PostChain chain) {
		GpuDevice device = RenderSystem.getDevice();
		// MC's PostPass.addToFrame CLOSES the GpuBuffer held in customUniforms at
		// the end of every frame, so our buffer dies after frame 1. Recreate it
		// whenever it is dead and re-bind it EVERY frame (no "already hooked"
		// shortcut) — otherwise the shader reads a stale/closed zeroed UBO and
		// produces a flat 0,0-coloured screen.
		if (glassConfigBuffer == null || glassConfigBuffer.isClosed()) {
			glassConfigBuffer = device.createBuffer(() -> "liquidum glass config",
				GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_DST, GLASS_CONFIG_BYTES);
		}
		boolean hooked = false;
		for (var pass : ((PostChainAccessor) chain).liquidum$getPasses()) {
			RenderPipeline pipeline = ((PostPassAccessor) pass).liquidum$getPipeline();
			Identifier frag = pipeline != null ? pipeline.getFragmentShader() : null;
			if (DEBUG && debugCount == 0 && frag != null) {
				LiquidumMod.LOGGER.info("[glass] pass frag shader = {} (match key = post/glass)", frag);
			}
			// MC resolves the JSON "fragment_shader": "liquidum:post/glass" to
			// "<ns>:shaders/post/glass" — match by exact path, not by substring.
			if (frag != null && frag.getPath().equals("post/glass")) {
				((PostPassAccessor) pass).liquidum$getCustomUniforms().put("GlassConfig", glassConfigBuffer);
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
		if (glassConfigBuffer == null || glassConfigBuffer.isClosed()) {
			LiquidumMod.LOGGER.warn("[glass] EFFECT SKIP: uniform buffer missing/closed");
			return;
		}
		float w = main.width;
		float h = main.height;
		// GUI units -> main framebuffer pixels
		float scale = w / (float) pendingGuiW;

		float[] floats = new float[MAX_PANELS * 4];
		float animP = openProgress();
		// Rise: tiles slide up a little while growing (easeOutCubic "выезд").
		float rise = (1.0f - animP) * 8.0f * scale;
		for (int i = 0; i < pendingCount; i++) {
			// HUD tiles (hotbar etc.) never animate — opening chat must not
			// rebuild the whole bar. Screen tiles scale 0.5→1.0 (never vanish
			// completely, even if a screen re-inits mid-animation).
			boolean animate = !(hudTilesFrom >= 0 && i >= hudTilesFrom);
			float p = animate ? (0.5f + 0.5f * animP) : 1.0f;
			float r = animate ? rise : 0.0f;
			float hw = Math.max(2f, pendW[i] * 0.5f * scale) * p;
			float hh = Math.max(2f, pendH[i] * 0.5f * scale) * p;
			// Widget Y grows downward; framebuffer texCoord v=0 is the BOTTOM row,
			// so mirror vertically.
			float cx = (pendX[i] + pendW[i] * 0.5f) * scale;
			float cy = h - (pendY[i] + pendH[i] * 0.5f) * scale + r;
			setRect(floats, i, cx, cy, hw, hh);
			// Negative halfWidth marks the tile as fusion-exempt (shader abs()s it).
			if (!pendFuse[i]) floats[i * 4 + 2] = -floats[i * 4 + 2];
			// Negative halfHeight marks the tile as dense (thicker material).
			if (pendDense[i]) floats[i * 4 + 3] = -floats[i * 4 + 3];
		}
		int count = pendingCount;

		// Material params come from LiquidumDebugState (live-tunable from the Lab).
		float cornerRadiusFraction = LiquidumDebugState.cornerRadiusFraction;
		float refraction = LiquidumDebugState.refraction;
		float fresnel = LiquidumDebugState.fresnel;
		float sharpnessMix = LiquidumDebugState.sharpnessMix;

		try (var mapped = glassConfigBuffer.map(false, true)) {
			ByteBuffer bb = mapped.data().order(ByteOrder.nativeOrder());
			bb.asFloatBuffer().put(floats);
			int floatBytes = MAX_PANELS * 16;
			bb.putFloat(floatBytes, cornerRadiusFraction);
			bb.putFloat(floatBytes + 4, refraction);
			bb.putFloat(floatBytes + 8, fresnel);
			bb.putFloat(floatBytes + 12, sharpnessMix);
			bb.putFloat(floatBytes + 16, count);
			// uMeta.y = SDF fusion radius (0 = hard union).
			bb.putFloat(floatBytes + 20,
				LiquidumDebugState.fusion ? LiquidumDebugState.fusionRadius : 0f);
			// uMeta.z = selected-slot ring centre Y (fb px, bottom-origin).
			// uMeta.w = ring centre X — exponentially smoothed toward the target
			// so the ring GLIDES between slots (iOS-style), -1 = hidden.
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
			// uRing = selected-slot ring half-size in fb px (scales with gui scale):
			// hugs the 20x22 cell (half 10x11 gui) + 1px margin, thin crisp line.
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
			// uPanel = frosted container panel (centre xy + half wh, fb px, bottom-origin).
			int panelOff = floatBytes + 96;
			if (hudPanelArea > 0) {
				bb.putFloat(panelOff, (hudPanelX + hudPanelW * 0.5f) * scale);
				bb.putFloat(panelOff + 4, h - (hudPanelY + hudPanelH * 0.5f) * scale);
				bb.putFloat(panelOff + 8, hudPanelW * 0.5f * scale);
				bb.putFloat(panelOff + 12, hudPanelH * 0.5f * scale);
			} else {
				bb.putFloat(panelOff + 8, 0.0f);
			}
			// uPar = parallax: smoothed mouse (fb px) + strength (fb px).
			updateParallaxMouse();
			int parOff = floatBytes + 112;
			bb.putFloat(parOff, parX);
			bb.putFloat(parOff + 4, parY);
			bb.putFloat(parOff + 8, 1.0f * scale * parallaxStrength);
			bb.putFloat(parOff + 12, 0.0f);
			int screenOff = floatBytes + 32; // after count's 16-byte slot
			bb.putFloat(screenOff, w);
			bb.putFloat(screenOff + 4, h);
			// zw = mouse position in main-pixel space, bottom-origin (hover FX).
			double mx = mc.mouseHandler.xpos();
			double my = mc.mouseHandler.ypos();
			bb.putFloat(screenOff + 8, (float) mx);
			bb.putFloat(screenOff + 12, (float) (h - my));
			// flags = (mode, hover, edgeFX, frostRadius); edgeFX: +2 aberration, +1 rim
			int flagsOff = screenOff + 16;
			bb.putFloat(flagsOff, LiquidumDebugState.mode);
			bb.putFloat(flagsOff + 4, LiquidumDebugState.hover ? 1f : 0f);
			bb.putFloat(flagsOff + 8, (LiquidumDebugState.aberration ? 2f : 0f) + (LiquidumDebugState.rim ? 1f : 0f));
			bb.putFloat(flagsOff + 12, LiquidumDebugState.frost ? LiquidumDebugState.frostRadius : 0f);
		}

		if (DEBUG && (count != lastLoggedCount)) {
			StringBuilder sb = new StringBuilder(String.format(
				"[glass] EFFECT(%d): tiles=%d gui=%dx%d scale=%.2f animP=%.2f",
				debugCount, count, pendingGuiW, pendingGuiH, scale, openProgress()));
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

	/** Full subsystem dump for the Lab (called from the debug screen). */
	public static void dumpDiagnostics() {
		Minecraft mc = Minecraft.getInstance();
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
		}
		LiquidumMod.LOGGER.info("[lab] glassConfigBuffer={} (closed={})",
			glassConfigBuffer != null, glassConfigBuffer != null && glassConfigBuffer.isClosed());
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
		// Open: tiles grow out of their centres (progress consumed in
		// writePanelUniform). Close is instant — after Screen.removed() the GUI
		// no longer extracts rects, so there is nothing to animate on.
		// Replay ONLY when the screen INSTANCE changes: some screens re-init
		// every second (rebuildWidgets), and a restart per init would keep the
		// tiles at ~5% size — invisible — forever.
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

