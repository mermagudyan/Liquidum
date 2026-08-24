package com.liquidum.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(targets = "net.minecraft.client.renderer.ShaderManager$Configs")
public interface ConfigsAccessor {

	@Accessor("postChains")
	Map<Object, Object> liquidum$getPostChains();
}
