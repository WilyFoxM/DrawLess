package ru.wilyfox.drawless.performance;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemDisplayEntityRenderState;
import net.minecraft.world.entity.Display;
import ru.wilyfox.drawless.bridge.DisplayLayerOwnership;
import ru.wilyfox.drawless.config.DrawLessConfig;
import ru.wilyfox.drawless.config.ExtremeDisplayMode;

import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.Locale;

/** Recycle only states removed from vanilla submission after a successful GPU-cache commit. */
public final class DisplayExtractionCache {
    private static final int MAX_STATES=4096;
    private static final IdentityHashMap<Display.ItemDisplay,Slot> slots=new IdentityHashMap<>();
    private static final ArrayList<Display.ItemDisplay> leases=new ArrayList<>();
    private static ClientLevel world;
    private static boolean enabled,extracting;
    private static long frame,reused,fresh,lightOverrides,reusedTotal,freshTotal,lightOverridesTotal;
    private DisplayExtractionCache(){}
    public static void clear(){
        slots.clear();leases.clear();world=null;enabled=extracting=false;
        frame=reused=fresh=lightOverrides=reusedTotal=freshTotal=lightOverridesTotal=0;
    }
    public static void beginFrame(Minecraft client,boolean liveReady){
        boolean requested=liveReady&&client.level!=null
                &&DrawLessConfig.get().extremeDisplayMode==ExtremeDisplayMode.LIVE_EXTRACT;
        if(!requested){if(enabled||world!=null)clear();return;}
        if(world!=client.level){clear();world=client.level;}
        enabled=extracting=true;frame++;leases.clear();reused=fresh=lightOverrides=0;
        if((frame&63)==0)slots.entrySet().removeIf(e->e.getKey().isRemoved()||frame-e.getValue().seen>120);
    }
    public static boolean active(){return enabled&&extracting;}
    public static void unload(Object entity){slots.remove(entity);}
    public static ItemDisplayEntityRenderState borrow(EntityRenderer<?,?> renderer,Display.ItemDisplay entity){
        if(!active()||entity.level()!=world||!ExtremeDisplayPerformance.target(entity)
                ||renderer.getClass()!=DisplayRenderer.ItemDisplayRenderer.class)return null;
        var slot=slots.get(entity);
        if(slot==null||!slot.available||slot.leased==frame){fresh++;freshTotal++;return null;}
        slot.available=false;slot.leased=frame;
        leases.add(entity);
        var state=slot.state;
        // Run every vanilla extraction/finalization callback again, with fresh per-frame attachments.
        state.clearExtraData();state.nameTag=null;state.scoreText=null;state.nameTagAttachment=null;
        state.leashStates=null;state.shadowPieces.clear();state.passengerOffset=null;
        ((DisplayLayerOwnership)state).drawless$borrowLayers();
        reused++;reusedTotal++;
        return state;
    }
    public static void accept(Display.ItemDisplay entity,ItemDisplayEntityRenderState state){
        if(!active()||entity==null||state.getClass()!=ItemDisplayEntityRenderState.class)return;
        var slot=slots.get(entity);
        if(slot==null){if(slots.size()>=MAX_STATES)return;slot=new Slot();slots.put(entity,slot);}
        slot.state=state;slot.available=true;slot.seen=frame;
    }
    public static void endFrame(){
        if(!active())return;
        // A leased state returned to ordinary rendering must never be mutated by this pool again.
        for(int i=0;i<leases.size();i++){
            var entity=leases.get(i);var slot=slots.get(entity);
            if(slot!=null&&!slot.available)slots.remove(entity);
        }
        leases.clear();
        var profiler=ru.wilyfox.drawless.diagnostics.RenderMetrics.getInstance();
        if(profiler.isEnabled()){
            profiler.incrementCounter("displayExtraction/reusedStates",reused);
            profiler.incrementCounter("displayExtraction/freshStates",fresh);
            profiler.incrementCounter("displayExtraction/brightnessOverrides",lightOverrides);
        }
        extracting=false;
    }
    /** Server override has exactly the same packing as vanilla getPackedLightCoords. */
    public static int brightnessOverride(EntityRenderer<?,?> renderer,Display.ItemDisplay entity){
        if(!active()||entity.level()!=world||!ExtremeDisplayPerformance.target(entity)
                ||renderer.getClass()!=DisplayRenderer.ItemDisplayRenderer.class)return -1;
        var render=entity.renderState();int value=render==null?-1:render.brightnessOverride();
        if(value!=-1){lightOverrides++;lightOverridesTotal++;}
        return value;
    }
    public static String diagnostic(){
        return String.format(Locale.ROOT,"statePool=%s; pooledStates=%d; stateReused=%d; stateFresh=%d; lightOverrides=%d; stateReusedTotal=%d; stateFreshTotal=%d; lightOverridesTotal=%d",
                enabled?"on":"off",slots.size(),reused,fresh,lightOverrides,reusedTotal,freshTotal,lightOverridesTotal);
    }
    private static final class Slot{ItemDisplayEntityRenderState state;long leased=-1,seen;boolean available;}
}
