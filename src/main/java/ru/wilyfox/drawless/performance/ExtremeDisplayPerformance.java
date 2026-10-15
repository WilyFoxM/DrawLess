package ru.wilyfox.drawless.performance;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.ItemDisplayEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;
import ru.wilyfox.drawless.config.DrawLessConfig;
import ru.wilyfox.drawless.config.ExtremeDisplayMode;
import ru.wilyfox.drawless.diagnostics.RenderMetrics;
import ru.wilyfox.drawless.diagnostics.DisplayCostProbe;

import java.util.*;

/** Diagnostic scene modes and conservative reuse of opaque geometry with an unchanged live pose. */
public final class ExtremeDisplayPerformance {
    private static final int MAX_PARTS=4096;
    private static final long MAX_BYTES=128L*1024*1024;
    private static final boolean SODIUM=net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium");
    private static final Set<Display.ItemDisplay> targets=Collections.newSetFromMap(new IdentityHashMap<>());
    private static final IdentityHashMap<Display.ItemDisplay,Snapshot> snapshots=new IdentityHashMap<>();
    private static final ArrayList<Batch> batches=new ArrayList<>();
    private static final ArrayList<Display.ItemDisplay> pending=new ArrayList<>();
    private static final HashSet<UUID> roots=new HashSet<>();
    private static final IdentityHashMap<Display.ItemDisplay,StablePose> stable=new IdentityHashMap<>();
    private static final IdentityHashMap<EntityRenderState,Display.ItemDisplay> extracted=new IdentityHashMap<>();
    private static final IdentityHashMap<Display.ItemDisplay,Admission> admissions=new IdentityHashMap<>();
    private static final Reason[] REASONS=Reason.values();
    private static final int[] reasonCounts=new int[REASONS.length];
    private static long admissionChecks,admissionChecksTotal,admissionNanosTotal,admissionFramesTotal;
    private static final long STABLE_NANOS=1_000_000_000L;
    private static long frameNow,invalidations,validationNanos;
    private static int unstable,unsafe,rejected;
    private static ClientLevel world;
    private static ExtremeDisplayMode mode=ExtremeDisplayMode.OFF;
    private static boolean all,registered,dirty=true,gpuDirty;
    private static long scanTick=Long.MIN_VALUE,bytes,skipped,captured,drawCalls,rebuilds,skippedTicks;
    private static long lastProfiledTicks;
    private static long selectNanos,captureNanos,uploadNanos,drawNanos;
    private static Vec3 origin=Vec3.ZERO;
    private static List<TextureAtlasSprite> animatedSprites=List.of();

