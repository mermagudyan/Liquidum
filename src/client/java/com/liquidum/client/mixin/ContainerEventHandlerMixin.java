package com.liquidum.client.mixin;

import com.liquidum.client.debug.LiquidumDebugState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Debug probe drag on every container using the default dispatch
@Mixin(net.minecraft.client.gui.components.events.ContainerEventHandler.class)
public interface ContainerEventHandlerMixin {

	@Inject(method = "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z", at = @At("HEAD"), cancellable = true)
	private void liquidum$probeGrab(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
		if (!LiquidumDebugState.probeShow || event.button() != 0 || LiquidumDebugState.probeDrag) return;
		float r = LiquidumDebugState.probeDiameter * 0.5f;
		float dx = (float) event.x() - LiquidumDebugState.probeX;
		float dy = (float) event.y() - LiquidumDebugState.probeY;
		if (dx * dx + dy * dy <= r * r) {
			LiquidumDebugState.probeDrag = true;
			LiquidumDebugState.probeGrabDX = dx;
			LiquidumDebugState.probeGrabDY = dy;
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "mouseDragged(Lnet/minecraft/client/input/MouseButtonEvent;DD)Z", at = @At("HEAD"), cancellable = true)
	private void liquidum$probeMove(net.minecraft.client.input.MouseButtonEvent event, double dx, double dy, CallbackInfoReturnable<Boolean> cir) {
		if (!LiquidumDebugState.probeDrag) return;
		LiquidumDebugState.probeX = (float) event.x() - LiquidumDebugState.probeGrabDX;
		LiquidumDebugState.probeY = (float) event.y() - LiquidumDebugState.probeGrabDY;
		cir.setReturnValue(true);
	}

	@Inject(method = "mouseReleased(Lnet/minecraft/client/input/MouseButtonEvent;)Z", at = @At("HEAD"))
	private void liquidum$probeDrop(net.minecraft.client.input.MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
		LiquidumDebugState.probeDrag = false;
	}
}
