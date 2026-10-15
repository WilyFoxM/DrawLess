package ru.wilyfox.drawless.diagnostics;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import java.util.*;

/** Short explicit probe only. One rendered frame and one client tick per second, no idle world scans. */
public final class DisplayCostProbe {
    private static final int MAX_MODELS=512;
    private static final Map<String,Cost> costs=new LinkedHashMap<>();
    private static final IdentityHashMap<EntityRenderState,Cost> states=new IdentityHashMap<>();
    private static final IdentityHashMap<EntityRenderState,Entity> entities=new IdentityHashMap<>();
    private static final IdentityHashMap<ItemFeatureRenderer.Submit,Cost> submits=new IdentityHashMap<>();
    private static final Map<String,Long> global=new LinkedHashMap<>();
    private static boolean active,frame,tick,registered;
    private static long nextFrame,nextTick;
    private static long sceneStarted;
    private static int frames,ticks;
    private static long itemDraws;
    private static Cost source;
    private DisplayCostProbe(){}
    public static void register() {
        if(registered)return;registered=true;
        ClientTickEvents.START_CLIENT_TICK.register(client->{
            tick=false;if(!active||!DisplayProbe.measuring(client))return;
            long now=System.nanoTime();if(now>=nextTick){tick=true;ticks++;nextTick=now+1_000_000_000L;}
        });
        ClientTickEvents.END_CLIENT_TICK.register(client->tick=false);
    }
    public static void start(){stop();active=true;}
    public static void stop(){active=frame=tick=false;source=null;nextFrame=nextTick=sceneStarted=itemDraws=0;frames=ticks=0;costs.clear();states.clear();entities.clear();submits.clear();global.clear();}
    public static void beginFrame(Minecraft client) {
        frame=false;source=null;states.clear();entities.clear();submits.clear();
        if(!active||!DisplayProbe.measuring(client))return;
        long now=System.nanoTime();if(now>=nextFrame){frame=true;frames++;nextFrame=now+1_000_000_000L;sceneStarted=now;}
    }
    public static boolean frame(){return frame;}
    public static void itemDraw(){if(frame)itemDraws++;}
    public static void global(String stage,long nanos){if(frame)global.merge(stage,nanos,Long::sum);}
    public static void sceneEnd(){if(frame)global("Visible entity extraction (inclusive)",System.nanoTime()-sceneStarted);}
    public static boolean tick(Entity entity){return tick&&entity instanceof Display.ItemDisplay;}
    public static Cost cost(Entity entity) {
        if(!(entity instanceof Display.ItemDisplay item))return null;
        var stack=item.getItemStack();var model=stack.get(DataComponents.ITEM_MODEL);
        String key=model==null?stack.getItem().toString():model.toString();
        var cmd=stack.get(DataComponents.CUSTOM_MODEL_DATA);
        if(cmd!=null&&(model==null||model.getNamespace().equals("minecraft"))) {
            if(!cmd.floats().isEmpty())key+=" [CMD="+cmd.floats().getFirst()+"]";
            if(!cmd.strings().isEmpty())key+=" [CMDs="+cmd.strings().getFirst()+"]";
        }
        if(!costs.containsKey(key)&&costs.size()>=MAX_MODELS)key="other (model limit)";
        return costs.computeIfAbsent(key,Cost::new);
    }
    public static void extracted(Entity entity,EntityRenderState state,Cost cost,long nanos){
        if(cost==null)return;cost.extractNanos+=nanos;cost.extractCalls++;states.put(state,cost);entities.put(state,entity);
    }
    public static Entity entity(EntityRenderState state){return frame?entities.get(state):null;}
    public static void admission(Cost cost,String reason) {
        if(!frame||cost==null)return;
        if(cost.admissions==null)cost.admissions=new LinkedHashMap<>();
        cost.admissions.merge(reason,1L,Long::sum);
    }
    public static Cost source(EntityRenderState state){return frame?states.get(state):null;}
    public static Cost bind(Cost cost){Cost previous=source;source=cost;return previous;}
    public static void tagged(ItemFeatureRenderer.Submit submit){if(frame&&source!=null)submits.put(submit,source);}
    public static Cost submitCost(ItemFeatureRenderer.Submit submit){return frame?submits.get(submit):null;}
    public static String report() {
        var out=new StringBuilder("\n## Sampled Item Display CPU cost by model\n\n");
        out.append("Sampled render frames: ").append(frames).append("; sampled client ticks: ").append(ticks).append(". At most one frame and one tick per second while measuring; not every frame.\n\n");
        out.append(String.format(Locale.ROOT,"Ordinary ItemFeatureRenderer draw calls per sampled frame (all items, excluding our GPU cache): %.2f\n\n",itemDraws/(double)Math.max(1,frames)));
        out.append("| Global stage | ms/sampled frame |\n| --- | ---: |\n");
        global.forEach((name,nanos)->out.append(String.format(Locale.ROOT,"| %s | %.5f |\n",name,nanos/1e6/Math.max(1,frames))));
        out.append("\nVisible extraction includes cache refresh, individual extraction and LIVE capture/upload. LIVE eligibility/local pose/positioning-grouping are components of LIVE capture, not additional costs. Item preparation group includes per-model Vertices and render-type setup; do not add inclusive totals to their components. Staged upload finishes/sorts meshes and uploads shared geometry for all features, not just Display.\n\n");
        out.append("Tick includes tick/rideTick. Extract creates entity/item render state; Submit records deferred geometry; Vertices builds deferred item vertices (including foil/outline), not GPU execution. Submit and Vertices are separate stages. Values are cumulative cost for all parts of each model divided by the corresponding sampled frame/tick count. These averages are not directly additive: client ticks normally run at 20Hz, frames at observed FPS. No packet parsing or GPU time is measured here.\n\n");
        out.append("| Item model / CMD | Tick ms/tick | Tick calls/tick | Extract ms/frame | Extract calls/frame | Submit ms/frame | Vertices ms/frame | Quads/frame |\n| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        costs.values().stream().sorted(Comparator.comparingDouble((Cost c)->c.tickNanos/(double)Math.max(1,ticks)+c.extractNanos/(double)Math.max(1,frames)+c.submitNanos/(double)Math.max(1,frames)+c.vertexNanos/(double)Math.max(1,frames)).reversed()).limit(64)
                .forEach(c->out.append(String.format(Locale.ROOT,"| %s | %.5f | %.2f | %.5f | %.2f | %.5f | %.5f | %.2f |\n",
                        c.model.replace("|","\\|").replace("\n"," ").replace("\r"," "),c.tickNanos/1e6/Math.max(1,ticks),c.tickCalls/(double)Math.max(1,ticks),
                        c.extractNanos/1e6/Math.max(1,frames),c.extractCalls/(double)Math.max(1,frames),c.submitNanos/1e6/Math.max(1,frames),c.vertexNanos/1e6/Math.max(1,frames),c.quads/(double)Math.max(1,frames))));
        var ordered=costs.values().stream().filter(c->c.admissions!=null)
                .sorted(Comparator.comparingLong((Cost c)->c.vertexNanos).reversed()).toList();
        if(!ordered.isEmpty()) {
            boolean live=ru.wilyfox.drawless.performance.LiveDisplayGeometry.requested();
            out.append(live?"\n## Live geometry cache admission by model\n\n":"\n## Static cache admission by model\n\n");
            out.append(live?"Live poses, light and tint are streamed every frame; local geometry is reused. Vanilla fallback reasons are checked on each extracted state.\n\n"
                    :"Last known admission for normally rendered parts, reconsidered once per client tick; cached parts are checked every frame. Reasons can change during the recording. The table contains the 64 highest vertex-cost models plus up to 64 cached models; it is not the complete scene. Cached models still run live client ticks.\n\n");
            out.append("| Item model / CMD | Admission | Parts/sample frame |\n| --- | --- | ---: |\n");
            var selected=new LinkedHashSet<Cost>();ordered.stream().limit(64).forEach(selected::add);
            ordered.stream().filter(c->c.admissions.containsKey("cached")||c.admissions.containsKey("live-cached")).limit(64).forEach(selected::add);
            for(var c:selected)for(var entry:c.admissions.entrySet())out.append(String.format(Locale.ROOT,"| %s | %s | %.2f |\n",
                    c.model.replace("|","\\|").replace("\n"," ").replace("\r"," "),entry.getKey(),entry.getValue()/(double)Math.max(1,frames)));
        }
        return out.toString();
    }
    public static final class Cost {
        final String model;
        public long tickNanos,tickCalls,extractNanos,extractCalls,submitNanos,vertexNanos,quads;
        private Map<String,Long> admissions;
        Cost(String model){this.model=model;}
    }
}
