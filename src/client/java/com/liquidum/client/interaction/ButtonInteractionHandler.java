package com.liquidum.client.interaction;

import com.liquidum.client.config.LiquidumConfig;
import com.liquidum.client.LiquidumCore;
import com.liquidum.client.motion.SpringPhysics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
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
    // Smoothed 0..1 press level mirrored to the glass shader dent (uLayer.w)
    private static float pressLevel = 0f;

    private static final class State {
        final SpringPhysics scale = new SpringPhysics(1.0f);
        float hoverTarget = 0f; // 0..1
        float hoverCurrent = 0f;
        boolean isPressed = false;
        State() {
            // Едва заметная живость как у iPhone: мягкий spring, минимальный overshoot
            scale.stiffness = 620f;
            scale.damping = 30f;
            scale.mass = 1f;
        }
    }

    public static boolean isPressed(AbstractWidget w) {
        State s = STATES.get(w);
        return s != null && s.isPressed;
    }

    private ButtonInteractionHandler() {}

    // Recipe controls own their press timing and toggle state
    private static boolean isRecipeControl(AbstractWidget w) {
        if (w == null) return false;
        if (w.getClass().getName().contains("recipebook.")) return true;
        if (w instanceof CycleButton
            && Minecraft.getInstance().gui.screen() instanceof AbstractRecipeBookScreen) return true;
        return false;
    }

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

    public static float pressLevel() {
        return pressLevel;
    }

    /** Called from AbstractButtonMixin on mouseClicked HEAD (cancellable). */
    public static boolean onButtonMouseClicked(AbstractButton btn, net.minecraft.client.input.MouseButtonEvent event, boolean bl) {
        if (!LiquidumCore.getConfig().enabled || !LiquidumCore.getConfig().buttonsGlass) return false;
        if (isRecipeControl(btn)) return false;
        // §D: книга рецептов открывается на 2-й клик если iOS-defer — у неё свой toggleVisibility который ждёт immediate onPress. Bypass iOS для неё.
        try {
            if (com.liquidum.client.shader.LiquidGlassRenderer.isRecipeBookButton(btn)) return false;
        } catch (Exception ignored) {}
        // Only iOS-style for primary button (left) — right clicks pass through
        try {
            var info = event.buttonInfo();
            // isValidClickButton check — only left (button 0) in vanilla; keep same gate If not valid, let vanilla handle
            if (!btn.isActive() || !btn.visible) return false;
        } catch (Exception ignored) {}
        double mx = event.x();
        double my = event.y();
        if (!btn.isMouseOver(mx, my)) return false;

        // WOW: visible press depth — the cabochon dents, action deferred to release
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
        if (isRecipeControl(btn)) return false;
        try {
            if (com.liquidum.client.shader.LiquidGlassRenderer.isRecipeBookButton(btn)) return false;
        } catch (Exception ignored) {}
        State st = STATES.get(btn);
        if (st == null || !st.isPressed) return false;
        // Only if this widget was the pressed one
        if (pressedWidget != btn) return false;
        st.isPressed = false;
        pressedWidget = null;

        double mx = event.x();
        double my = event.y();
        boolean stillOver = btn.isMouseOver(mx, my) && btn.isActive() && btn.visible;
        // WOW: spring-back with overshoot — release pops past 1.0 and settles
        st.scale.velocity = 3.5f;
        st.scale.setTarget(1.0f);

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
        // Reduced motion: no spring overshoot
        boolean reduced = LiquidumCore.getConfig().reducedMotion;
        float dt = 1f/60f;
        if (pressedWidget != null) {
            State ps = STATES.get(pressedWidget);
            if (ps == null || !ps.isPressed) pressedWidget = null;
        }
        float pressTarget = (pressedWidget != null && !reduced) ? 1f : 0f;
        pressLevel += (pressTarget - pressLevel) * (1f - (float) Math.exp(-14 * dt));
        if (pressLevel < 0.001f && pressTarget <= 0f) pressLevel = 0f;
        var iter = new java.util.ArrayList<>(STATES.entrySet());
        for (var e : iter) {
            AbstractWidget w = e.getKey();
            State st = e.getValue();
            if (w == null || !w.visible) {
                STATES.remove(w);
                continue;
            }
            // Disabled → no hover, settle to 1.0 dim
            if (!w.active) {
                st.hoverCurrent = 0f;
                if (st.scale.isAtRest()) st.scale.setTarget(1.0f);
                st.scale.update(dt);
                continue;
            }
            boolean over = false;
            try { over = w.isHovered(); } catch (Exception ignored) {}
            if (!st.isPressed) {
                float hoverT = over || w.isFocused() ? 1f : 0f;
                if (reduced) hoverT *= 0.3f;
                st.hoverCurrent += (hoverT - st.hoverCurrent) * (1f - (float)Math.exp(-12*dt));
                // WOW hover-lift: focused/hovered hero glass rises a touch
                float scaleT = w.isFocused() ? 1.015f : 1.0f;
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
        pressLevel = 0f;
    }
}
