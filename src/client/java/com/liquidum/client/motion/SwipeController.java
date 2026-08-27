package com.liquidum.client.motion;

import com.liquidum.client.config.LiquidumConfig;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * Swipe Inventory gesture state machine (§4–9).
 * Works with mouse/trackpad (and touch if platform provides it).
 * Gesture arbitration: before threshold input stays as normal click/drag;
 * after capture the sequence is owned by swipe and not delivered as slot drag.
 */
public class SwipeController {

	private enum State { IDLE, POSSIBLE, DRAGGING, SETTLING }

	private static State state = State.IDLE;
	private static float startX, startY;
	private static float progress = 1f; // 0 closed, 1 open (inventory is open -> 1)
	private static final float THRESHOLD = 8f; // gui px before capture
	private static final SpringPhysics spring = new SpringPhysics(1f);
	private static long lastNanos = 0L;

	/** Should be called from ScreenMixin on mouse press to check start zone. */
	public static boolean onPress(AbstractContainerScreen<?> screen, double mx, double my, int button) {
		if (!isEnabled()) return false;
		// Start zones: free panel area or edge zone, NOT inside a Slot hitbox.
		// Slot hitboxes have priority — we check isHoveringSlot.
		if (isHoveringSlot(screen, mx, my)) return false;
		startX = (float) mx;
		startY = (float) my;
		state = State.POSSIBLE;
		lastNanos = System.nanoTime();
		return false; // do not consume, let vanilla handle click
	}

	public static boolean onDrag(AbstractContainerScreen<?> screen, double mx, double my, double dx, double dy) {
		if (!isEnabled() || state == State.IDLE) return false;
		float dist = (float) Math.hypot(mx - startX, my - startY);
		if (state == State.POSSIBLE) {
			if (dist < THRESHOLD) return false; // still normal drag
			// capture
			state = State.DRAGGING;
		}
		if (state == State.DRAGGING) {
			// Vertical swipe close: drag up reduces progress.
			float raw = (float) (my - startY); // positive = down
			// Overscroll resistance beyond limits
			if (raw < 0) { // close
				float p = 1f + raw / 120f; // 120px to close
				if (p < 0) p = ElasticOverscroll.apply(p * 120f) / 120f + 1f;
				progress = Math.max(-0.3f, Math.min(1f, p));
			} else { // overscroll beyond open
				progress = 1f + ElasticOverscroll.apply(raw) / 120f;
				progress = Math.min(1.25f, progress);
			}
			return true; // consume drag
		}
		return false;
	}

	public static void onRelease(double mx, double my, float velocityY) {
		if (state != State.DRAGGING) { state = State.IDLE; return; }
		// Release decision uses velocity, not just progress (§6)
		boolean shouldClose = progress < 0.5f || velocityY < -800f;
		spring.position = progress;
		spring.velocity = velocityY / 120f;
		spring.target = shouldClose ? 0f : 1f;
		// Reduced motion shortens settle
		if (isReducedMotion()) {
			spring.stiffness = 600f; spring.damping = 45f;
		}
		state = State.SETTLING;
		lastNanos = System.nanoTime();
	}

	public static void tick() {
		if (state != State.SETTLING) return;
		long now = System.nanoTime();
		float dt = (now - lastNanos) / 1e9f;
		lastNanos = now;
		spring.update(dt);
		progress = spring.position;
		if (spring.isAtRest()) {
			state = State.IDLE;
			// TODO: actually close screen when target==0 -> needs screen close logic
		}
	}

	public static float getProgress() { return progress; }
	public static boolean isDragging() { return state == State.DRAGGING; }

	private static boolean isEnabled() {
		try {
			LiquidumConfig c = com.liquidum.client.LiquidumCore.getConfig();
			return c.swipeEnabled && !c.reducedMotion || c.swipeEnabled; // reduced motion keeps functional
		} catch (Exception e) { return false; }
	}
	private static boolean isReducedMotion() {
		try { return com.liquidum.client.LiquidumCore.getConfig().reducedMotion; } catch (Exception e){ return false; }
	}
	private static boolean isHoveringSlot(AbstractContainerScreen<?> screen, double mx, double my) {
		// Use screen's hoveredSlot via accessor if available, else approximate
		try {
			var acc = (com.liquidum.client.mixin.AbstractContainerScreenAccessor)(Object) screen;
			return acc.liquidum$getHoveredSlot() != null;
		} catch (Exception e) { return false; }
	}
}
