package com.liquidum.client.config;

import com.liquidum.client.debug.LiquidumDebugState;

// Single main profile, tuned live in the Lab
public enum LiquidumProfiles {
    ETALON(30.0f, 0.65f, 0.18f, 10.0f, 12.0f, 0.0f, 0.10f, 0.65f, 0.45f, 2.0f, 0.0f);

    public final float refraction;
    public final float fresnel;
    public final float sharpness;
    public final float frost;
    public final float fusion;
    public final float sun;
    public final float bodyBleed;
    public final float edgeBleed;
    public final float chroma;
    public final float edgeWidth;
    public final float reflection;

    // Profile name follows the Lab language
    public String displayName() {
        return LiquidumLang.ru() ? "Основная" : "Main";
    }

    // True when live glass values equal this profile, epsilon for slider drift
    public boolean matchesState() {
        return eq(refraction, LiquidumDebugState.refraction)
            && eq(fresnel, LiquidumDebugState.fresnel)
            && eq(sharpness, LiquidumDebugState.sharpnessMix)
            && eq(frost, LiquidumDebugState.frostRadius)
            && eq(fusion, LiquidumDebugState.fusionRadius)
            && eq(sun, LiquidumDebugState.sunSpec)
            && eq(bodyBleed, LiquidumDebugState.bodyBleed)
            && eq(edgeBleed, LiquidumDebugState.edgeBleed)
            && eq(chroma, LiquidumDebugState.chroma)
            && eq(edgeWidth, LiquidumDebugState.edgeWidth)
            && eq(reflection, LiquidumDebugState.reflection)
            && eq(0.18f, LiquidumDebugState.cornerRadiusFraction)
            && LiquidumDebugState.frost && LiquidumDebugState.rim
            && LiquidumDebugState.aberration && LiquidumDebugState.hover
            && LiquidumDebugState.parallax && !LiquidumDebugState.animOpen
            && !LiquidumDebugState.lightManual;
    }

    private static boolean eq(float a, float b) {
        return Math.abs(a - b) < 1e-4f;
    }

    // First matching profile or null when the user drifted off stock
    public static LiquidumProfiles detect() {
        for (LiquidumProfiles p : values()) {
            if (p.matchesState()) return p;
        }
        return null;
    }

    // Status name everywhere: profile name or Custom when drifted
    public static String currentName() {
        LiquidumProfiles p = detect();
        return p != null ? p.displayName() : LiquidumLang.tr("Своя");
    }

    LiquidumProfiles(float refraction, float fresnel, float sharpness, float frost, float fusion, float sun, float bodyBleed, float edgeBleed, float chroma, float edgeWidth, float reflection) {
        this.refraction = refraction;
        this.fresnel = fresnel;
        this.sharpness = sharpness;
        this.frost = frost;
        this.fusion = fusion;
        this.sun = sun;
        this.bodyBleed = bodyBleed;
        this.edgeBleed = edgeBleed;
        this.chroma = chroma;
        this.edgeWidth = edgeWidth;
        this.reflection = reflection;
    }

    public void apply() {
        LiquidumDebugState.refraction = refraction;
        LiquidumDebugState.edgeWidth = edgeWidth;
        LiquidumDebugState.fresnel = fresnel;
        LiquidumDebugState.sharpnessMix = sharpness;
        LiquidumDebugState.frostRadius = frost;
        LiquidumDebugState.fusionRadius = fusion;
        LiquidumDebugState.sunSpec = sun;
        LiquidumDebugState.bodyBleed = bodyBleed;
        LiquidumDebugState.edgeBleed = edgeBleed;
        LiquidumDebugState.chroma = chroma;
        LiquidumDebugState.reflection = reflection;
        LiquidumDebugState.cornerRadiusFraction = 0.18f;
        LiquidumDebugState.frost = true;
        LiquidumDebugState.rim = true;
        LiquidumDebugState.aberration = true;
        LiquidumDebugState.hover = true;
        LiquidumDebugState.fusion = true;
        LiquidumDebugState.parallax = true;
        LiquidumDebugState.animOpen = false;
        LiquidumDebugState.lightManual = false;
        save();
    }

    private static java.nio.file.Path path() {
        return LiquidumPaths.dir().resolve("liquidum_profiles.json");
    }

