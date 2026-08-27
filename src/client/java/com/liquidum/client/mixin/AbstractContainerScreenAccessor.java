package com.liquidum.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Access to the inherited protected menu field of container screens (the
 * field is declared in AbstractContainerScreen, so a @Shadow in a mixin
 * targeting a SUBCLASS can't see it).
 */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {

	@Accessor("menu")
	AbstractContainerMenu liquidum$getMenu();

	/** Слот под курсором (для hover-ячейки GridWell). */
	@Accessor("hoveredSlot")
	net.minecraft.world.inventory.Slot liquidum$getHoveredSlot();

	@Accessor("leftPos")
	int liquidum$getLeftPos();
	@Accessor("topPos")
	int liquidum$getTopPos();
	@Accessor("imageWidth")
	int liquidum$getImageWidth();
	@Accessor("imageHeight")
	int liquidum$getImageHeight();
}
