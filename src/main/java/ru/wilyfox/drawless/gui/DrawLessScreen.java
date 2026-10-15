package ru.wilyfox.drawless.gui;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import ru.wilyfox.drawless.config.DrawLessConfig;
import ru.wilyfox.drawless.config.ExtremeDisplayMode;

public final class DrawLessScreen extends Screen {
    private final Screen parent;
    private int page;
    private static final int ROWS=7;
    public DrawLessScreen(Screen parent) { super(Component.literal("DrawLess")); this.parent=parent; }
    @Override protected void init() {
        int w=Math.min(360,width-24),x=(width-w)/2,y=Math.max(42,(height-230)/2);
        var c=DrawLessConfig.get();
        if(page==0) {
            toggle(x,y,w,"DrawLess",()->c.enabled,v->c.enabled=v);
            cycle(x,y+24,w,"Display mode",()->{
                var values=java.util.List.of(ExtremeDisplayMode.OFF,ExtremeDisplayMode.STATIC_CACHE,ExtremeDisplayMode.LIVE_CACHE,ExtremeDisplayMode.LIVE_EXTRACT);
                c.extremeDisplayMode=values.get((values.indexOf(c.extremeDisplayMode)+1)%values.size());
            },()->c.extremeDisplayMode.label());
            toggle(x,y+48,w,"Display model cache",()->c.displayModelBudget,v->c.displayModelBudget=v);
            toggle(x,y+72,w,"Adaptive model budget",()->c.adaptiveDisplayModelBudget,v->c.adaptiveDisplayModelBudget=v);
            cycle(x,y+96,w,"Model budget",()->c.displayModelBudgetMicros=c.displayModelBudgetMicros>=5000?100:c.displayModelBudgetMicros+100,()->c.displayModelBudgetMicros+" us");
            toggle(x,y+120,w,"Particle light cache (experimental)",()->c.particleLightCache,v->c.particleLightCache=v);
            toggle(x,y+144,w,"Particle empty collision (experimental)",()->c.particleEmptyCollision,v->c.particleEmptyCollision=v);
        } else {
            cycle(x,y,w,"Diagnostic mode",()->{
                var values=java.util.List.of(ExtremeDisplayMode.FREEZE,ExtremeDisplayMode.UNRENDER,ExtremeDisplayMode.TICKS_ONLY,ExtremeDisplayMode.LIVE_CACHE);
                c.extremeDisplayMode=values.get((values.indexOf(c.extremeDisplayMode)+1)%values.size());
            },()->c.extremeDisplayMode==ExtremeDisplayMode.FREEZE||c.extremeDisplayMode==ExtremeDisplayMode.UNRENDER||c.extremeDisplayMode==ExtremeDisplayMode.TICKS_ONLY?c.extremeDisplayMode.label():"OFF");
            toggle(x,y+24,w,"Entire Item Display scene",()->c.extremeAllItemDisplays,v->c.extremeAllItemDisplays=v);
            toggle(x,y+48,w,"Pause Display ticks (Freeze / Unrender)",()->c.extremePauseDisplayTicks,v->c.extremePauseDisplayTicks=v);
            toggle(x,y+72,w,"NPC pose budget (experimental)",()->c.modelEngineNpcBudget,v->c.modelEngineNpcBudget=v);
            cycle(x,y+96,w,"NPC groups per frame",()->c.npcGroupsPerFrame=c.npcGroupsPerFrame>=100?1:c.npcGroupsPerFrame+1,()->String.valueOf(c.npcGroupsPerFrame));
            toggle(x,y+120,w,"Adaptive NPC budget",()->c.adaptiveNpcBudget,v->c.adaptiveNpcBudget=v);
            button(x,y+144,w,"Reset settings",()->{DrawLessConfig.defaults();rebuildWidgets();});
        }
        button(x,y+ROWS*24+4,w/2-3,page==0?"Advanced →":"← General",()->{page=1-page;rebuildWidgets();});
        button(x+w/2+3,y+ROWS*24+4,w/2-3,"Done",this::onClose);
    }
    private void toggle(int x,int y,int w,String label,BooleanSupplier get,Consumer<Boolean> set) {
        cycle(x,y,w,label,()->set.accept(!get.getAsBoolean()),()->get.getAsBoolean()?"ON":"OFF");
    }
    private void cycle(int x,int y,int w,String label,Runnable next,java.util.function.Supplier<String> value) {
        button(x,y,w,label+": "+value.get(),()->{next.run();rebuildWidgets();});
    }
    private void button(int x,int y,int w,String label,Runnable action) {
        addRenderableWidget(new Button(x,y,w,20,Component.literal(label),ignored->action.run(),java.util.function.Supplier::get) {
            @Override protected void extractContents(GuiGraphicsExtractor g,int mx,int my,float delta) {
                boolean highlighted=isMouseOver(mx,my)||isFocused();
                g.fill(getX(),getY(),getRight(),getBottom(),highlighted?0xFF3B4746:0xFF293235);
                if(highlighted) g.fill(getX(),getY(),getRight(),getY()+1,0xFFB6E164);
                String text=font.plainSubstrByWidth(getMessage().getString(),getWidth()-12);
                g.centeredText(font,text,getX()+getWidth()/2,getY()+6,active?0xFFE4E9E8:0xFF7E898C);
                if(highlighted&&!text.equals(getMessage().getString())) g.setTooltipForNextFrame(font,getMessage(),mx,my);
            }
        });
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta) {
        g.fill(0,0,width,height,0xE51B2024);
        g.fill(0,0,width,3,0xFFB6E164);
        g.centeredText(font,"DrawLess",width/2,14,0xFFB6E164);
        g.centeredText(font,"Rendering settings",width/2,27,0xFFC6CED1);
        super.extractRenderState(g,mx,my,delta);
    }
    @Override public void extractBackground(GuiGraphicsExtractor g,int x,int y,float delta) { }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { DrawLessConfig.save();minecraft.gui.setScreen(parent); }
}
