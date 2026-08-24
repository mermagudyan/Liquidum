# Liquidum API — Future Plans

## Goal
Create a standalone API library that other mod developers can include in their projects
to easily add Liquid Glass effects to their custom GUI screens.

## Usage Example (target design)

```java
// In your mod's build.gradle:
dependencies {
    modImplementation "com.liquidum:LiquidumAPI:1.0.0"
}

// In your custom Screen class:
@Override
public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
    // Enable liquid glass background
    LiquidGlassAPI.startGlassContext(guiGraphics);

    // Draw your custom widgets — they render over the glass
    super.render(guiGraphics, mouseX, mouseY, partialTick);

    // Disable liquid glass
    LiquidGlassAPI.endGlassContext();
}

// For button merging (SDF liquid effect):
LiquidLayout layout = new LiquidLayout();
layout.addComponent(button1.getX(), button1.getY(), button1.getWidth(), button1.getHeight());
layout.addComponent(button2.getX(), button2.getY(), button2.getWidth(), button2.getHeight());
layout.apply(guiGraphics);
```

## API Classes to Implement

### `LiquidGlassAPI`
- `startGlassContext(GuiGraphics)` — enables glass shader for subsequent draws
- `endGlassContext()` — restores normal rendering
- `setRefractionStrength(float)` — override refraction intensity
- `setFresnelPower(float)` — override edge glow
- `setChromaticAberration(float)` — override RGB split
- `setSaturationBoost(float)` — override color vibrancy
- `setBlurPasses(int)` — override blur quality
- `setDownsampleScale(float)` — override resolution scale (performance tuning)

### `LiquidLayout`
- `addComponent(x, y, w, h)` — register a button/widget position
- `clearComponents()` — reset
- `apply(GuiGraphics)` — pass all positions to shader for SDF merging
- `setLiquidK(float)` — control how much buttons merge together

### `LiquidAnimation`
- `openScreen(Screen)` — animate screen open with spring physics
- `closeScreen(Screen)` — animate screen close
- `setEasingFunction(EasingUtil.EasingType)` — choose animation curve

### `LiquidConfig`
- Runtime configuration class for all visual parameters
- JSON config file support for user preferences
- Per-screen overrides

## Distribution
- Publish as Maven artifact via JitPack or GitHub Packages
- Other devs add one line to `build.gradle`
- Include shader `.fsh`/`.vsh`/`.json` files in the API JAR

## License
- Use MIT for maximum adoption
- LGPLv3 if code protection is needed

## Dependencies to Consider
- Fabric API (required)
- Iris/Shaders mod compatibility layer (optional, for advanced users)
- Sodium (optional, for performance)

## Performance Targets
- 60+ FPS on mid-range hardware with glass enabled
- Dynamic quality scaling based on FPS (auto-reduce blur passes if FPS drops)
- Option to disable per-screen for performance-critical UIs
