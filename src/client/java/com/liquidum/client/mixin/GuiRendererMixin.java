package com.liquidum.client.mixin;

import com.liquidum.client.shader.LiquidGlassRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Late overlay point: widget draws flushed, staging open, state alive
@Mixin(net.minecraft.client.gui.render.GuiRenderer.class)
public abstract class GuiRendererMixin {

	// Staging still open here, mesh replay before vanilla upload seals it
	@Inject(method = "render()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/StagedVertexBuffer;upload()V"))
	private void liquidum$stageReplay(CallbackInfo ci) {
		var draws = liquidum$getDraws();
		LiquidGlassRenderer.replayCap = draws.size();
		LiquidGlassRenderer.replayStart = -1;
		if (!LiquidGlassRenderer.hasPopupReplay()) return;
		try {
			int s0 = draws.size();
			int firstDraw = liquidum$getFirstDrawIndexAfterBlur();
			int added = LiquidGlassRenderer.submitPopupReplay(liquidum$getRenderState());
			if (added <= 0) return;
			// Re-mesh repeats old draws deterministically, ours append last
			liquidum$meshRange(net.minecraft.client.renderer.state.gui.GuiRenderState.TraverseRange.AFTER_BLUR);
			LiquidGlassRenderer.replayStart = 2 * s0 - firstDraw;
		} catch (Exception ignored) {
			LiquidGlassRenderer.replayStart = -1;
		}
	}

	// Widget ranges never swallow replay draws, ours run late instead
	@WrapOperation(method = "draw()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;executeDrawRange(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/pipeline/RenderTarget;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;II)V"))
	private void liquidum$capWidgetRange(net.minecraft.client.gui.render.GuiRenderer instance, java.util.function.Supplier<String> label, com.mojang.blaze3d.pipeline.RenderTarget target, com.mojang.blaze3d.buffers.GpuBufferSlice slice, int start, int end, Operation<Void> original) {
		original.call(instance, label, target, slice, start, Math.min(end, LiquidGlassRenderer.replayCap));
	}

	// Full frame in main here, reset has not run yet
	@Inject(method = "render()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/StagedVertexBuffer;endDraw()V"))
	private void liquidum$lateOverlay(CallbackInfo ci) {
		try {
			if (LiquidGlassRenderer.hasPopupTiles()) {
				LiquidGlassRenderer.renderOverlayPostChain();
			}
		} catch (Exception ignored) {
		}
		try {
			liquidum$replayPopupRows();
		} catch (Exception ex) {
			if (!replayWarned) {
				replayWarned = true;
				com.liquidum.LiquidumMod.LOGGER.warn("[glass] popup replay failed, rows missing", ex);
			}
		}
		LiquidGlassRenderer.resetFrame();
	}

	private static boolean replayWarned = false;

	// Stashed popup rows draw crisp above glass via their own range
	private void liquidum$replayPopupRows() {
		int start = LiquidGlassRenderer.replayStart;
		if (start < 0) return;
		int s1 = liquidum$getDraws().size();
		if (start >= s1) return;
		var mc = net.minecraft.client.Minecraft.getInstance();
		var window = mc.gameRenderer.gameRenderState().windowRenderState;
		float gw = (float) window.width / (float) window.guiScale;
		float gh = (float) window.height / (float) window.guiScale;
		liquidum$getProjection().setupOrtho(1000f, 11000f, gw, gh, true);
		var slice = liquidum$getProjectionBuffer().getBuffer(liquidum$getProjection());
		com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(slice, com.mojang.blaze3d.ProjectionType.ORTHOGRAPHIC);
		var target = mc.gameRenderer.mainRenderTarget();
		var transform = com.mojang.blaze3d.systems.RenderSystem.getDynamicUniforms()
			.writeTransform(new org.joml.Matrix4f().setTranslation(0f, 0f, -11000f));
		liquidum$executeRange(() -> "liquidum replay", target, transform, start, s1);
	}

	@Accessor("renderState")
	abstract net.minecraft.client.renderer.state.gui.GuiRenderState liquidum$getRenderState();

	@Accessor("draws")
	abstract java.util.List liquidum$getDraws();

	@Accessor("firstDrawIndexAfterBlur")
	abstract int liquidum$getFirstDrawIndexAfterBlur();

	@Accessor("guiProjection")
	abstract net.minecraft.client.renderer.Projection liquidum$getProjection();

	@Accessor("guiProjectionMatrixBuffer")
	abstract net.minecraft.client.renderer.ProjectionMatrixBuffer liquidum$getProjectionBuffer();

	@Invoker("addElementsToMeshes")
	abstract void liquidum$meshRange(net.minecraft.client.renderer.state.gui.GuiRenderState.TraverseRange range);

	@Invoker("executeDrawRange")
	abstract void liquidum$executeRange(java.util.function.Supplier<String> label, com.mojang.blaze3d.pipeline.RenderTarget target, com.mojang.blaze3d.buffers.GpuBufferSlice transform, int start, int end);
}