    /** Persist current DebugState so F8 survives restart / screen close. */
    public static void save() {
        try {
            var o = new com.google.gson.JsonObject();
            o.addProperty("refraction", LiquidumDebugState.refraction);
            o.addProperty("edgeWidth", LiquidumDebugState.edgeWidth);
            o.addProperty("fresnel", LiquidumDebugState.fresnel);
            o.addProperty("sharpnessMix", LiquidumDebugState.sharpnessMix);
            o.addProperty("frostRadius", LiquidumDebugState.frostRadius);
            o.addProperty("fusionRadius", LiquidumDebugState.fusionRadius);
            o.addProperty("corner", LiquidumDebugState.cornerRadiusFraction);
            o.addProperty("frost", LiquidumDebugState.frost);
            o.addProperty("rim", LiquidumDebugState.rim);
            o.addProperty("aberration", LiquidumDebugState.aberration);
            o.addProperty("fusion", LiquidumDebugState.fusion);
            o.addProperty("hover", LiquidumDebugState.hover);
            o.addProperty("sunSpec", LiquidumDebugState.sunSpec);
            o.addProperty("parallax", LiquidumDebugState.parallax);
            o.addProperty("bodyBleed", LiquidumDebugState.bodyBleed);
            o.addProperty("edgeBleed", LiquidumDebugState.edgeBleed);
            o.addProperty("chroma", LiquidumDebugState.chroma);
            o.addProperty("reflection", LiquidumDebugState.reflection);
            o.addProperty("lightManual", LiquidumDebugState.lightManual);
            o.addProperty("lightAngle", LiquidumDebugState.lightAngle);
            o.addProperty("lightLevel", LiquidumDebugState.lightLevel);
            var p = path();
            java.nio.file.Files.createDirectories(p.getParent());
            java.nio.file.Files.writeString(p, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(o));
        } catch (Exception ignored) {}
    }

    /** Restore persisted state at client init, falls back to ETALON. */
    public static void load() {
        try {
            var p = LiquidumPaths.find("liquidum_profiles.json");
            if (p == null || !java.nio.file.Files.exists(p)) return;
            var o = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(p)).getAsJsonObject();
            if (o.has("refraction")) LiquidumDebugState.refraction = o.get("refraction").getAsFloat();
            if (o.has("edgeWidth")) LiquidumDebugState.edgeWidth = o.get("edgeWidth").getAsFloat();
            if (o.has("fresnel")) LiquidumDebugState.fresnel = o.get("fresnel").getAsFloat();
            if (o.has("sharpnessMix")) LiquidumDebugState.sharpnessMix = o.get("sharpnessMix").getAsFloat();
            if (o.has("frostRadius")) LiquidumDebugState.frostRadius = o.get("frostRadius").getAsFloat();
            if (o.has("fusionRadius")) LiquidumDebugState.fusionRadius = o.get("fusionRadius").getAsFloat();
            if (o.has("corner")) LiquidumDebugState.cornerRadiusFraction = o.get("corner").getAsFloat();
            if (o.has("frost")) LiquidumDebugState.frost = o.get("frost").getAsBoolean();
            if (o.has("rim")) LiquidumDebugState.rim = o.get("rim").getAsBoolean();
            if (o.has("aberration")) LiquidumDebugState.aberration = o.get("aberration").getAsBoolean();
            if (o.has("fusion")) LiquidumDebugState.fusion = o.get("fusion").getAsBoolean();
            if (o.has("hover")) LiquidumDebugState.hover = o.get("hover").getAsBoolean();
            if (o.has("sunSpec")) LiquidumDebugState.sunSpec = o.get("sunSpec").getAsFloat();
            if (o.has("parallax")) LiquidumDebugState.parallax = o.get("parallax").getAsBoolean();
            if (o.has("bodyBleed")) LiquidumDebugState.bodyBleed = o.get("bodyBleed").getAsFloat();
            if (o.has("edgeBleed")) LiquidumDebugState.edgeBleed = o.get("edgeBleed").getAsFloat();
            if (o.has("chroma")) LiquidumDebugState.chroma = o.get("chroma").getAsFloat();
            if (o.has("reflection")) LiquidumDebugState.reflection = o.get("reflection").getAsFloat();
            if (o.has("lightManual")) LiquidumDebugState.lightManual = o.get("lightManual").getAsBoolean();
            if (o.has("lightAngle")) LiquidumDebugState.lightAngle = o.get("lightAngle").getAsFloat();
            if (o.has("lightLevel")) LiquidumDebugState.lightLevel = o.get("lightLevel").getAsFloat();
        } catch (Exception ignored) {}
    }
}
