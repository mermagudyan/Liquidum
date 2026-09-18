package com.liquidum.client.config;

import com.liquidum.client.debug.LiquidumDebugState;
import com.liquidum.client.shader.LiquidGlassRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * YACL-based config screen — полноценное меню со всеми настройками,
 * превью на своём фоне (minecraft/blurred), вкладки профилей.
 * Дизайн на основе YACL: категории слева, опции справа, live preview.
 * Если YACL установлен — открывается через ModMenu, иначе через F8 / /liquidum config.
 * Спокойные пресеты не выкинуты — доступны как профили.
 */
public class LiquidumYaclScreen extends Screen {
    private static final int TAB_W = 90;
    private static final int CONTENT_W = 310;
    private final Screen parent;
    private Category current = Category.PROFILES;
    private String previewBg = "grass"; // grass / snow / nether / end / blur
    private int scroll = 0;

    enum Category {
        PROFILES("Профили"), EFFECTS("Эффекты"), MATERIALS("Материалы"), PREVIEW("Превью"), ADVANCED("Продвинуто");
        final String name; Category(String n){name=n;}
    }

    public LiquidumYaclScreen(Screen parent) {
        super(Component.literal("Liquidum — YACL"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        // Rebuild clears everything then re-adds tabs and content
        clearWidgets();
        int cx = width / 2;
        int tabX = cx - (TAB_W + CONTENT_W)/2 - 4;
        int tabY = 40;
        int i=0;
        for (var c : Category.values()) {
            var cat = c;
            var btn = Button.builder(Component.literal((current==cat?"> ":"")+cat.name), b -> { current=cat; rebuild(); })
                    .bounds(tabX, tabY + i*22, TAB_W, 20).build();
            btn.active = current != cat;
            addRenderableWidget(btn);
            i++;
        }
        addRenderableWidget(Button.builder(Component.literal("Применить"), b -> { LiquidumProfiles.save(); b.setMessage(Component.literal("Сохранено ✓")); }).bounds(cx - 50, height - 28, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Готово"), b -> { LiquidumProfiles.save(); onClose(); }).bounds(cx + 60, height - 28, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Сброс: Основная"), b -> { LiquidumProfiles.ETALON.apply(); rebuild(); }).bounds(cx -160, height -28, 100,20).build());
        addRenderableWidget(Button.builder(Component.literal("Превью: "+previewBg), b -> {
            previewBg = switch(previewBg){ case "grass"->"snow"; case "snow"->"nether"; case "nether"->"end"; case "end"->"blur"; default->"grass";};
            b.setMessage(Component.literal("Превью: "+previewBg));
        }).bounds(cx+60, height-28, 100,20).build());

        int contentX = tabX + TAB_W + 8;
        int contentY = 40;
        int w = CONTENT_W;
        switch (current) {
            case PROFILES -> buildProfiles(contentX, contentY, w);
            case EFFECTS -> buildEffects(contentX, contentY, w);
            case MATERIALS -> buildMaterials(contentX, contentY, w);
            case PREVIEW -> buildPreview(contentX, contentY, w);
            case ADVANCED -> buildAdvanced(contentX, contentY, w);
        }
    }

    private void buildProfiles(int x, int y, int w) {
        int curY = y;
        addRenderableWidget(Button.builder(Component.literal("Сброс: Основная"), b-> {LiquidumProfiles.ETALON.apply(); rebuild();}).bounds(x,y,w,20).build()); y+=22;
        addRenderableWidget(Button.builder(Component.literal("Текущий: " + LiquidumProfiles.currentName()), b->{rebuild();}).bounds(x,y,w,20).build());
    }

    private void buildEffects(int x, int y, int w) {
        addToggle(x,y,w,"Blur",()->LiquidumDebugState.frost, v->{LiquidumDebugState.frost=v; LiquidumProfiles.save();}); y+=22;
        addToggle(x,y,w,"Refraction",()->LiquidumDebugState.refraction>0.1f, v->{LiquidumDebugState.refraction=v?9f:0f; LiquidumProfiles.save();}); y+=22;
        addToggle(x,y,w,"Fresnel/Rim",()->LiquidumDebugState.rim, v->{LiquidumDebugState.rim=v; LiquidumProfiles.save();}); y+=22;
        addToggle(x,y,w,"Aberration",()->LiquidumDebugState.aberration, v->{LiquidumDebugState.aberration=v; LiquidumProfiles.save();}); y+=22;
        addToggle(x,y,w,"Fusion",()->LiquidumDebugState.fusion, v->{LiquidumDebugState.fusion=v; LiquidumProfiles.save();}); y+=22;
        addToggle(x,y,w,"Hover",()->LiquidumDebugState.hover, v->{LiquidumDebugState.hover=v; LiquidumProfiles.save();}); y+=22;
        addToggle(x,y,w,"GeometryDebug",()->LiquidumDebugState.debugGeometry, v->LiquidumDebugState.debugGeometry=v);
    }

    private void buildMaterials(int x, int y, int w) {
        addSlider(x,y,w,"Corner",()->LiquidumDebugState.cornerRadiusFraction,v->{LiquidumDebugState.cornerRadiusFraction=v; LiquidumProfiles.save();},0f,1f,0.05f); y+=22;
        addSlider(x,y,w,"Refraction",()->LiquidumDebugState.refraction,v->{LiquidumDebugState.refraction=v; LiquidumProfiles.save();},0f,60f,1f); y+=22;
        addSlider(x,y,w,"Fresnel",()->LiquidumDebugState.fresnel,v->{LiquidumDebugState.fresnel=v; LiquidumProfiles.save();},0f,2f,0.05f); y+=22;
        addSlider(x,y,w,"Sharpness",()->LiquidumDebugState.sharpnessMix,v->{LiquidumDebugState.sharpnessMix=v; LiquidumProfiles.save();},0f,1f,0.02f); y+=22;
        addSlider(x,y,w,"FrostRadius",()->LiquidumDebugState.frostRadius,v->{LiquidumDebugState.frostRadius=v; LiquidumProfiles.save();},0f,20f,0.5f); y+=22;
        addSlider(x,y,w,"FusionRadius",()->LiquidumDebugState.fusionRadius,v->{LiquidumDebugState.fusionRadius=v; LiquidumProfiles.save();},0f,30f,1f); y+=22;
    }

    private void buildPreview(int x, int y, int w) {
        addSlider(x,y,w,"Preview Bg Luma",()-> previewLuma(), v-> {},0f,1f,0.1f); y+=22;
        // info text handled in render
    }
    private float previewLuma(){ return switch(previewBg){case "snow"->0.92f; case "nether"->0.12f; case "end"->0.22f; case "blur"->0.45f; default->0.55f;};}

    private void buildAdvanced(int x, int y, int w) {
        var cfg = com.liquidum.client.LiquidumCore.getConfig();
        addToggle(x,y,w,"Enabled",()->cfg.enabled, v->{cfg.enabled=v; cfg.save();}); y+=22;
        addToggle(x,y,w,"Buttons Glass",()->cfg.buttonsGlass, v->{cfg.buttonsGlass=v; cfg.save();}); y+=22;
        addToggle(x,y,w,"Hotbar Glass",()->cfg.hotbarGlass, v->{cfg.hotbarGlass=v; cfg.save();}); y+=22;
        addToggle(x,y,w,"Slots Glass",()->cfg.containerGlass, v->{cfg.containerGlass=v; cfg.save();}); y+=22;
        addToggle(x,y,w,"Dock Adaptive",()->cfg.dockAdaptive, v->{cfg.dockAdaptive=v; cfg.save();}); y+=22;
    }

    private void addToggle(int x,int y,int w,String name, Supplier<Boolean> get, Consumer<Boolean> set){
        var b = Button.builder(Component.literal(name+": "+(get.get()?"ON":"OFF")), btn->{
            set.accept(!get.get());
            btn.setMessage(Component.literal(name+": "+(get.get()?"ON":"OFF")));
        }).bounds(x,y,w,20).build();
        addRenderableWidget(b);
    }
    private void addSlider(int x,int y,int w,String name, Supplier<Float> get, Consumer<Float> set, float min,float max,float step){
        var val = String.format(java.util.Locale.ROOT,"%.2f",get.get());
        var b = Button.builder(Component.literal(name+": "+val), btn->{
            // simple +/- via click: left dec, right inc, middle reset
        }).bounds(x,y,w,20).build();
        // make -/+ around
        addRenderableWidget(Button.builder(Component.literal("-"), btn->{
            float v = Math.max(min, get.get()-step);
            set.accept(v);
            b.setMessage(Component.literal(name+": "+String.format(java.util.Locale.ROOT,"%.2f",v)));
        }).bounds(x,y,20,20).build());
        addRenderableWidget(b);
        addRenderableWidget(Button.builder(Component.literal("+"), btn->{
            float v = Math.min(max, get.get()+step);
            set.accept(v);
            b.setMessage(Component.literal(name+": "+String.format(java.util.Locale.ROOT,"%.2f",v)));
        }).bounds(x+w-20,y,20,20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        renderPreviewBackground(graphics);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        renderGlassPreview(graphics);
        var prof = LiquidumProfiles.currentName();
        graphics.centeredText(this.font, "Liquidum — "+prof+" | refr="+String.format(java.util.Locale.ROOT,"%.1f",LiquidumDebugState.refraction)+" fres="+String.format(java.util.Locale.ROOT,"%.2f",LiquidumDebugState.fresnel)+" frost="+String.format(java.util.Locale.ROOT,"%.1f",LiquidumDebugState.frostRadius)+" fuse="+String.format(java.util.Locale.ROOT,"%.1f",LiquidumDebugState.fusionRadius), width/2, 12, 0xFFFFFFFF);
    }

    private void renderPreviewBackground(GuiGraphicsExtractor g){
        int col = switch(previewBg){
            case "snow" -> 0xFFECECEC;
            case "nether" -> 0xFF2A0A0A;
            case "end" -> 0xFF1A1A2A;
            case "blur" -> 0xFF5A6A7A;
            default -> 0xFF5B7C4A; // grass
        };
        g.fill(0,0,width,height,col);
        // high-contrast checker + color bars: на плоском фоне рефракцию не видно, поэтому рисуем частую решётку и цветные полосы — сдвиг виден сразу
        for(int y=0;y<height;y+=20){
            for(int x=0;x<width;x+=20){
                if(((x+y)/20)%2==0) g.fill(x,y,x+20,y+20,0x22000000);
            }
        }
        for(int i=0;i<width;i+=40){
            int c = switch((i/40)%3){ case 0 -> 0xFFFF5555; case 1 -> 0xFF55FF55; default -> 0xFF5555FF; };
            g.fill(i,height/2-30,i+20,height/2+30,c);
        }
        for(int i=0;i<width;i+=20) g.fill(i,0,i+2,height,0x66FFFFFF);
        for(int i=0;i<height;i+=20) g.fill(0,i,width,i+2,0x66FFFFFF);
    }

    private void renderGlassPreview(GuiGraphicsExtractor g){
        // Preview uses real glass: rects into PostChain, no fake fills over it
        int cx = width/2 + 20;
        int cy = height/2 - 10;
        LiquidGlassRenderer.submitBasePanel(cx-60, cy-40, 120, 80);
        LiquidGlassRenderer.submitSpriteTile(cx-40, cy+50, 80, 20, LiquidGlassRenderer.MAT_CONTROL);
        for(int r=0;r<3;r++) for(int c=0;c<3;c++){
            int sx = cx-40 + c*24;
            int sy = cy+80 + r*24;
            LiquidGlassRenderer.submitSlotWell(sx, sy);
        }
        // поверх стекла только тонкие метки, не перекрывающие линзу
        g.fill(cx-60, cy-42, cx+60, cy-40, 0x88FFFFFF);
        g.fill(cx-40, cy+48, cx+40, cy+50, 0x88FFFFFF);
    }

    @Override
    public void onClose(){
        LiquidumProfiles.save();
        if (this.minecraft != null) this.minecraft.gui.setScreen(parent);
        else if (Minecraft.getInstance() != null) Minecraft.getInstance().gui.setScreen(parent);
    }
    @Override public boolean isPauseScreen(){return false;}
}
