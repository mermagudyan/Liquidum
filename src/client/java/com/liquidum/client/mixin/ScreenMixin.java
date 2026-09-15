package com.liquidum.client.mixin;

import com.liquidum.client.interaction.ButtonInteractionHandler;
import com.liquidum.client.shader.LiquidGlassRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(Screen.class)
public class ScreenMixin {

	@Shadow
	public int width;
	@Shadow
	public int height;

	/**
	 * Collect visible widget bounds (GUI units) right after the screen extracted
	 * its state; the renderer converts them to framebuffer pixels and feeds the
	 * glass post-chain before UI strata are drawn.
	 */
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void liquidum$collectWidgetRects(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		// L4 FOREGROUND: переигрываем отложенные модели (игрок, книга,
		// флаг) и progress-иконки печек/точил/столов зачарования в widget-фазе
		// — они рендерятся ПОВЕРХ glass composite, sharp.
		LiquidGlassRenderer.replayForeground(guiGraphics);
		LiquidGlassRenderer.replayDeferredSprites(guiGraphics);
		if (com.liquidum.client.compat.LiquidumOptOut.isOptedOut((Screen)(Object)this)) return;
		if (!LiquidGlassRenderer.isEnabled() || !LiquidGlassRenderer.replaceVanillaButtonBackground()) return;
		List<int[]> rects = new ArrayList<>();
		StringBuilder dump = null;
		if (LiquidGlassRenderer.DEBUG && LiquidGlassRenderer.dumpWidgetClasses) {
			LiquidGlassRenderer.dumpWidgetClasses = false;
			dump = new StringBuilder("[glass] widgets on ").append(getClass().getSimpleName()).append(":");
		}
		collect(((Screen) (Object) this).children(), rects, dump);
		if (dump != null) com.liquidum.LiquidumMod.LOGGER.info(dump.toString());
		LiquidGlassRenderer.submitWidgets(this.width, this.height, rects);
	}

	private void collect(Iterable<? extends net.minecraft.client.gui.components.events.GuiEventListener> listeners,
	                     List<int[]> rects, StringBuilder dump) {
		int base = ((Object) this instanceof AbstractContainerScreen)
			? 500 : com.liquidum.client.shader.LiquidumLayers.MENU_FUSE_BASE;
		collectClipped(listeners, rects, dump, null, base);
	}

	private void collectClipped(Iterable<? extends net.minecraft.client.gui.components.events.GuiEventListener> listeners,
	                            List<int[]> rects, StringBuilder dump, int[] clip, int base) {
		for (var listener : listeners) {
			if (dump != null) dump.append('\n').append("  ").append(listener.getClass().getName());
			// Descend into containers (tab bars, grids, tab contents) — many
			// screens keep their buttons nested, not as direct children.
			if (listener instanceof net.minecraft.client.gui.components.events.ContainerEventHandler container) {
				int[] sub = clip;
				if (listener instanceof net.minecraft.client.gui.components.AbstractSelectionList<?> list) {
					sub = new int[]{ list.getX(), list.getY(), list.getX() + list.getWidth(), list.getY() + list.getHeight() };
				}
				collectClipped(container.children(), rects, dump, sub, base);
				continue;
			}
			// Recipe tabs carry their own sprite tile with selected state
			if (listener.getClass().getName().contains("recipebook.")) continue;
			if (listener instanceof net.minecraft.client.gui.components.PlainTextButton) continue;
			if (!(listener instanceof AbstractButton) && !(listener instanceof AbstractSliderButton)) continue;
			AbstractWidget widget = (AbstractWidget) listener;
			if (!widget.visible) continue;
			int w = widget.getWidth();
			int h = widget.getHeight();
			if (w <= 0 || h <= 0) continue;
			// P1: без беления — фокус/hover без MAT_ACTIVE, только пружина (§T)
			int mat = LiquidGlassRenderer.MAT_CONTROL;
			if (!widget.active) mat = LiquidGlassRenderer.MAT_GROUP;
			// Pressed state already drives scale via handler; keep mat CONTROL for press
			if (listener instanceof AbstractButton btn) {
				if (ButtonInteractionHandler.isPressed(btn)) mat = LiquidGlassRenderer.MAT_CONTROL;
				float s = ButtonInteractionHandler.getScale(btn);
				if (s != 1.0f && s > 0.8f && s < 1.2f) {
					int nw = Math.round(w * s);
					int nh = Math.round(h * s);
					int nx = widget.getX() + (w - nw)/2;
					int ny = widget.getY() + (h - nh)/2;
					addTile(rects, new int[]{ nx, ny, nw, nh, mat, base + rects.size() }, clip);
					continue;
				}
			}
			addTile(rects, new int[] { widget.getX(), widget.getY(), w, h, mat, base + rects.size() }, clip);
		}
	}

