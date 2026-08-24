package com.liquidum.client.mixin;

import com.liquidum.client.shader.LiquidGlassRenderer;
import net.minecraft.client.renderer.ShaderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShaderManager.class)
public class ShaderManagerMixin {

	/**
	 * Capture the freshly applied shader configs (post chains live there) so
	 * Liquidum can load its chain directly, bypassing the failure-caching
	 * getPostChain path during startup races and resource reloads.
	 */
	@Inject(
		method = "apply(Ljava/lang/Object;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
		at = @At("TAIL")
	)
	private void liquidum$onConfigsApplied(Object configs, net.minecraft.server.packs.resources.ResourceManager resourceManager, net.minecraft.util.profiling.ProfilerFiller profiler, CallbackInfo ci) {
		LiquidGlassRenderer.onShaderConfigs(configs);
	}
}
