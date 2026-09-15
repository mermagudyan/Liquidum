package com.liquidum.client.compat;

import net.minecraft.client.gui.screens.Screen;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * §T P1 изоляция — OptOut API как у ReGlass (ReGlassOptOut) но без кросс-поломок.
 * Если мод (ModernUI, Exordium и т.д.) конфликтует, он или игрок может отключить
 * стекло для конкретного Screen-класса. Fallback — vanilla, не чёрный экран.
 * Хотбар/инвентарь/доки независимы: выкл. одного не ломает другое.
 */
public final class LiquidumOptOut {
    private static final Set<String> OPTED_OUT = ConcurrentHashMap.newKeySet();
    private static final Set<Class<?>> OPTED_CLASSES = ConcurrentHashMap.newKeySet();

    private LiquidumOptOut() {}

    public static void optOut(Class<? extends Screen> screenClass) {
        OPTED_CLASSES.add(screenClass);
        OPTED_OUT.add(screenClass.getName());
    }

    public static void optOut(String className) {
        OPTED_OUT.add(className);
    }

    public static void optIn(Class<? extends Screen> screenClass) {
        OPTED_CLASSES.remove(screenClass);
        OPTED_OUT.remove(screenClass.getName());
    }

    public static boolean isOptedOut(Screen screen) {
        if (screen == null) return false;
        if (OPTED_CLASSES.contains(screen.getClass())) return true;
        if (OPTED_OUT.contains(screen.getClass().getName())) return true;
        // Check assignable (subclass) opt-out via isAssignableFrom
        for (Class<?> c : OPTED_CLASSES) {
            if (c.isAssignableFrom(screen.getClass())) return true;
        }
        return false;
    }

    public static boolean isOptedOut(Class<?> screenClass) {
        if (screenClass == null) return false;
        if (OPTED_OUT.contains(screenClass.getName())) return true;
        for (Class<?> c : OPTED_CLASSES) if (c.isAssignableFrom(screenClass)) return true;
        return false;
    }
}
