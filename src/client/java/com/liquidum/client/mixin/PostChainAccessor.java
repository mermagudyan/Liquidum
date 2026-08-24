package com.liquidum.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.PostChain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(PostChain.class)
public interface PostChainAccessor {

	@Accessor("passes")
	java.util.List<net.minecraft.client.renderer.PostPass> liquidum$getPasses();

	@Accessor("persistentTargets")
	Map<Object, RenderTarget> liquidum$getPersistentTargets();
}
