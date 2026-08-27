package com.liquidum.client.shader;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
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
		// Persistent targets are NOT auto-sized to main by the engine. Resize
		// ALL of them (glassout + uiprev) to match main, and re-fetch the
		// glassout view EVERY frame (PostChain recreates textures on resize —
		// a cached view goes stale = flat stretched colors).
		for (var e : ((com.liquidum.client.mixin.PostChainAccessor) chain).liquidum$getPersistentTargets().entrySet()) {
			var t = e.getValue();
			if (t.width != main.width || t.height != main.height) {
				t.resize(main.width, main.height);
			}
			if (((Identifier) e.getKey()).getPath().equals("glassout")) {
				var view = t.getColorTextureView();
				if (view != null) glassOutView = view;
				glassOutTarget = t;
			}
		}
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
	/** Максимум GridWell-дескрипторов за кадр (12 сеток хватает для любого
	 *  ванильного контейнера: chest = content + inv + hotbar = 3 wells). */
	public static final int MAX_WELLS = 12;
	// rects[128] + mats[128] + wells[12x3] + params + count + screen + flags
	// + ring + grid + panel + par + anim + wellMeta + fxFlame + fxChannel + tone + dockParams + lightDir
	private static final int GLASS_CONFIG_BYTES =
		MAX_PANELS * 16 * 2 + MAX_WELLS * 3 * 16 + 16 * 15;

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

	// ─── Creative Tab Stack: frame-capture transition ───
	// On tab switch the OLD tab appearance (already composited in main from
	// the previous frame) is captured into the uiprev target; during the ~250ms
	// transition it slides UP behind the panel edge (the shader clips it by the
	// panel SDF), while the new content sits still beneath. No offsets on the
	// live grid — tiles and items never desync.
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
		// WIP: the capture/slide transition conflicts with scroll spring and
		// needs polishing — disabled by default (tabTransition in config).
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


	// ─── Единая система материалов (иерархия слоёв Liquidum) ───
	// Эталон — BASE SURFACE инвентаря. Роли отличаются ПАРАМЕТРАМИ одного
	// материала (никаких отдельных проходов): чем больше элемент, тем
	// спокойнее; чем меньше и интерактивнее, тем живее.
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
	/** Inventory BASE — тот же clear glass, что у плиток кнопок/Recipe Book, но спокойнее из-за размера. */
	public static void submitBasePanel(int x, int y, int w, int h) {
		if (w <= 0 || h <= 0) return;
		appendRect(x, y, w, h, MAT_BASE);
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
		// Компактные вкладки (P7): визуальная форма заметно меньше
		// vanilla-спрайта и hitbox'а — navigation chips, а не mini-panels.
		appendRect(x + 3, y + 3, w - 6, h - 6, MAT_CONTROL);
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
		// Выбранный рецепт: vanilla красная рамка (recipe_book/overlay*)
		// заменяется состоянием ACTIVE того же материала.
		if (p.startsWith("recipe_book/overlay")) return MAT_ACTIVE;
		// Вкладки креатива: активная — BRIGHT (другое состояние стекла),
		// неактивные — CLEAR; вся группа читается как одна система.
		if (p.startsWith("container/creative_inventory/tab_")) {
			return p.contains("selected") ? MAT_ACTIVE : MAT_CONTROL;
		}
		// Трек скроллбара креатива — тихое стекло вместо белой полосы
		// (сам ползунок-scroller остаётся ванильным — это ручка).
		if (p.equals("widget/scroller_background")) return MAT_SLOT;
		return -1;
	}

	/** Стеклянная плитка вместо vanilla-спрайта (поиск, вкладки книги).
	 *  НЕ входит в parallax-диапазон слотов — содержимое остаётся статичным. */
	public static void submitSpriteTile(int x, int y, int w, int h, int mat) {
		appendRect(x, y, w, h, mat);
	}

	/** L2 FUNCTIONAL GROUP: лёгкая локальная разница яркости над BASE,
	 *  без отдельной карточки/рамки. */
	public static void submitGroupRect(int x, int y, int w, int h) {
		if (w <= 0 || h <= 0) return;
		appendRect(x, y, w, h, MAT_GROUP);
	}

	// ─── Recipe Book button: icon-only (P3/P14/P34) ───
	// Vanilla widget/hitbox/click are untouched — we only cancel the full
	// button sprite and draw a book item glyph on a subtle MAT_CONTROL body.
	private static boolean drawingBookIcon = false;

	public static void drawRecipeBookButton(net.minecraft.client.gui.GuiGraphicsExtractor g,
	                                        com.mojang.blaze3d.pipeline.RenderPipeline pipeline,
	                                        int x, int y, int w, int h, boolean hovered) {
		if (drawingBookIcon) return;
		drawingBookIcon = true;
		try {
			// RECIPE BOOK BUTTON = graceful glass control + ICON ONLY (P3/P14/P34).
			// The vanilla button sprite is NOT blitted (no baked white square); a
			// subtle MAT_CONTROL body stays so rest/hover read as one control, and
			// a book item glyph is drawn in its place.
			submitSpriteTile(x, y, w, h, MAT_CONTROL);
			int cx = x + (w - 16) / 2;
			int cy = y + (h - 16) / 2;
			g.item(new ItemStack(Items.BOOK), cx, cy, 0);
		} finally {
			drawingBookIcon = false;
		}
	}

	// ─── GridWell: процедурная recessed-сетка (визуальный примитив) ───
	// Группа vanilla Slot подаётся ОДНИМ дескриптором: шейдер математически
	// определяет ячейку по координате пикселя — без per-slot циклов и
	// без отдельных blur/mask на каждый слот (54 слота = та же стоимость,
	// что 9). Vanilla Slot остаются нетронутыми логически.
	// layout per well (gui px): [x0,y0, cellW,cellH | pitchX,pitchY, cols,rows | hoverCol,hoverRow, 0,0]
	private static final java.util.List<Object[]> deferredSprites = new java.util.ArrayList<>();
	private static final java.util.List<Object[]> deferredBlits = new java.util.ArrayList<>();
	private static final java.util.List<Object[]> deferredTabIcons = new java.util.ArrayList<>();

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
	public static void replayDeferredSprites(net.minecraft.client.gui.GuiGraphicsExtractor g) {
		if (deferredSprites.isEmpty() && deferredBlits.isEmpty() && deferredTabIcons.isEmpty()) return;
		if (!deferForeground()) {
			// Still clear to avoid leak when gate closed
			deferredSprites.clear(); deferredBlits.clear(); deferredTabIcons.clear();
			return;
		}
		boolean old = inForegroundReplay;
		inForegroundReplay = true;
		try {
			for (Object[] a : deferredSprites) {
				g.blitSprite((com.mojang.blaze3d.pipeline.RenderPipeline) a[0], (net.minecraft.resources.Identifier) a[1], (Integer) a[2], (Integer) a[3], (Integer) a[4], (Integer) a[5]);
			}
			for (Object[] a : deferredBlits) {
				g.blit((com.mojang.blaze3d.pipeline.RenderPipeline) a[0], (net.minecraft.resources.Identifier) a[1], (Integer) a[2], (Integer) a[3], (Float) a[4], (Float) a[5], (Integer) a[6], (Integer) a[7], (Integer) a[8], (Integer) a[9]);
			}
			for (Object[] a : deferredTabIcons) {
				g.item((net.minecraft.world.item.ItemStack) a[0], (Integer) a[1], (Integer) a[2], (Integer) a[3]);
			}
		} finally {
			inForegroundReplay = old;
			deferredSprites.clear();
			deferredBlits.clear();
			deferredTabIcons.clear();
		}
	}

	private static final float[] wells = new float[MAX_WELLS * 12];
	private static int wellCount = 0;
	
	public static void submitGridWell(int x, int y, int cellW, int cellH,
	                                  int pitchX, int pitchY, int cols, int rows, int hover) {
		if (wellCount >= MAX_WELLS || cols <= 0 || rows <= 0) return;
		if (wellCellCount + cols * rows > wellCells.length / 4) {
			// Not enough parallax slots — skip this well entirely to keep UBO/wellCells in sync
			return;
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
	}

	/** Ячейки GridWell для itemParallax: {x,y,w,h} на ячейку. */
	private static final int[] wellCells = new int[256 * 4];
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

	// ─── Appearance (LIGHT/DARK/AUTO + custom tint, roadmap §1–3) ───
	// Это состояния ОДНОГО материала: darkness управляет только плотностью
	// внутри glass mask; refraction/edge остаются. AUTO считается из
	// ОКРУЖЕНИЯ ИГРОКА (не из камеры!) — поэтому нет flicker при повороте,
	// плюс экспоненциальное сглаживание (hysteresis ~0.4 c).
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
	private static void updateLightDir(Minecraft mc) {
		if (mc.level == null) { lightDirX = 0f; lightDirY = 1f; lightIntensity = 1f; return; }
		// Fixed top light for now — sun angle not available in 26.x mappings without reflection.
		// Intensity still reacts to skyDarken / darkSmooth so rim dim in darkness.
		float sky = 1f - mc.level.getSkyDarken() / 15f;
		lightIntensity = 0.4f + 0.6f * sky * (1f - darkSmooth * 0.5f);
		lightDirX = 0f; lightDirY = 1f;
		if (mc.level.dimension() == net.minecraft.world.level.Level.NETHER || mc.level.dimension() == net.minecraft.world.level.Level.END) {
			lightIntensity = 0.5f;
		}
	}

	/** Площадь panel-rect текущего кадра (для cap групп semantic adapter). */
	public static int panelArea() {
		return hudPanelArea;
	}

	// ─── L4 FOREGROUND DEFERRAL ───
	// Vanilla извлекает динамические модели (игрок в инвентаре, книга
	// зачарований, флаг ткацкого станка) из extractBackground — это ДО-blur
	// фаза, поэтому они размываются стеклом. Мы отменяем вызов на этапе
	// экстракта и переигрываем его в widget-фазе ПОСЛЕ glass composite —
	// модель резкая, как item icon. Без координатных хаков.

	/** Откладывать ли foreground-модели: только когда стекло реально
	 *  заменяет фон контейнерного экрана. Во время replay — НИКОГДА
	 *  (иначе mixin отменяет собственное переигрывание). */
	public static boolean deferForeground() {
		if (inForegroundReplay) return false;
		if (!enabled || !slotsGlass) return false;
		var s = Minecraft.getInstance().gui.screen();
		return s instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
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
				int cy = (int) (oy - r * py);
				cross(g, cx, cy, s, 0xFF36E036);
			}
		}
	}

	private static void cross(net.minecraft.client.gui.GuiGraphicsExtractor g, int cx, int cy, int s, int color) {
		g.fill(RenderPipelines.GUI, cx - s, cy - 1, cx + s, cy + 1, color);
		g.fill(RenderPipelines.GUI, cx - 1, cy - s, cx + 1, cy + s, color);
	}


	/** Vanilla hotbar geometry: solid 182x22 panel (same footprint as vanilla),
	 *  offhand tile on the LEFT when holding an item. Selected slot ring
	 *  (uMeta.w/z) glides between slots. §12-14: в Spectator/MainMenu/HideGUI
	 *  Liquidum hotbar и его wells/кольцо не существуют; в Creative — остаётся (§1). */
	public static void submitHotbar(int guiW, int guiH, int selSlot, boolean hasOffhand) {
		if (!replaceHotbarBackground()) return;
		if (!shouldRenderHud()) return;
		var mcH = Minecraft.getInstance();
		if (mcH.player != null && mcH.player.isSpectator()) return; // §13 (Creative hotbar остаётся per P0 §1)
		if (mcH.level == null) return; // §14
		pendingGuiW = Math.max(1, guiW);
		pendingGuiH = Math.max(1, guiH);
		if (hudTilesFrom < 0) hudTilesFrom = pendingCount;
		int x0 = guiW / 2 - 91;
		int y0 = guiH - 23;
		if (hasOffhand) {
			appendRect(x0 - 30, y0, 24, 22, MAT_SLOT);            // offhand: LEFT, centred on the item
		}
		// Hotbar — часть той же mask-системы, что Dock (§17): Inner+Outer из одной SDF,
		// но пока подаётся как MAT_DENSE (плотный), профиль подчинится dock outer в шейдере
		appendRect(x0, y0, 182, 22, MAT_DENSE);               // bar: vanilla footprint
		// Animated ring target (fb px); -1 = no selection this frame.
		// Vanilla slot grid: slot i spans [x0+1+20i, x0+21+20i].
		float scl = ((float) mainW() / pendingGuiW);
		hudSelTargetX = selSlot < 0 ? -1f : (x0 + 11 + selSlot * 20) * scl;
		hudSelCenterYGui = y0 + 11;
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
		if (mc.gui.screen() != null) return false;
		if (isHideGui()) return false;
		var p = mc.player;
		if (p == null) return false;
		return true;
	}

	public static void submitLuminanceDock(int guiW, int guiH) {
		if (!luminanceDockEnabled || !enabled) return;
		if (!shouldRenderHud()) return;
		var mc = Minecraft.getInstance();
		var p = mc.player;
		if (p == null) return;
		// §10-11: повторять то, что Minecraft реально решил нарисовать (vanilla visibility)
		// Creative/Spectator — survival Dock off (§11,13), но XP/jump/mount — по своим правилам
		boolean isCreative = p.isCreative();
		boolean isSpectator = p.isSpectator();
		if (isSpectator) return; // §13
		pendingGuiW = Math.max(pendingGuiW, Math.max(1, guiW));
		pendingGuiH = Math.max(pendingGuiH, Math.max(1, guiH));
		if (hudTilesFrom < 0) hudTilesFrom = pendingCount;
		int hw = guiW / 2;
		int hh = guiH;
		int pad = Math.round(dockPadding);
		// Left group: hearts + armor — только если vanilla их рисует (§10)
		boolean canHurt = true;
		try { canHurt = mc.gameMode != null && mc.gameMode.canHurtPlayer(); } catch (Exception ignored) {}
		if (!isCreative && canHurt) {
			boolean hasArmor = p.getArmorValue() > 0;
			boolean hasMount = false;
			try { var v = p.getVehicle(); hasMount = v instanceof net.minecraft.world.entity.LivingEntity; } catch (Exception ignored) {}
			int healthX = hw - 91 - pad;
			int healthY = hh - 39 - pad;
			int healthW = 86 + pad * 2;
			int healthH = 12 + pad * 2;
			// Health row always (if canHurt)
			appendRect(healthX, healthY, healthW, healthH, MAT_DOCK);
			// Armor/mount row — only when present, width based on actual icons (§9-11, shape not bbox)
			if (hasArmor || hasMount) {
				int armorIcons = hasArmor ? (p.getArmorValue() + 1) / 2 : 0;
				// mount health icons: use mount's health max /2
				if (hasMount) {
					try {
						var le = (net.minecraft.world.entity.LivingEntity) p.getVehicle();
						armorIcons = Math.max(armorIcons, (int) Math.ceil(le.getMaxHealth() / 2f));
					} catch (Exception ignored) {}
				}
				armorIcons = Math.min(armorIcons, 10);
				int armorW = Math.max(12 + pad*2, armorIcons * 8 + pad*2 + 2);
				int armorX = hw - 91 - pad;
				int armorY = hh - 50 - pad;
				int armorH = 12 + pad*2;
				appendRect(armorX, armorY, armorW, armorH, MAT_DOCK);
			}
		}
		// Right group: food / hunger + air — две отдельные маски, каждая по форме ряда (§12)
		if (!isCreative && canHurt) {
			int foodX = hw + 10 - pad;
			int foodY = hh - 39 - pad;
			int foodW = 86 + pad * 2;
			int foodH = 12 + pad * 2;
			appendRect(foodX, foodY, foodW, foodH, MAT_DOCK);
			if (p.getAirSupply() < p.getMaxAirSupply()) {
				int airX = hw + 10 - pad;
				int airY = hh - 50 - pad;
				int airW = 86 + pad * 2;
				int airH = 12 + pad * 2;
				appendRect(airX, airY, airW, airH, MAT_DOCK);
			}
		}
		// Center XP bar — виден только при наличии опыта и не в creative/spectator без XP
		if (!isSpectator && (p.totalExperience > 0 || p.experienceLevel > 0 || p.experienceProgress > 0)) {
			int xpX = hw - 91 - pad;
			int xpY = hh - 29 - pad;
			appendRect(xpX, xpY, 182 + pad*2, 6 + pad*2, MAT_DOCK);
		}
	}

	private static boolean luminanceDockEnabled = true;
	private static float dockPadding = 3f, dockOuterPad = 6f, dockCornerRadius = 6f, dockRefraction = 0.04f, dockDensity = 0.18f;
	private static float lightDirX = 0f, lightDirY = 1f, lightIntensity = 1f;

	private static float mainW() {
		Minecraft mc = Minecraft.getInstance();
		return mc.gameRenderer != null && mc.gameRenderer.mainRenderTarget() != null
			? mc.gameRenderer.mainRenderTarget().width : (float) pendingGuiW;
	}

	private static void appendRect(int x, int y, int w, int h, int mat) {
		if (pendingCount >= MAX_PANELS || w <= 0 || h <= 0) return;
		pendX[pendingCount] = x;
		pendY[pendingCount] = y;
		pendW[pendingCount] = w;
		pendH[pendingCount] = h;
		pendMat[pendingCount] = mat;
		pendingCount++;
	}
	private static int debugCount = 0;

	private static boolean frameDone = false;

	// Widget rects collected during Screen.extractRenderState (GUI units), consumed at draw.
	private static final int[] pendX = new int[MAX_PANELS];
	private static final int[] pendY = new int[MAX_PANELS];
	private static final int[] pendW = new int[MAX_PANELS];
	private static final int[] pendH = new int[MAX_PANELS];
	private static final int[] pendMat = new int[MAX_PANELS];
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
		if (parallaxStrength <= 0) return null;
		boolean haveTiles = slotTilesFrom >= 0 && slotTilesFrom < pendingCount;
		if (!haveTiles && wellCellCount == 0) return null;
		updateParallaxMouse();
		float scale = mainW() / pendingGuiW;
		float mgx = parX / scale, mgy = parY / scale;
		// GATE: parallax activates ONLY when the cursor is over a slot cell —
		// hovering tabs/buttons must not pull neighbouring items.
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
			appendRect(r[0], r[1], r[2], r[3], MAT_CONTROL);
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
		// Panel area resets each frame so the rect re-wins from CURRENT screen
		// geometry (recipe book open/close shifts the panel). Coordinates are
		// kept: captureTabFrame (fires on click, between frames) reads them.
		hudPanelArea = 0;
		blurMarkerSeen = false;
		pendingEntityDraw = null;
		pendingBookDraw = null;
		pendingBannerDraw = null;
		deferredSprites.clear();
		wellCount = 0;
		wellCellCount = 0;
		fxLit = -1f;
		fxCook = -1f;
		// §15 state leakage: при выходе из мира / hide HUD сбрасываем HUD-кольцо и dock
		if (!shouldRenderHud()) {
			hudSelX = -1f; hudSelTargetX = -1f; hudSelNanos = 0L; hudSelCenterYGui = -1;
		}
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
		float[] mats = new float[MAX_PANELS * 4];   // parallel material-ID array
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
			mats[i * 4] = pendMat[i];
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
			// uMats: parallel array right after uRects (std140 vec4 stride).
			int matsOff = MAX_PANELS * 16;
			for (int i = 0; i < MAX_PANELS; i++) {
				bb.putFloat(matsOff + i * 16, mats[i * 4]);
				// y/z/w stay zero (std140 padding).
			}
			int floatBytes = MAX_PANELS * 32 + MAX_WELLS * 3 * 16;   // tail after uWells
			// uWells: 12 grid-дескрипторов × 3 vec4, конвертация gui → fb px
			// (bottom-origin): центр ячейки [0][0], шаг со знаком −Y.
			int wellsOff = MAX_PANELS * 32;
			for (int wi = 0; wi < wellCount; wi++) {
				int s = wi * 12;
				float cellW = wells[s + 2] * scale, cellH = wells[s + 3] * scale;
				float orgX = (wells[s] + wells[s + 2] * 0.5f) * scale;
				float orgY = h - (wells[s + 1] + wells[s + 3] * 0.5f) * scale;
				bb.putFloat(wellsOff + wi * 48, orgX);
				bb.putFloat(wellsOff + wi * 48 + 4, orgY);
				bb.putFloat(wellsOff + wi * 48 + 8, cellW * 0.5f - 1.0f * scale); // inner half
				bb.putFloat(wellsOff + wi * 48 + 12, cellH * 0.5f - 1.0f * scale);
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
			// Gated by an OPEN CONTAINER SCREEN: without this the last panel
			// rect leaks into other screens (a ghost panel over the pause menu).
			boolean panelScreenOpen = mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
			int panelOff = floatBytes + 96;
			if (hudPanelArea > 0 && panelScreenOpen) {
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
			// uWellMeta = (wellCount, cookFill, tintStrength, 0).
			int wellMetaOff = floatBytes + 144;
			bb.putFloat(wellMetaOff, wellCount);
			bb.putFloat(wellMetaOff + 4, fxCook >= 0 ? fxCook : 0f);
			bb.putFloat(wellMetaOff + 8, tintStrength);
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
			bb.putFloat(lightOff + 12, 0f);
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