	private static void addTile(List<int[]> rects, int[] r, int[] clip) {
		if (clip != null) {
			int x0 = Math.max(r[0], clip[0]);
			int y0 = Math.max(r[1], clip[1]);
			int x1 = Math.min(r[0] + r[2], clip[2]);
			int y1 = Math.min(r[1] + r[3], clip[3]);
			if (x1 <= x0 || y1 <= y0) return;
			r[0] = x0;
			r[1] = y0;
			r[2] = x1 - x0;
			r[3] = y1 - y0;
		}
		rects.add(r);
	}

	@Inject(method = "extractBlurredBackground", at = @At("HEAD"), cancellable = true)
	private void liquidum$suppressVanillaBlurFlag(CallbackInfo ci) {
		// ALWAYS cancel vanilla's marker here. Container screens have
		// isInGameUi=false, so vanilla fires this MID-extractBackground —
		// BEFORE the container panel texture is recorded — which pushed the
		// panel into the after-blur phase, covering our glass tiles.
		// The fallback in liquidum$afterBackground sets the marker AFTER the
		// whole extractBackground (dim + panel included): background goes
		// below the glass, slots/items above it. Vanilla's actual blur is
		// cancelled in GameRendererMixin, its backdrop quad in
		// extractMenuBackground below.
		if (!LiquidGlassRenderer.isEnabled()) return;
		ci.cancel();
	}

	@Inject(method = "extractMenuBackground", at = @At("HEAD"), cancellable = true)
	private void liquidum$suppressMenuBackdrop(CallbackInfo ci) {
		// The vanilla menu backdrop quad samples the blur target we never
		// render (vanilla blur is cancelled), so it would paint a flat stale
		// color over our tiles. Our glassout IS the backdrop now.
		if (!LiquidGlassRenderer.isEnabled()) return;
		ci.cancel();
	}

	/**
	 * P0: remove the fullscreen background dim for container screens.
	 *
	 * Vanilla {@code Screen.extractTransparentBackground(GuiGraphicsExtractor)}
	 * paints a fullscreen translucent dark overlay (fillGradient(0,0,w,h,
	 * -1072689136,-804253680), alpha ~0.75) into the background stratum. Our
	 * glass chain samples that stratum at processBlurEffect, so the world
	 * behind every container GUI was being darkened ~50-70% before the glass
	 * even touched it. Cancel ONLY for containers; PauseScreen keeps dim.
	 */
	@Inject(method = "extractTransparentBackground", at = @At("HEAD"), cancellable = true)
	private void liquidum$removeContainerWorldDim(GuiGraphicsExtractor guiGraphics, CallbackInfo ci) {
		if (!LiquidGlassRenderer.isEnabled()) return;
		if ((Object) this instanceof AbstractContainerScreen) {
			ci.cancel();
		}
	}

	/**
	 * Runs for EVERY screen right after extractBackground (even when subclasses
	 * override it): record the glass backdrop blit. The chain itself runs at
	 * processBlurEffect (GameRendererMixin) — BEFORE this blit executes in the
	 * after-range, so glassout is fresh. Widgets extract after this point and
	 * draw above the glass.
	 */
	private static int debugBlitLog = 0;

	@Inject(
		method = "extractRenderStateWithTooltipAndSubtitles",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/Screen;extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
			shift = At.Shift.AFTER
		)
	)
	private void liquidum$afterBackground(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (!LiquidGlassRenderer.isEnabled()) return;
		if (!LiquidGlassRenderer.isBlurMarkerSeen()) {
			guiGraphics.nextStratum();
			guiGraphics.blurBeforeThisStratum();
			LiquidGlassRenderer.setBlurMarkerSeen();
		}
		LiquidGlassRenderer.replayHudBar(guiGraphics);
		if (com.liquidum.client.compat.LiquidumOptOut.isOptedOut((Screen)(Object)this)) return;
		if ((Object) this instanceof AbstractContainerScreen) {
			com.liquidum.client.shader.LiquidumLayers.beginInventoryObjects(guiGraphics);
		} else {
			com.liquidum.client.shader.LiquidumLayers.beginPauseBase(guiGraphics);
		}
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void liquidum$onScreenInit(CallbackInfo ci) {
		LiquidGlassRenderer.dumpWidgetClasses = true;
		if (LiquidGlassRenderer.DEBUG) {
			com.liquidum.LiquidumMod.LOGGER.info("[glass] screen init: {}@{}",
				getClass().getSimpleName(), Integer.toHexString(hashCode()));
		}
		LiquidGlassRenderer.startAnimation(true);
	}

	@Inject(method = "removed", at = @At("HEAD"))
	private void liquidum$onScreenRemoved(CallbackInfo ci) {
		LiquidGlassRenderer.startAnimation(false);
	}
}
