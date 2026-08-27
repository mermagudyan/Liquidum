package com.liquidum.client.mixin;

import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Accessor for the protected scroll math of the creative ItemPickerMenu —
 * needed by the smooth-scroll animator (mixin classes can't call protected
 * members of the target hierarchy directly).
 */
@Mixin(CreativeModeInventoryScreen.ItemPickerMenu.class)
public interface ItemPickerMenuAccessor {

	@Invoker("subtractInputFromScroll")
	float liquidum$subtractInputFromScroll(float scrollOffs, double delta);
}
