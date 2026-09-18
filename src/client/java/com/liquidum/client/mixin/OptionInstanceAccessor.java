package com.liquidum.client.mixin;

import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.function.Function;

// Vanilla option display data for custom settings rows
@Mixin(OptionInstance.class)
public interface OptionInstanceAccessor<T> {
	@Accessor("caption")
	Component liquidum$getCaption();

	@Accessor("toString")
	Function<T, Component> liquidum$getValueText();

	@Accessor("tooltip")
	OptionInstance.TooltipSupplier<T> liquidum$getTooltipSupplier();
}