    private ExtremeDisplayPerformance(){}
    public static void register() {
        if(registered)return;registered=true;
        ClientEntityEvents.ENTITY_LOAD.register((entity,level)->{if(entity instanceof Display.ItemDisplay)dirty=true;});
        ClientEntityEvents.ENTITY_UNLOAD.register((entity,level)->{
            if(level!=world)return;
            targets.remove(entity);dirty=true;
            stable.remove(entity);
            admissions.remove(entity);
            var removed=snapshots.remove(entity);
            if(removed!=null){bytes-=removed.bytes();gpuDirty=true;}
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->clear());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client->clear());
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context->{if(mode==ExtremeDisplayMode.FREEZE)draw(context);});
        LevelRenderEvents.AFTER_SOLID_FEATURES.register(context->{if(mode==ExtremeDisplayMode.STATIC_CACHE)draw(context);});
    }
    public static boolean requested(){return DrawLessConfig.get().enabled&&DrawLessConfig.get().extremeDisplayMode!=ExtremeDisplayMode.OFF;}
    static boolean target(Display.ItemDisplay entity){return entity.level()==world&&targets.contains(entity);}
    public static void clear() {
        closeBatches();targets.clear();snapshots.clear();pending.clear();roots.clear();world=null;
        stable.clear();extracted.clear();invalidations=validationNanos=0;unstable=unsafe=rejected=0;
        admissions.clear();Arrays.fill(reasonCounts,0);admissionChecks=admissionChecksTotal=admissionNanosTotal=admissionFramesTotal=0;
        animatedSprites=List.of();
        mode=ExtremeDisplayMode.OFF;dirty=true;gpuDirty=false;scanTick=Long.MIN_VALUE;origin=Vec3.ZERO;
        bytes=skipped=captured=drawCalls=rebuilds=skippedTicks=selectNanos=captureNanos=uploadNanos=drawNanos=0;
        lastProfiledTicks=0;
    }
    private static void closeBatches(){for(var batch:batches)batch.buffer.close();batches.clear();}
    public static void beginFrame(Minecraft client,LevelRenderState frame) {
        var config=DrawLessConfig.get();
        if(!config.enabled||config.extremeDisplayMode==ExtremeDisplayMode.OFF||client.level==null){if(mode!=ExtremeDisplayMode.OFF)clear();return;}
        if(world!=client.level||mode!=config.extremeDisplayMode||all!=config.extremeAllItemDisplays
                ||mode==ExtremeDisplayMode.STATIC_CACHE&&origin.distanceToSqr(frame.cameraRenderState.pos)>1_048_576) {
            clear();world=client.level;mode=config.extremeDisplayMode;all=config.extremeAllItemDisplays;
            origin=frame.cameraRenderState.pos;DisplayModelBudget.clear();NpcFrameCache.clear();
        }
        skipped=captured=0;selectNanos=captureNanos=uploadNanos=0;
        extracted.clear();frameNow=System.nanoTime();
        Arrays.fill(reasonCounts,0);admissionChecks=0;
        long tick=world.getGameTime();
        if(dirty||scanTick!=tick) {
            long start=System.nanoTime();
            try(var ignored=RenderMetrics.getInstance().scope("render/extremeDisplay/membership")){refresh();scanTick=tick;dirty=false;}
            finally{selectNanos=System.nanoTime()-start;}
        }
        if(mode==ExtremeDisplayMode.STATIC_CACHE) {
            long validationStart=System.nanoTime();
            try(var ignored=RenderMetrics.getInstance().scope("render/staticDisplay/validate")) {
                var iterator=snapshots.entrySet().iterator();
                while(iterator.hasNext()) {
                    var entry=iterator.next();var entity=entry.getKey();var pose=stable.get(entity);
                    entry.getValue().visibilitySeen=false;
                    if(pose==null || !pose.matches(entity) || !stationary(entity)
                            || !DisplayModelBudget.hasSafeLayers(entity,frameNow)
                            || !entry.getValue().lightMatches(client,entity,tick)) {
                        bytes-=entry.getValue().bytes();iterator.remove();stable.remove(entity);admissions.remove(entity);gpuDirty=true;invalidations++;
                    }
                }
            }finally{validationNanos=System.nanoTime()-validationStart;DisplayCostProbe.global("Static validation",validationNanos);}
            // Visibility is resolved by vanilla below, before rebuilding the opaque GPU batches.
            return;
        }
        if(mode==ExtremeDisplayMode.FREEZE&&!pending.isEmpty()) {
            long start=System.nanoTime();
            try(var ignored=RenderMetrics.getInstance().scope("render/extremeDisplay/capture")) {
                var iterator=pending.iterator();
                while(iterator.hasNext()) {
                    var entity=iterator.next();
                    if(entity.isRemoved()||!targets.contains(entity)){iterator.remove();continue;}
                    if(entity.getItemStack().isEmpty())continue; // Spawn packets can populate the item later.
                    if(snapshots.size()>=MAX_PARTS||bytes>=MAX_BYTES)break;
                    var state=(ItemDisplayEntityRenderState)client.getEntityRenderDispatcher().extractEntity(entity,1);
                    if(state.renderState==null||state.item.isEmpty())continue;
                    freezePose(state);
                    Snapshot snapshot;
                    try(var collector=new Capture()) {
                        client.getEntityRenderDispatcher().submit(state,frame.cameraRenderState,
                                state.x-origin.x,state.y-origin.y,state.z-origin.z,new PoseStack(),collector);
                        var meshes=collector.finish();
                        boolean fallback=!collector.getSubmitsPerOrder().isEmpty()||meshes.isEmpty();
                        snapshot=new Snapshot(state,fallback?List.of():meshes,fallback);
                        if(!fallback)snapshot.sprites=List.copyOf(collector.sprites);
                    }
                    if(bytes+snapshot.bytes()>MAX_BYTES) snapshot=new Snapshot(state,List.of(),true);
                    snapshots.put(entity,snapshot);bytes+=snapshot.bytes();iterator.remove();captured++;gpuDirty=true;
                }
            } finally{captureNanos=System.nanoTime()-start;}
        }
        if(gpuDirty)rebuildGpu();
    }
    private static void refresh() {
        targets.clear();roots.clear();
        if(!all)for(var e:world.entitiesForRendering())if(e instanceof Display.ItemDisplay item&&enginePart(item)) {
            var root=e.getRootVehicle();if(root!=e&&!(root instanceof Player))roots.add(root.getUUID());
        }
        for(var e:world.entitiesForRendering())if(e instanceof Display.ItemDisplay item&&!e.isRemoved()
                &&!(e.getRootVehicle() instanceof Player)&&(all||enginePart(item)||roots.contains(e.getRootVehicle().getUUID())))targets.add(item);
        var iterator=snapshots.entrySet().iterator();
        while(iterator.hasNext()) {var entry=iterator.next();if(!targets.contains(entry.getKey())){bytes-=entry.getValue().bytes();iterator.remove();gpuDirty=true;}}
        pending.clear();if(mode==ExtremeDisplayMode.FREEZE)for(var target:targets)if(!snapshots.containsKey(target))pending.add(target);
        stable.keySet().removeIf(entity->!targets.contains(entity));
        admissions.keySet().removeIf(entity->!targets.contains(entity));
    }
    public static boolean staticCache(){return requested()&&mode==ExtremeDisplayMode.STATIC_CACHE;}
    public static void recordState(Entity entity,EntityRenderState state) {
        if(!staticCache()||!(entity instanceof Display.ItemDisplay item)||!targets.contains(item))return;
        var admission=admissions.get(item);
        if(admission==null) {
            if(admissions.size()>=MAX_PARTS)return;
            admission=new Admission();admissions.put(item,admission);
        }
        reasonCounts[admission.reason.ordinal()]++;
        // Reconsider failed admission at 20Hz. Vanilla rendering stays live every frame;
        // a cached part is still invalidated independently before EACH visible-entity pass.
        if(admission.tick!=world.getGameTime()||admission.reason==Reason.READY)extracted.put(state,item);
    }
    /** Keep vanilla frustum/distance decisions, even for a part whose geometry is already cached. */
    public static boolean staticVisibility(Entity entity,boolean visible) {
        var snapshot=snapshots.get(entity);
        if(snapshot==null)return visible;
        snapshot.visibilitySeen=true;
        if(snapshot.visible!=visible){snapshot.visible=visible;gpuDirty=true;}
        if(visible)skipped++;
        return false;
    }
    public static void finishFrame(Minecraft client,LevelRenderState frame) {
        if(!staticCache())return;
        unstable=unsafe=rejected=0;
        long start=System.nanoTime();
        int attempts=0;
        try(var ignored=RenderMetrics.getInstance().scope("render/staticDisplay/capture")) {
            var iterator=extracted.isEmpty()?Collections.<EntityRenderState>emptyIterator():frame.entityRenderStates.iterator();
            while(iterator.hasNext()) {
                var state=iterator.next();var entity=extracted.get(state);
                if(entity==null || !(state instanceof ItemDisplayEntityRenderState itemState))continue;
                var admission=admissions.get(entity);
                var reason=qualify(entity,itemState);
                admissionChecks++;admissionChecksTotal++;
                decision(admission,reason);
                if(reason!=Reason.READY)continue;
                var pose=stable.get(entity);
                if(attempts>=4 || attempts>0&&System.nanoTime()-start>2_000_000L)continue;
                attempts++;
                try(var collector=new Capture(true)) {
                    client.getEntityRenderDispatcher().submit(itemState,frame.cameraRenderState,
                            itemState.x-origin.x,itemState.y-origin.y,itemState.z-origin.z,new PoseStack(),collector);
                    if(collector.rejected || !collector.getSubmitsPerOrder().isEmpty()) {
                        pose.rejection=collector.reason!=null?collector.reason:Reason.SPECIAL_RENDERER;decision(admission,pose.rejection);continue;
                    }
                    var meshes=collector.finish();
                    if(meshes.isEmpty()){pose.rejection=Reason.EMPTY_GEOMETRY;decision(admission,pose.rejection);continue;}
                    var snapshot=new Snapshot(itemState,meshes,false);
                    snapshot.sprites=List.copyOf(collector.sprites);
                    if(bytes+snapshot.bytes()>MAX_BYTES)continue;
                    snapshots.put(entity,snapshot);bytes+=snapshot.bytes();captured++;gpuDirty=true;admissions.remove(entity);
                    reasonCounts[Reason.READY.ordinal()]--;
                    iterator.remove(); // Never submit both the cached and ordinary representation in one frame.
                }
            }
        } finally{
            captureNanos=System.nanoTime()-start;admissionNanosTotal+=captureNanos;admissionFramesTotal++;
            extracted.clear();DisplayCostProbe.global("Static admission scan",captureNanos);
        }
        // Vanilla can skip an entity before calling isEntityVisible (e.g. unloaded chunk).
        for(var snapshot:snapshots.values())if(!snapshot.visibilitySeen&&snapshot.visible){snapshot.visible=false;gpuDirty=true;}
        if(gpuDirty)rebuildGpu();
        unstable=reasonCounts[Reason.POSITION.ordinal()]+reasonCounts[Reason.CARRIER.ordinal()]
                +reasonCounts[Reason.INTERPOLATION.ordinal()]+reasonCounts[Reason.POSE_UPDATED.ordinal()]+reasonCounts[Reason.WARMING.ordinal()];
        unsafe=reasonCounts[Reason.UNSAFE_MODEL.ordinal()];
        rejected=0;for(var reason:REASONS)if(reason.visual)rejected+=reasonCounts[reason.ordinal()];
        if(DisplayCostProbe.frame()) {
            for(var state:frame.entityRenderStates) {
                if(!(state instanceof ItemDisplayEntityRenderState))continue;
                var cost=DisplayCostProbe.source(state);if(cost==null)continue;
                var entity=DisplayCostProbe.entity(state);var admission=admissions.get(entity);
                if(admission!=null)DisplayCostProbe.admission(cost,admission.reason.label);
            }
            for(var entry:snapshots.entrySet())if(entry.getValue().visible)
                DisplayCostProbe.admission(DisplayCostProbe.cost(entry.getKey()),"cached");
        }
    }
    private static void decision(Admission admission,Reason reason) {
        reasonCounts[admission.reason.ordinal()]--;reasonCounts[reason.ordinal()]++;
        admission.reason=reason;admission.tick=world.getGameTime();
    }
    private static Reason qualify(Display.ItemDisplay entity,ItemDisplayEntityRenderState state) {
        if(state.renderState==null)return Reason.NO_RENDER;
        if(state.item.isEmpty()){stable.remove(entity);return Reason.EMPTY;}
        var hard=stationaryReason(entity);
        // The progress already computed by vanilla is read here; recomputing would modify lastProgress.
        if(hard!=Reason.READY||state.interpolationProgress<1) {
            stable.remove(entity);return hard==Reason.READY?Reason.INTERPOLATION:hard;
        }
        if(!DisplayModelBudget.hasSafeLayers(entity,frameNow)){stable.remove(entity);return Reason.UNSAFE_MODEL;}
        var pose=stable.get(entity);
        if(pose==null||!pose.matches(entity)) {
            if(stable.size()<MAX_PARTS)stable.put(entity,new StablePose(entity,frameNow));
            return Reason.POSE_UPDATED;
        }
        if(pose.rejection!=null)return pose.rejection;
        if(frameNow-pose.since<STABLE_NANOS)return Reason.WARMING;
        if(snapshots.size()>=MAX_PARTS||bytes>=MAX_BYTES)return Reason.CAPACITY;
        // Atlas animation changes texture pixels, not static vertices. Foil is rejected by Capture;
        // genuinely dynamic item models were already excluded by hasSafeLayers.
        if(state.appearsGlowing()||state.displayFireAnimation||state.nameTag!=null
                ||!state.shadowPieces.isEmpty()||state.leashStates!=null&&!state.leashStates.isEmpty())
            return pose.rejection=Reason.EXTRA_VISUAL;
        return Reason.READY;
    }
    private enum Reason {
        PENDING("pending",false),READY("ready",false),NO_RENDER("no-render",false),EMPTY("empty",false),
        BILLBOARD("billboard",true),SHADOW("shadow",true),POSITION("position",false),CARRIER("carrier",false),
        GLOW("glow",true),FIRE("fire",true),NAME("name",true),
        INTERPOLATION("interpolation",false),UNSAFE_MODEL("unsafe-model",false),POSE_UPDATED("pose-updated",false),
        WARMING("warming",false),EXTRA_VISUAL("extra-visual",true),
        TRANSPARENCY("transparency",true),FOIL("foil-outline",true),SPECIAL_RENDERER("special-renderer",true),
        EMPTY_GEOMETRY("empty-geometry",true),CAPACITY("capacity",false);
        final String label;final boolean visual;
        Reason(String label,boolean visual){this.label=label;this.visual=visual;}
    }
    private static final class Admission {long tick=Long.MIN_VALUE;Reason reason=Reason.PENDING;}
    private static boolean stationary(Display.ItemDisplay entity) {
        return stationaryReason(entity)==Reason.READY;
    }
    private static Reason stationaryReason(Display.ItemDisplay entity) {
        var render=entity.renderState();if(render==null)return Reason.NO_RENDER;
        if(render.billboardConstraints()!=Display.BillboardConstraints.FIXED)return Reason.BILLBOARD;
        if(render.shadowRadius().get(1)!=0)return Reason.SHADOW;
        if(!still(entity))return Reason.POSITION;
        if(!still(entity.getRootVehicle()))return Reason.CARRIER;
        if(Minecraft.getInstance().shouldEntityAppearGlowing(entity))return Reason.GLOW;
        if(entity.displayFireAnimation())return Reason.FIRE;
        // DisplayRenderer still submits Item Display geometry when its entity flag is invisible.
        // ModelEngine uses that flag on visible bone carriers; it is not a culling condition.
        if(entity.getCustomName()!=null)return Reason.NAME;
        return Reason.READY;
    }
    private static boolean still(Entity entity) {
        return entity.xo==entity.getX() && entity.yo==entity.getY() && entity.zo==entity.getZ()
                && entity.yRotO==entity.getYRot() && entity.xRotO==entity.getXRot();
    }
    private static final class StablePose {
        final Object render,item;final Entity root;
        final double x,y,z,rootX,rootY,rootZ;final float yaw,pitch,rootYaw,rootPitch;
        final long since;Reason rejection;
        StablePose(Display.ItemDisplay entity,long now) {
            render=entity.renderState();item=entity.itemRenderState();root=entity.getRootVehicle();
            x=entity.getX();y=entity.getY();z=entity.getZ();yaw=entity.getYRot();pitch=entity.getXRot();
            rootX=root.getX();rootY=root.getY();rootZ=root.getZ();rootYaw=root.getYRot();rootPitch=root.getXRot();since=now;
        }
        boolean matches(Display.ItemDisplay entity) {
            return render==entity.renderState() && item==entity.itemRenderState() && root==entity.getRootVehicle()
                    && x==entity.getX() && y==entity.getY() && z==entity.getZ() && yaw==entity.getYRot() && pitch==entity.getXRot()
                    && rootX==root.getX() && rootY==root.getY() && rootZ==root.getZ() && rootYaw==root.getYRot() && rootPitch==root.getXRot();
        }
    }
    private static boolean enginePart(Display.ItemDisplay item) {
        var stack=item.getItemStack();var id=stack.get(DataComponents.ITEM_MODEL);
        return id!=null&&id.getNamespace().startsWith("modelengine")
                ||stack.is(Items.BONE)&&(stack.has(DataComponents.CUSTOM_MODEL_DATA)
                ||id!=null&&!id.toString().equals("minecraft:bone"));
    }
    private static void freezePose(ItemDisplayEntityRenderState state) {
        var render=state.renderState;float p=state.interpolationProgress;
        state.renderState=new Display.RenderState(Display.GenericInterpolator.constant(render.transformation().get(p)),
                render.billboardConstraints(),render.brightnessOverride(),Display.FloatInterpolator.constant(render.shadowRadius().get(p)),
                Display.FloatInterpolator.constant(render.shadowStrength().get(p)),render.glowColorOverride());
        state.interpolationProgress=0;
    }
    /** -1: vanilla, 0: omit extraction, 1: frozen fallback uses the saved render state. */
    public static int visibility(Entity entity) {
        if(!requested()||entity.level()!=world||!targets.contains(entity))return -1;
        if(mode==ExtremeDisplayMode.TICKS_ONLY||mode==ExtremeDisplayMode.STATIC_CACHE)return -1;
        if(mode==ExtremeDisplayMode.UNRENDER){skipped++;return 0;}
        var snapshot=snapshots.get(entity);
        if(snapshot==null)return -1;
        if(snapshot.fallback)return 1;
        skipped++;return 0;
    }
    public static EntityRenderState reuse(Entity entity) {
        if(!requested()||mode!=ExtremeDisplayMode.FREEZE||entity.level()!=world)return null;
        var snapshot=snapshots.get(entity);return snapshot==null?null:snapshot.state;
    }
    public static boolean skipTick(Display entity) {
        if(!requested()||entity.level()!=world||!targets.contains(entity))return false;
        boolean skip=mode==ExtremeDisplayMode.TICKS_ONLY||DrawLessConfig.get().extremePauseDisplayTicks
                &&(mode==ExtremeDisplayMode.UNRENDER||mode==ExtremeDisplayMode.FREEZE&&snapshots.containsKey(entity));
        if(skip)skippedTicks++;return skip;
    }
    private static void rebuildGpu() {
        long start=System.nanoTime();
        try(var ignored=RenderMetrics.getInstance().scope("render/extremeDisplay/upload")) {
            var byType=new LinkedHashMap<RenderType,List<Mesh>>();
            Set<TextureAtlasSprite> sprites=Collections.newSetFromMap(new IdentityHashMap<>());
            for(var snapshot:snapshots.values())if(snapshot.visible) {
                sprites.addAll(snapshot.sprites);
                for(var mesh:snapshot.meshes)byType.computeIfAbsent(mesh.type,k->new ArrayList<>()).add(mesh);
            }
            var next=new ArrayList<Batch>();
            try {
                for(var entry:byType.entrySet()) {
                    int total=entry.getValue().stream().mapToInt(m->m.vertices.length).sum();
                    var data=MemoryUtil.memAlloc(total);
                    try {
                        int vertices=0;for(var mesh:entry.getValue()){data.put(mesh.vertices);vertices+=mesh.vertexCount;}data.flip();
                        var gpu=RenderSystem.getDevice().createBuffer(()->"DrawLess frozen Item Display scene",GpuBuffer.USAGE_VERTEX,data);
                        next.add(new Batch(entry.getKey(),gpu,vertices/4*6));
                    } finally{MemoryUtil.memFree(data);}
                }
            } catch(RuntimeException|Error error){for(var batch:next)batch.buffer.close();throw error;}
            closeBatches();batches.addAll(next);animatedSprites=List.copyOf(sprites);gpuDirty=false;rebuilds++;
        } finally{uploadNanos=System.nanoTime()-start;}
    }
    private static void draw(LevelRenderContext context) {
        if(!requested()||(mode!=ExtremeDisplayMode.FREEZE&&mode!=ExtremeDisplayMode.STATIC_CACHE)||world!=Minecraft.getInstance().level)return;
        long start=System.nanoTime();drawCalls=0;
        var matrix=RenderSystem.getModelViewStack();matrix.pushMatrix();
        try(var ignored=RenderMetrics.getInstance().scope("render/extremeDisplay/draw")) {
            // Cached vertices bypass Sodium's ordinary item submission tracking. Keep visible atlas
            // animations active through its public API without rebuilding or re-uploading geometry.
            if(SODIUM)for(var sprite:animatedSprites)SodiumSprites.mark(sprite);
            var camera=context.levelState().cameraRenderState;
            // Persist vertices relative to one nearby world origin; camera movement updates only this uniform.
            matrix.set(camera.viewRotationMatrix).translate((float)(origin.x-camera.pos.x),(float)(origin.y-camera.pos.y),(float)(origin.z-camera.pos.z));
            for(var batch:batches) {
                var indices=RenderSystem.getSequentialBuffer(batch.type.primitiveTopology());var indexBuffer=indices.getBuffer(batch.indexCount);
                batch.type.prepare().drawFromBuffer(batch.buffer,indexBuffer,indices.type(),0,0,batch.indexCount);drawCalls++;
            }
        } finally{matrix.popMatrix();drawNanos=System.nanoTime()-start;}
        var profiler=RenderMetrics.getInstance();if(profiler.isEnabled())profiler.incrementCounter("extremeDisplay/drawCalls",drawCalls);
    }
    public static void endFrame() {
        if(mode==ExtremeDisplayMode.OFF)return;var profiler=RenderMetrics.getInstance();
        if(profiler.isEnabled()) {
            profiler.incrementCounter("extremeDisplay/skippedStates",skipped);profiler.incrementCounter("extremeDisplay/capturedParts",captured);
            profiler.incrementCounter("extremeDisplay/targets",targets.size());profiler.incrementCounter("extremeDisplay/skippedTicks",skippedTicks-lastProfiledTicks);
        }
        lastProfiledTicks=skippedTicks;
    }
    public static String diagnostic() {
        if(DrawLessConfig.get().extremeDisplayMode.liveCache())return LiveDisplayGeometry.diagnostic();
        if(mode==ExtremeDisplayMode.OFF)return "off";
        int fallback=0;for(var snapshot:snapshots.values())if(snapshot.fallback)fallback++;
        return String.format(Locale.ROOT,"%s; scope=%s; pauseTicks=%s; targets=%d; snapshots=%d; fallback=%d; skippedStates=%d; captured=%d; batches=%d; drawCalls=%d; verticesBytes=%d; rebuilds=%d; pausedTicksTotal=%d; membership=%.3fms; capture=%.3fms; upload=%.3fms; drawCpu=%.3fms; staticInvalidations=%d; unstable=%d; unsafeModel=%d; rejectedVisual=%d",
                mode,all?"all-item-displays":"modelengine",mode==ExtremeDisplayMode.TICKS_ONLY||mode!=ExtremeDisplayMode.STATIC_CACHE&&DrawLessConfig.get().extremePauseDisplayTicks,
                targets.size(),snapshots.size(),fallback,skipped,captured,batches.size(),drawCalls,bytes,rebuilds,
                skippedTicks,selectNanos/1e6,captureNanos/1e6,uploadNanos/1e6,drawNanos/1e6,invalidations,unstable,unsafe,rejected)
                +String.format(Locale.ROOT,"; validation=%.3fms; admissionChecks=%d; admissionChecksTotal=%d; admissionCpuTotalMs=%.3f; admissionFramesTotal=%d; animatedSprites=%d; admission=%s",
                        validationNanos/1e6,admissionChecks,admissionChecksTotal,admissionNanosTotal/1e6,admissionFramesTotal,animatedSprites.size(),admissionSummary());
    }
    private static String admissionSummary() {
        var out=new StringJoiner(",");
        for(var reason:REASONS)if(reasonCounts[reason.ordinal()]>0)out.add(reason.label+":"+reasonCounts[reason.ordinal()]);
        return out.toString();
    }
    private static final class Snapshot {
        final ItemDisplayEntityRenderState state;final List<Mesh> meshes;final boolean fallback;boolean visible=true,visibilitySeen=true;
        List<TextureAtlasSprite> sprites=List.of();
        long lightTick=Long.MIN_VALUE;boolean lightValid=true;
        Snapshot(ItemDisplayEntityRenderState state,List<Mesh> meshes,boolean fallback){this.state=state;this.meshes=meshes;this.fallback=fallback;}
        boolean lightMatches(Minecraft client,Display.ItemDisplay entity,long tick) {
            if(lightTick!=tick){lightTick=tick;lightValid=client.getEntityRenderDispatcher().getRenderer(entity).getPackedLightCoords(entity,1)==state.lightCoords;}
            return lightValid;
        }
        long bytes(){long sum=0;for(var mesh:meshes)sum+=mesh.vertices.length;return sum;}
    }
    private record Mesh(RenderType type,byte[] vertices,int vertexCount){}
    private record Batch(RenderType type,GpuBuffer buffer,int indexCount){}
    private static final class Capture extends SubmitNodeStorage implements AutoCloseable {
        private final boolean strict;private boolean rejected;private Reason reason;
        Capture(){this(false);}Capture(boolean strict){this.strict=strict;}
        private final LinkedHashMap<RenderType,Builder> builders=new LinkedHashMap<>();
        private final QuadInstance instance=new QuadInstance();
        private final Set<TextureAtlasSprite> sprites=Collections.newSetFromMap(new IdentityHashMap<>());
        @Override public void submitItem(PoseStack pose,ItemDisplayContext context,int light,int overlay,int outline,int[] tints,List<BakedQuad> quads,ItemStackRenderState.FoilType foil) {
            if(strict&&(foil!=ItemStackRenderState.FoilType.NONE || outline!=0)){rejected=true;reason=Reason.FOIL;return;}
            instance.setLightCoords(light);instance.setOverlayCoords(overlay);
            for(var quad:quads) {
                var material=quad.materialInfo();var type=material.itemRenderType();
                if(material.sprite().isAnimated())sprites.add(material.sprite());
                if(strict&&(type.hasBlending() || type.sortOnUpload())){rejected=true;reason=Reason.TRANSPARENCY;return;}
                instance.setColor(material.isTinted()&&material.tintIndex()>=0&&material.tintIndex()<tints.length?tints[material.tintIndex()]:-1);
                builders.computeIfAbsent(type,Builder::new).builder.putBakedQuad(pose.last(),quad,instance);
            }
        }
        List<Mesh> finish() {
            var result=new ArrayList<Mesh>();
            for(var entry:builders.entrySet())try(var mesh=entry.getValue().builder.build()) {
                if(mesh==null)continue;
                var vertices=new byte[mesh.vertexBuffer().remaining()];mesh.vertexBuffer().get(vertices);
                result.add(new Mesh(entry.getKey(),vertices,mesh.drawState().vertexCount()));
            }
            return result;
        }
        @Override public void close(){for(var builder:builders.values())builder.memory.close();}
    }
    private static final class SodiumSprites {
        static void mark(TextureAtlasSprite sprite){net.caffeinemc.mods.sodium.api.texture.SpriteUtil.INSTANCE.markSpriteActive(sprite);}
    }
    private static final class Builder {
        final ByteBufferBuilder memory=new ByteBufferBuilder(4096);
        final BufferBuilder builder;
        Builder(RenderType type){builder=new BufferBuilder(memory,type.primitiveTopology(),type.format());}
    }
}
