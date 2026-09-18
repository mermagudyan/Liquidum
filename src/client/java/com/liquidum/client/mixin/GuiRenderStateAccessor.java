package com.liquidum.client.mixin;

import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Ground truth for the once-per-frame blur-stratum marker
@Mixin(GuiRenderState.class)
public interface GuiRenderStateAccessor {
	@Accessor("firstStratumAfterBlur")
	int liquidum$getFirstStratumAfterBlur();
}
