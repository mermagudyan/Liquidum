package com.liquidum.client.mixin;

import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(net.minecraft.client.renderer.ShaderManager.class)
public interface ShaderManagerAccessor {

	@Accessor("postChainProjection")
	Projection liquidum$getProjection();

	@Accessor("postChainProjectionMatrixBuffer")
	ProjectionMatrixBuffer liquidum$getProjectionMatrixBuffer();
}
