package com.liquidum.client.mixin;

import net.minecraft.client.gui.screens.options.UnsupportedGraphicsWarningScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

// Protected vanilla warning ctor, called with the same accept/cancel pair
@Mixin(UnsupportedGraphicsWarningScreen.class)
public interface WarningScreenInvoker {
	@Invoker("<init>")
	static UnsupportedGraphicsWarningScreen liquidum$create(Component title, List<Component> message,
		com.google.common.collect.ImmutableList<UnsupportedGraphicsWarningScreen.ButtonOption> options) {
		throw new AssertionError();
	}
}
