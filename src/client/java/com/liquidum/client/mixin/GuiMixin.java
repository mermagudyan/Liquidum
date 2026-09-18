package com.liquidum.client.mixin;

import com.liquidum.client.settings.LiquidumVideoSettingsScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Vanilla settings open our hub, Sodium keeps its own screens
@Mixin(net.minecraft.client.gui.Gui.class)
public class GuiMixin {
	@Inject(method = "setScreen(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("HEAD"), cancellable = true)
	private void liquidum$videoHub(Screen screen, CallbackInfo ci) {
		try {
			if (!com.liquidum.client.LiquidumCore.getConfig().enabled) return;
			if (com.liquidum.client.debug.LiquidumDebugState.customSettings) return;
		} catch (Exception ignored) {
		}
		var mc = net.minecraft.client.Minecraft.getInstance();
		if (screen instanceof net.minecraft.client.gui.screens.options.VideoSettingsScreen
			&& !isSodiumLoaded()) {
			ci.cancel();
			mc.gui.setScreen(new LiquidumVideoSettingsScreen(mc.gui.screen()));
			return;
		}
		if (screen instanceof net.minecraft.client.gui.screens.options.OptionsScreen
			&& !isSodiumLoaded()) {
			ci.cancel();
			mc.gui.setScreen(new com.liquidum.client.settings.LiquidumSettingsScreen(mc.gui.screen()));
		}
	}

	// Sodium owns the video entry, our hub stays out of its way entirely
	private static boolean isSodiumLoaded() {
		try {
			return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium");
		} catch (Exception e) {
			return false;
		}
	}
}
