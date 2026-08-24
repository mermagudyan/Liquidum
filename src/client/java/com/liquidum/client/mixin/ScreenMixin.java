package com.liquidum.client.mixin;

import com.liquidum.client.shader.LiquidGlassRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
		for (var listener : listeners) {
			if (dump != null) dump.append('\n').append("  ").append(listener.getClass().getName());
			// Descend into containers (tab bars, grids, tab contents) — many
			// screens keep their buttons nested, not as direct children.
			if (listener instanceof net.minecraft.client.gui.components.events.ContainerEventHandler container) {
				collect(container.children(), rects, dump);
				continue;
			}
			if (!(listener instanceof AbstractButton) && !(listener instanceof AbstractSliderButton)) continue;
			AbstractWidget widget = (AbstractWidget) listener;
			if (!widget.visible) continue;
			int w = widget.getWidth();
			int h = widget.getHeight();
			if (w <= 0 || h <= 0) continue;
			rects.add(new int[] { widget.getX(), widget.getY(), w, h });
		}
	}

	@Inject(method = "extractBlurredBackground", at = @At("HEAD"), cancellable = true)
	private void liquidum$suppressVanillaBlurFlag(CallbackInfo ci) {
		// Vanilla sets the blur flag here; we set our own at extractBackground
		// TAIL instead — only one flag per frame is allowed.
		ci.cancel();
	}

	@Inject(method = "extractMenuBackground", at = @At("HEAD"), cancellable = true)
	private void liquidum$suppressMenuBackdrop(CallbackInfo ci) {
		// The vanilla menu backdrop quad (gradient/blurred sample) draws AFTER
		// our glass blit and covers the tiles with a flat color from the stale
		// blur target. Our glassout IS the backdrop now.
		ci.cancel();
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
		// NOTE: no blur-boundary flag here! Setting it makes the engine draw its
		// own blurred-backdrop quad (sampling the stale blur target = flat color)
		// over our tiles. Without the flag there is no engine backdrop; our
		// glassout blit below is the backdrop, widgets draw above it.
		// The glass result is now composited straight into the screen by the
		// post-chain's final pass (glass.json: pass 2 writes minecraft:main),
		// so no GUI-extraction blit is needed here. A raw GpuTextureView blit
		// during extraction is NOT preserved by the deferred GuiRenderState,
		// which is why the old blit produced a flat screen.
		return;
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void liquidum$onScreenInit(CallbackInfo ci) {
		LiquidGlassRenderer.dumpWidgetClasses = true;
		LiquidGlassRenderer.startAnimation(true);
	}

	@Inject(method = "removed", at = @At("HEAD"))
	private void liquidum$onScreenRemoved(CallbackInfo ci) {
		LiquidGlassRenderer.startAnimation(false);
	}
}
