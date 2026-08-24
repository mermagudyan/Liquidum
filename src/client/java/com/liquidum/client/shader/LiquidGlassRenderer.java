package com.liquidum.client.shader;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.liquidum.LiquidumMod;
import com.liquidum.client.debug.LiquidumDebugState;
import com.liquidum.client.mixin.PostChainAccessor;
import com.liquidum.client.mixin.PostPassAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.Identifier;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.OptionalDouble;

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
	private static final Identifier GLASS_PASS_SHADER =
		Identifier.fromNamespaceAndPath("liquidum", "post/glass");

	private static boolean initialized = false;
	private static boolean errored = false;

	private static Object lastConfigs;
	private static PostChain loadedChain;
	private static com.mojang.blaze3d.textures.GpuTextureView glassOutView;
	private static RenderTarget glassOutTarget;
	private static com.mojang.blaze3d.textures.GpuSampler blitSampler;

	public static com.mojang.blaze3d.textures.GpuSampler getSampler() {
		return blitSampler;
	}

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

	private static Object currentConfigs;

	private static PostChain activeChain;
	private static GpuBuffer glassConfigBuffer;

	private static final int MAX_PANELS = 128;
	// rects[128] + params + count(pad) + screen + flags
	private static final int GLASS_CONFIG_BYTES = MAX_PANELS * 16 + 16 + 16 + 16 + 16;

	public static boolean DEBUG = true;
	/** Set true to dump the next screen's widget classes once (diagnostics). */
	public static boolean dumpWidgetClasses = true;

	/** When true, vanilla button sprites are skipped so glass becomes the button body. */
	private static final boolean REPLACE_BUTTON_BACKGROUND = true;

	public static boolean replaceVanillaButtonBackground() {
		return REPLACE_BUTTON_BACKGROUND;
	}
	private static int debugCount = 0;

	private static boolean frameDone = false;

	// Widget rects collected during Screen.extractRenderState (GUI units), consumed at draw.
	private static final int[] pendX = new int[MAX_PANELS];
	private static final int[] pendY = new int[MAX_PANELS];
	private static final int[] pendW = new int[MAX_PANELS];
	private static final int[] pendH = new int[MAX_PANELS];
	private static int pendingCount = 0;
	private static int pendingGuiW = 1;
	private static int pendingGuiH = 1;

	/** Called from ScreenMixin at extractRenderState TAIL with visible widget bounds. */
	public static void submitWidgets(int guiW, int guiH, List<int[]> rects) {
		pendingGuiW = Math.max(1, guiW);
		pendingGuiH = Math.max(1, guiH);
		pendingCount = Math.min(rects.size(), MAX_PANELS);
		for (int i = 0; i < pendingCount; i++) {
			int[] r = rects.get(i);
			pendX[i] = r[0];
			pendY[i] = r[1];
			pendW[i] = r[2];
			pendH[i] = r[3];
		}
	}

	/** Called at frame end (GameRenderer.render TAIL) to re-arm the guard and drop stale rects. */
	public static void resetFrame() {
		frameDone = false;
		blurFlagArmed = true;
		pendingCount = 0;
	}

	/** Run at vanilla's blur-before-stratum point, at most once per frame. */
	public static void applyOncePerFrame() {
		if (frameDone) return;
		frameDone = true;
		renderGlassPostChain();
	}

	private static boolean blurFlagArmed = false;

	/**
	 * Set the engine's blur-boundary flag (once per frame РІР‚вЂќ the engine throws
	 * on a second call). The flag makes GuiRenderer.draw actually invoke
	 * processBlurEffect, which is where our chain runs.
	 */
	public static void requestBlurBoundary(net.minecraft.client.gui.GuiGraphicsExtractor guiGraphics) {
		if (!blurFlagArmed) return;
		blurFlagArmed = false;
		guiGraphics.blurBeforeThisStratum();
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
				if (resolveFailures == 1 || resolveFailures % 600 == 0) {
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
		Minecraft mc = Minecraft.getInstance();
		// A failed getPostChain lookup is cached permanently inside CompilationCache,
		// so never query before client resources have finished loading.
		if (mc.gui == null || mc.gui.overlay() != null) return;
		if (!initialized) init();
		if (!initialized || errored || mc.gameRenderer == null) return;
		RenderTarget main = mc.gameRenderer.mainRenderTarget();
		if (main == null) return;

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
		if (pendingCount == 0) { // no widgets this frame -> no glass, zero cost
			logFrame(LiquidumDebugState.mode, pendingCount, false, "skip pend=0");
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

	/** Per-frame trace to catch flicker: which mode, how many panels, did the chain run. */
	private static void logFrame(int mode, int pend, boolean ran, String note) {
		if (!DEBUG) return;
		diagCount++;
		// Burst for the first ~20 frames, then throttled — enough to see the init pattern.
		boolean burst = diagCount < 20;
		boolean slow = diagCount % 600 == 0;
		if (burst || slow) {
			LiquidumMod.LOGGER.info("[glass] frame #{}: mode={}({}) pend={} ran={} {}",
				diagCount, mode, LiquidumDebugState.modeName(), pend, ran, note);
		}
	}

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
				LiquidumMod.LOGGER.info("[glass] pass frag shader = {} (our match key = {})", frag, GLASS_PASS_SHADER);
			}
			// MC resolves the JSON "fragment_shader": "liquidum:post/glass" to
			// "liquidum:shaders/post/glass", so match by path instead of exact id.
			if (frag != null && frag.getPath().contains("glass")) {
				((PostPassAccessor) pass).liquidum$getCustomUniforms().put("GlassConfig", glassConfigBuffer);
				hooked = true;
				break;
			}
		}
		if (!hooked && DEBUG && debugCount == 0) {
			LiquidumMod.LOGGER.warn("[glass] glass pass not found for uniform hook");
		}
		activeChain = chain;
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
		for (int i = 0; i < pendingCount; i++) {
			float hw = Math.max(2f, pendW[i] * 0.5f * scale);
			float hh = Math.max(2f, pendH[i] * 0.5f * scale);
			// Widget Y grows downward; framebuffer texCoord v=0 is the BOTTOM row,
			// so mirror vertically.
			float cx = (pendX[i] + pendW[i] * 0.5f) * scale;
			float cy = h - (pendY[i] + pendH[i] * 0.5f) * scale;
			setRect(floats, i, cx, cy, hw, hh);
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

		if (DEBUG && (debugCount % 600 == 0 || count != lastLoggedCount)) {
			lastLoggedCount = count;
			StringBuilder sb = new StringBuilder(String.format(
				"[glass] EFFECT: tiles=%d gui=%dx%d main=%dx%d scale=%.2f params=[r=%.2f ref=%.1f fr=%.2f sh=%.2f]",
				count, pendingGuiW, pendingGuiH, (int) w, (int) h, scale,
				cornerRadiusFraction, refraction, fresnel, sharpnessMix));
			int shown = Math.min(count, 4);
			for (int i = 0; i < shown; i++) {
				sb.append(String.format(" | #%d[%.0f,%.0f %.0fx%.0f]",
					i, floats[i * 4], floats[i * 4 + 1], floats[i * 4 + 2] * 2, floats[i * 4 + 3] * 2));
			}
			if (count > shown) sb.append(" | +").append(count - shown).append(" more");
			LiquidumMod.LOGGER.info(sb.toString());
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
			if (resolveChain(mc) == null) {
				return; // retry next frame, no poisoning
			}
			if (blitSampler == null) {
				blitSampler = RenderSystem.getDevice().createSampler(
					com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE,
					com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE,
					com.mojang.blaze3d.textures.FilterMode.LINEAR,
					com.mojang.blaze3d.textures.FilterMode.LINEAR, 1, OptionalDouble.of(0.0));
			}
			initialized = true;
			LiquidumMod.LOGGER.info("Liquidum PostChain renderer initialized");
		} catch (Exception e) {
			errored = true;
			LiquidumMod.LOGGER.error("Failed to initialize Liquidum PostChain", e);
		}
	}

	public static void startAnimation(boolean open) {
	}
}

