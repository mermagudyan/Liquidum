package com.liquidum.client;

import com.liquidum.client.debug.LiquidumDebugScreen;
import com.liquidum.LiquidumMod;
import com.liquidum.client.shader.LiquidGlassRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public class LiquidumClient implements ClientModInitializer {
	private static boolean labKeyWasDown = false;

	@Override
	public void onInitializeClient() {
		LiquidumMod.LOGGER.info("Liquidum initialized");
		LiquidumCore.init();

		// F7 opens the Liquidum Lab (raw GLFW poll - no keybinding API needed).
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			boolean down = GLFW.glfwGetKey(client.getWindow().handle(), GLFW.GLFW_KEY_F7) == GLFW.GLFW_PRESS;
			if (down && !labKeyWasDown && client.gui.screen() == null) {
				client.gui.setScreen(new LiquidumDebugScreen());
			}
			labKeyWasDown = down;
		});
	}
}
