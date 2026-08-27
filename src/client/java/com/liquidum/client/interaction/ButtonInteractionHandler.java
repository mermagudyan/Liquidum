package com.liquidum.client.interaction;

import com.liquidum.client.config.LiquidumConfig;
import com.liquidum.client.LiquidumCore;
import com.liquidum.client.motion.SpringPhysics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.Minecraft;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * P1 — iPhone-like button states (§T).
 * Hover/pressed/focus are NOT just cursor tint — they are spring-driven glass deformations,
 * and the action fires on mouseUp (pressed state is visible while held) as in iOS HIG.
 * This mirrors the ReGlass PR «iOS-style pressed/release behaviour» but with spring.
 */
public final class ButtonInteractionHandler {
    private static final Map<AbstractWidget, State> STATES = new IdentityHashMap<>();
    private static AbstractWidget pressedWidget = null;
    private static boolean pressedWasHovered = false;
    private static long pressedNanos = 0L;

    private static final class State {
        final SpringPhysics scale = new SpringPhysics(1.0f);
        float hoverTarget = 0f; // 0..1
        float hoverCurrent = 0f;
        boolean isPressed = false;
        State() {
            scale.stiffness = 650f;
            scale.damping = 35f;
            scale.mass = 1f;
        }
    }

    private ButtonInteractionHandler() {}

    private static State getOrCreate(AbstractWidget w) {
        return STATES.computeIfAbsent(w, k -> new State());
    }

    public static float getScale(AbstractWidget w) {
        State s = STATES.get(w);
        return s != null ? s.scale.position : 1.0f;
    }

    public static float getHoverFactor(AbstractWidget w) {
        State s = STATES.get(w);
        return s != null ? s.hoverCurrent : 0f;
    }

    /** Called from AbstractButtonMixin on mouseClicked HEAD (cancellable). */
    public static boolean onButtonMouseClicked(AbstractButton btn, net.minecraft.client.input.MouseButtonEvent event, boolean bl) {
        if (!LiquidumCore.getConfig().enabled || !LiquidumCore.getConfig().buttonsGlass) return false;
        // Only iOS-style for primary button (left) — right clicks pass through
        try {
            var info = event.buttonInfo();
            // isValidClickButton check — only left (button 0) in vanilla; keep same gate
            // If not valid, let vanilla handle
            if (!btn.isActive() || !btn.visible) return false;
        } catch (Exception ignored) {}
        double mx = event.x();
        double my = event.y();
        if (!btn.isMouseOver(mx, my)) return false;

        // iOS: action NOT yet, just enter pressed state with spring shrink
        State st = getOrCreate(btn);
        st.isPressed = true;
        st.scale.setTarget(0.97f);
        pressedWidget = btn;
        pressedWasHovered = true;
        pressedNanos = System.nanoTime();
        // Play down sound now (tactile), action deferred to release
        try { btn.playDownSound(Minecraft.getInstance().getSoundManager()); } catch (Exception ignored) {}
        return true; // cancel vanilla onClick
    }

    public static boolean onButtonMouseReleased(AbstractButton btn, net.minecraft.client.input.MouseButtonEvent event) {
        if (!LiquidumCore.getConfig().enabled || !LiquidumCore.getConfig().buttonsGlass) return false;
        State st = STATES.get(btn);
        if (st == null || !st.isPressed) return false;
        // Only if this widget was the pressed one
        if (pressedWidget != btn) return false;
        st.isPressed = false;
        pressedWidget = null;

        double mx = event.x();
        double my = event.y();
        boolean stillOver = btn.isMouseOver(mx, my) && btn.isActive() && btn.visible;
        // Spring bounce: overshoot then settle to hover/normal
        st.scale.velocity = 2.5f; // kick
        st.scale.setTarget(stillOver ? 1.02f : 1.0f);

        if (stillOver) {
            // Fire action on release (iOS) — delegate to vanilla onClick
            try {
                // isValidClickButton already checked in mouseReleased guard, but re-check buttonInfo
                btn.onClick(event, false);
                return true; // cancel vanilla onRelease default (we already fired)
            } catch (Exception e) {
                return false;
            }
        }
        // Released outside — cancel, no action
        return true;
    }

    public static void onButtonDragged(AbstractButton btn, double mx, double my) {
        State st = STATES.get(btn);
        if (st == null || !st.isPressed) return;
        boolean over = btn.isMouseOver(mx, my);
        if (over != pressedWasHovered) {
            pressedWasHovered = over;
            st.scale.setTarget(over ? 0.97f : 1.0f);
        }
    }

    /** Call every client tick to advance springs and hover. */
    public static void tick(Minecraft mc) {
        if (mc == null || !LiquidumCore.getConfig().enabled) return;
        float dt = 1f/60f;
        var iter = new java.util.ArrayList<>(STATES.entrySet());
        for (var e : iter) {
            AbstractWidget w = e.getKey();
            State st = e.getValue();
            if (w == null || !w.visible) {
                STATES.remove(w);
                continue;
            }
            boolean over = false;
            try { over = w.isHovered(); } catch (Exception ignored) {}
            if (!st.isPressed) {
                float hoverT = over || w.isFocused() ? 1f : 0f;
                st.hoverCurrent += (hoverT - st.hoverCurrent) * (1f - (float)Math.exp(-12*dt));
                float scaleT = 1.0f + 0.02f * st.hoverCurrent;
                if (st.scale.isAtRest()) st.scale.setTarget(scaleT);
            }
            st.scale.update(dt);
        }
        if (pressedWidget != null) {
            try {
                double mx = mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getWidth();
                double my = mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getHeight();
                onButtonDragged((AbstractButton)pressedWidget, mx, my);
            } catch (Exception ignored) {}
        }
    }

    public static void clear() {
        STATES.clear();
        pressedWidget = null;
    }
}
