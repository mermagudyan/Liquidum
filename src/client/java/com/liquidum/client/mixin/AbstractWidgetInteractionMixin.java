package com.liquidum.client.mixin;

import com.liquidum.client.interaction.ButtonInteractionHandler;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractWidget.class)
public class AbstractWidgetInteractionMixin {

	/**
	 * P1 iOS-style press: action on mouseUp, pressed visual while held.
	 * Only for AbstractButton (sliders keep vanilla drag).
	 */
	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void liquidum$iosPress(MouseButtonEvent event, boolean bl, CallbackInfoReturnable<Boolean> cir) {
		if (!((Object) this instanceof AbstractButton btn)) return;
		if (ButtonInteractionHandler.onButtonMouseClicked(btn, event, bl)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
	private void liquidum$iosRelease(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
		if (!((Object) this instanceof AbstractButton btn)) return;
		if (ButtonInteractionHandler.onButtonMouseReleased(btn, event)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "mouseDragged", at = @At("HEAD"))
	private void liquidum$iosDrag(MouseButtonEvent event, double d, double e, CallbackInfoReturnable<Boolean> cir) {
		if (!((Object) this instanceof AbstractButton btn)) return;
		try {
			ButtonInteractionHandler.onButtonDragged(btn, event.x(), event.y());
		} catch (Exception ignored) {}
	}
}
