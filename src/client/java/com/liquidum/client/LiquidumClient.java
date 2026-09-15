package com.liquidum.client;

import com.liquidum.client.creative.TabHistory;
import com.liquidum.client.debug.LiquidumDebugScreen;
import com.liquidum.client.lab.LiquidumLabScreen;
import com.liquidum.client.interaction.ButtonInteractionHandler;
import com.liquidum.LiquidumMod;
import com.liquidum.client.shader.LiquidGlassRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public class LiquidumClient implements ClientModInitializer {
	private static boolean labKeyWasDown = false;
	private static boolean yaclKeyWasDown = false;

	@Override
	public void onInitializeClient() {
		LiquidumMod.LOGGER.info("Liquidum initialized");
		if (com.liquidum.client.debug.LiquidumDebugState.DEBUG_BUILD) {
			LiquidumMod.LOGGER.info("Liquidum debug mode (-Dliquidum.debug=true)");
		}
		LiquidumCore.init();
		// Профиль из F8 (liquidum_profiles.json), fallback — Wow
		try { com.liquidum.client.config.LiquidumProfiles.load(); } catch(Exception ignored){}
		try { if (com.liquidum.client.debug.LiquidumDebugState.refraction <= 0.01f) com.liquidum.client.config.LiquidumProfiles.ETALON.apply(); } catch(Exception ignored){}

		// F7 toggles the new Material Lab, F_SHIFT+F7 legacy debug
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			boolean down = GLFW.glfwGetKey(client.getWindow().handle(), GLFW.GLFW_KEY_F7) == GLFW.GLFW_PRESS;
			if (down && !labKeyWasDown) {
				boolean shift = GLFW.glfwGetKey(client.getWindow().handle(), GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
					|| GLFW.glfwGetKey(client.getWindow().handle(), GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
				if (client.gui.screen() instanceof LiquidumLabScreen lab) {
					lab.onClose();
				} else if (client.gui.screen() instanceof LiquidumDebugScreen dbg && !shift) {
					client.gui.setScreen(new LiquidumLabScreen(dbg));
				} else if (shift) {
					if (client.gui.screen() instanceof LiquidumDebugScreen d2) {
						d2.onClose();
					} else {
						client.gui.setScreen(new LiquidumDebugScreen());
					}
				} else {
					client.gui.setScreen(new LiquidumLabScreen(client.gui.screen()));
				}
			}
			labKeyWasDown = down;
			boolean yaclDown = GLFW.glfwGetKey(client.getWindow().handle(), GLFW.GLFW_KEY_F8) == GLFW.GLFW_PRESS;
			if (yaclDown && !yaclKeyWasDown) {
				if (client.gui.screen() instanceof LiquidumLabScreen lab2) {
					lab2.onClose();
				} else if (!(client.gui.screen() instanceof LiquidumDebugScreen)) {
					client.gui.setScreen(new LiquidumLabScreen(client.gui.screen()));
				}
			}
			yaclKeyWasDown = yaclDown;
			ButtonInteractionHandler.tick(client);
		});

		// Creative Tab Stack: history persists until the player leaves the world.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> TabHistory.clear());
	}
}
