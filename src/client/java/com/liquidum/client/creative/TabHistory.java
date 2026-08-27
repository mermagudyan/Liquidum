package com.liquidum.client.creative;

import net.minecraft.world.item.CreativeModeTab;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Navigation stack of opened creative tabs (iOS Safari-tabs semantics).
 * - Selecting a tab NOT in the stack = push (forward swipe).
 * - Selecting an EARLIER tab = pop-to: everything opened after it closes.
 * - Persists until the player leaves the world (cleared on disconnect).
 */
public final class TabHistory {

	public enum Transition {
		NONE, PUSH, POP
	}

	private static final Deque<CreativeModeTab> STACK = new ArrayDeque<>();

	private TabHistory() {
	}

	/**
	 * Register a tab switch, returns the transition kind for the animator.
	 * tab = the tab being switched TO.
	 */
	public static Transition transitionTo(CreativeModeTab tab) {
		if (tab == null) return Transition.NONE;
		if (STACK.isEmpty()) {
			STACK.push(tab);
			return Transition.NONE;              // first ever: no animation
		}
		if (STACK.peek() == tab) return Transition.NONE;
		if (STACK.contains(tab)) {
			// pop-to: drop everything opened after it
			while (STACK.peek() != tab) STACK.pop();
			return Transition.POP;
		}
		STACK.push(tab);
		return Transition.PUSH;
	}

	public static void clear() {
		STACK.clear();
	}
}
