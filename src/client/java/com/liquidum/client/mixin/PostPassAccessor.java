package com.liquidum.client.mixin;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.PostPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(PostPass.class)
public interface PostPassAccessor {

	@Accessor("customUniforms")
	Map<String, GpuBuffer> liquidum$getCustomUniforms();

	@Accessor("pipeline")
	RenderPipeline liquidum$getPipeline();
}
