package com.liquidum.client.compat;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;

/**
 * Optional Iris integration (§29–35).
 * No hard dependency: checked via reflection at runtime; no ClassNotFound when absent.
 * Correct capture phase: Liquidum samples main color target AFTER Iris has resolved
 * its shader pack result (i.e. the currentWorldColorTarget). In VANILLA mode we use
 * mc.gameRenderer.mainRenderTarget(). In IRIS_COMPAT we try to resolve Iris's
 * swapped target via reflection on Iris API if present.
 */
public final class IrisCompat {
	private IrisCompat(){}

	public enum Mode { AUTO, VANILLA, IRIS_COMPAT }

	public static Mode fromConfig(String s) {
		if (s == null) return Mode.AUTO;
		return switch (s.toLowerCase()) {
			case "vanilla" -> Mode.VANILLA;
			case "iris_compat", "force" -> Mode.IRIS_COMPAT;
			default -> Mode.AUTO;
		};
	}

	public static boolean isIrisPresent() {
		try { Class.forName("net.irisshaders.iris.api.v0.IrisApi"); return true; } catch (ClassNotFoundException e) { return false; }
	}

	/** Resolve current world color target — falls back to vanilla main. */
	public static RenderTarget getWorldColorTarget(Minecraft mc) {
		Mode m;
		try { m = fromConfig(com.liquidum.client.LiquidumCore.getConfig().irisIntegration); } catch(Exception e){ m = Mode.AUTO; }
		if (m == Mode.VANILLA) return mc.gameRenderer.mainRenderTarget();
		if (m == Mode.IRIS_COMPAT || (m == Mode.AUTO && isIrisPresent())) {
			// Try Iris API via reflection: IrisApi.getInstance().getMainTarget() or similar
			try {
				Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
				Object inst = api.getMethod("getInstance").invoke(null);
				// Many Iris versions expose getPipelineManager etc; we probe for getMainTarget
				// If not found, fallback to vanilla
				try {
					var mt = api.getMethod("getMainTarget");
					Object rt = mt.invoke(inst);
					if (rt instanceof RenderTarget) return (RenderTarget) rt;
				} catch (NoSuchMethodException ignored) {}
			} catch (Exception ignored) {}
		}
		return mc.gameRenderer.mainRenderTarget();
	}
}
