package ru.wilyfox.drawless.performance;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.ItemDisplayEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL31;
import org.lwjgl.system.MemoryUtil;
import ru.wilyfox.drawless.DrawLess;
import ru.wilyfox.drawless.compat.RenderModSupport;
import ru.wilyfox.drawless.config.DrawLessConfig;
import ru.wilyfox.drawless.config.ExtremeDisplayMode;
import ru.wilyfox.drawless.diagnostics.DisplayCostProbe;
import ru.wilyfox.drawless.diagnostics.RenderMetrics;

import java.nio.ByteBuffer;
import java.util.*;

import static ru.wilyfox.drawless.performance.LiveGeometryEncoding.*;

/**
 * Experimental indexed vertex pulling. Local meshes are shared; only current vanilla poses/light/tints
 * are streamed. Encoded indices select a mesh vertex and an instance, so a material needs one draw,
 * without a draw call per bone, compute shaders, or decimating animation/ticks.
 */
public final class LiveDisplayGeometry {
    private static final int MAX_PARTS=4096,MAX_MESHES=4096,MAX_ALIASES=8192;
    private static final long MAX_INDEX_BYTES=16L*1024*1024;
    private static final Identifier ITEM_SHADER=Identifier.withDefaultNamespace("core/item");
    private static final Identifier VERTEX_SHADER=Identifier.fromNamespaceAndPath("drawless","core/live_item");
    private static final boolean SODIUM=RenderModSupport.SODIUM;
    private static final BindGroupLayout MESH_LAYOUT=BindGroupLayout.builder()
            .withUniform("FHGeometry",UniformType.TEXEL_BUFFER,GpuFormat.RGBA32_FLOAT)
            .withUniform("FHInstances",UniformType.TEXEL_BUFFER,GpuFormat.RGBA32_UINT).build();
    private static final IdentityHashMap<EntityRenderState,Display.ItemDisplay> extracted=new IdentityHashMap<>();
    private static final IdentityHashMap<Display.ItemDisplay,Part> parts=new IdentityHashMap<>();
    private static final IdentityHashMap<List<BakedQuad>,Geometry> aliases=new IdentityHashMap<>();
    private static final HashMap<QuadKey,Geometry> geometries=new HashMap<>();
    private static final IdentityHashMap<RenderPipeline,RenderPipeline> pipelines=new IdentityHashMap<>();
    private static final IdentityHashMap<RenderType,TypeBatches> batches=new IdentityHashMap<>();
    private static final ArrayList<Batch> activeBatches=new ArrayList<>();
    private static final Set<EntityRenderState> accepted=Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<TextureAtlasSprite> animated=Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Capture capture=new Capture();
    private static PoseStack pose=new PoseStack();
    private static final Vector3f normal=new Vector3f();
    private static final int[] reasons=new int[Reason.values().length];
    private static ClientLevel world;
    private static boolean registered,enabled,checked,all;
    private static ExtremeDisplayMode liveMode;
    private static String blocked="off";
    private static ByteBuffer geometryData;
    private static GpuBuffer geometryBuffer;
    private static int vertices,uploadedVertices,maxVertices,meshCount,buildsThisFrame,frame,keyQuads;
    private static long meshBuilds,indexRebuilds,geometryUploads,geometryUploadedBytes,instanceUploadedBytes;
    private static long instances,quads,draws,fallbacks,indexBytes,buildNanos,captureNanos,uploadNanos,drawNanos;
    private static long framesTotal,captureTotal,uploadTotal,drawTotal;
    private static long frameNow;
    private static long visibleVertices,interpolatingParts;
    private static int visibleMeshCount;
    private static long poseReused,poseSubmitted,poseReusedTotal,poseSubmittedTotal;
    private static long entityLoopStarted,entityLoopNanos,entityLoopTotal,entityLoopFrames;
    private LiveDisplayGeometry(){}

    public static void register(){
        if(registered)return;registered=true;
        ClientEntityEvents.ENTITY_UNLOAD.register((entity,level)->{parts.remove(entity);DisplayExtractionCache.unload(entity);});
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->clear());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client->clear());
        LevelRenderEvents.AFTER_SOLID_FEATURES.register(LiveDisplayGeometry::draw);
    }
    public static boolean requested(){return DrawLessConfig.get().enabled&&DrawLessConfig.get().extremeDisplayMode.liveCache();}
    public static boolean ready(){return enabled;}
    public static void clear(){
        for(var type:batches.values())for(var batch:type.chunks)batch.close();
        batches.clear();activeBatches.clear();accepted.clear();extracted.clear();parts.clear();
        aliases.clear();geometries.clear();animated.clear();
        if(geometryBuffer!=null){geometryBuffer.close();geometryBuffer=null;}
        if(geometryData!=null){MemoryUtil.memFree(geometryData);geometryData=null;}
        capture.reset(null);world=null;enabled=checked=false;blocked="off";
        liveMode=null;DisplayExtractionCache.clear();
        vertices=uploadedVertices=meshCount=buildsThisFrame=frame=maxVertices=keyQuads=0;
        meshBuilds=indexRebuilds=geometryUploads=geometryUploadedBytes=instanceUploadedBytes=0;
        instances=quads=draws=fallbacks=indexBytes=buildNanos=captureNanos=uploadNanos=drawNanos=0;
        framesTotal=captureTotal=uploadTotal=drawTotal=0;Arrays.fill(reasons,0);
        visibleVertices=interpolatingParts=0;
        visibleMeshCount=0;poseReused=poseSubmitted=poseReusedTotal=poseSubmittedTotal=0;
        entityLoopStarted=entityLoopNanos=entityLoopTotal=entityLoopFrames=0;
        pose=new PoseStack();
    }
    /** GPU pipeline objects are device-owned: retain bounded variants across mode/world changes. */
    public static void reload(){clear();pipelines.clear();}
    public static void beginFrame(Minecraft client){
        if(!requested()||client.level==null){if(world!=null)clear();return;}
        if(world!=client.level||all!=DrawLessConfig.get().extremeAllItemDisplays||liveMode!=DrawLessConfig.get().extremeDisplayMode){
            clear();world=client.level;all=DrawLessConfig.get().extremeAllItemDisplays;
            liveMode=DrawLessConfig.get().extremeDisplayMode;
        }
        extracted.clear();accepted.clear();animated.clear();activeBatches.clear();
        visibleVertices=interpolatingParts=0;
        visibleMeshCount=0;poseReused=poseSubmitted=0;
        for(var type:batches.values()){type.count=0;for(var batch:type.chunks)batch.instances.clear();}
        Arrays.fill(reasons,0);instances=quads=fallbacks=indexBytes=buildNanos=captureNanos=uploadNanos=0;
        buildsThisFrame=0;frame++;
        frameNow=System.nanoTime();
        enabled=false;
        if(!DrawLessConfig.get().displayModelBudget)return;
        if(!checked){
            checked=true;blocked=compatibility(client);
            if(blocked.isEmpty()){
                maxVertices=Math.min(MAX_VERTICES,GL31.glGetInteger(GL31.GL_MAX_TEXTURE_BUFFER_SIZE)/2);
                if(maxVertices<4096)blocked="texel-buffer-limit";
            }
        }
        enabled=blocked.isEmpty();
        // Entity-local records must not accumulate across hidden/unloaded actors.
        if((frame&63)==0)parts.entrySet().removeIf(e->e.getKey().isRemoved()||frame-e.getValue().seen>120);
    }
    private static String compatibility(Minecraft client){
        if(!RenderSystem.getDevice().getDeviceInfo().backendName().equals("OpenGL"))return "unsupported-backend";
        if(RenderModSupport.IRIS)return "iris-vanilla-fallback";
        for(String file:List.of("shaders/core/item.vsh","shaders/core/item.fsh")){
            var resource=client.getResourceManager().getResource(Identifier.withDefaultNamespace(file));
            if(resource.isEmpty()||!resource.get().source().equals(client.getVanillaPackResources()))return "custom-item-shader";
        }
        return "";
    }
    public static void recordState(Entity entity,EntityRenderState state){
        if(enabled&&entity instanceof Display.ItemDisplay item&&ExtremeDisplayPerformance.target(item))extracted.put(state,item);
    }
    /** One timing pair per frame, including vanilla visibility and state extraction, before capture. */
    public static void startEntityLoop(){if(enabled)entityLoopStarted=System.nanoTime();}
    public static void finishEntityLoop(){
        if(entityLoopStarted==0)return;
        entityLoopNanos=System.nanoTime()-entityLoopStarted;entityLoopStarted=0;
        entityLoopTotal+=entityLoopNanos;entityLoopFrames++;
        DisplayCostProbe.global("Vanilla entity extraction loop (capture excluded)",entityLoopNanos);
    }
    public static void finishFrame(Minecraft client,LevelRenderState state){
        if(!enabled)return;
        long start=System.nanoTime();
        boolean sample=DisplayCostProbe.frame();
        long eligibilityNanos=0,poseNanos=0,groupNanos=0;
        try(var ignored=RenderMetrics.getInstance().scope("render/liveDisplay/capture")){
            for(var renderState:state.entityRenderStates){
                var entity=extracted.get(renderState);
                if(entity==null||!(renderState instanceof ItemDisplayEntityRenderState item))continue;
                long stage=sample?System.nanoTime():0;
                var reason=eligible(entity,item);
                if(sample)eligibilityNanos+=System.nanoTime()-stage;
                if(reason==Reason.READY){
                    var part=parts.get(entity);
                    if(part==null&&parts.size()<MAX_PARTS){part=new Part();parts.put(entity,part);}
                    if(part==null)reason=Reason.CAPACITY;
                    else{
                        stage=sample?System.nanoTime():0;
                        part.seen=frame;
                        if(part.matches(item)){
                            reason=part.reason;poseReused++;poseReusedTotal++;
                        }else{
                            capture.reset(part);
                            // Capture around the entity origin. Camera/actor/passenger translation is
                            // applied below from current doubles; it must not invalidate a local pose.
                            var offset=item.passengerOffset;
                            client.getEntityRenderDispatcher().submit(item,state.cameraRenderState,
                                    offset==null?0:-offset.x,offset==null?0:-offset.y,
                                    offset==null?0:-offset.z,pose,capture);
                            reason=capture.reason;
                            if(reason==Reason.READY&&!capture.getSubmitsPerOrder().isEmpty())reason=Reason.EXTRA;
                            if(reason==Reason.READY&&part.used==0)reason=Reason.EMPTY;
                            part.remember(item,reason,client.getEntityRenderDispatcher().getRenderer(item).getClass()==DisplayRenderer.ItemDisplayRenderer.class);
                            poseSubmitted++;poseSubmittedTotal++;
                        }
                        if(sample)poseNanos+=System.nanoTime()-stage;
                        if(reason==Reason.READY){
                            stage=sample?System.nanoTime():0;
                            if(indexBytes+part.indexBytes>MAX_INDEX_BYTES||instances+part.used>8192)reason=Reason.CAPACITY;
                            else{
                                indexBytes+=part.indexBytes;instances+=part.used;
                                var offset=item.passengerOffset;var camera=state.cameraRenderState.pos;
                                float x=(float)(item.x-camera.x+(offset==null?0:offset.x));
                                float y=(float)(item.y-camera.y+(offset==null?0:offset.y));
                                float z=(float)(item.z-camera.z+(offset==null?0:offset.z));
                                for(int i=0;i<part.used;i++){
                                    var instance=part.instances.get(i);
                                    instance.position(x,y,z,item.lightCoords);add(instance);
                                }
                                accepted.add(renderState);
                                if(item.interpolationProgress<1)interpolatingParts++;
                            }
                            if(sample)groupNanos+=System.nanoTime()-stage;
                        }
                    }
                }
                reasons[reason.ordinal()]++;
                if(reason!=Reason.READY)fallbacks++;
                if(sample)DisplayCostProbe.admission(DisplayCostProbe.source(renderState),reason.label);
            }
        }catch(RuntimeException failure){fail(failure);}
        finally{
            captureNanos=System.nanoTime()-start;captureTotal+=captureNanos;
            DisplayCostProbe.global("Live geometry capture",captureNanos);
            if(sample){DisplayCostProbe.global("Live eligibility (capture component)",eligibilityNanos);
                DisplayCostProbe.global("Live local pose (capture component)",poseNanos);
                DisplayCostProbe.global("Live positioning/grouping (capture component)",groupNanos);}
            capture.reset(null);
        }
        if(!enabled){extracted.clear();return;}
        start=System.nanoTime();
        try(var ignored=RenderMetrics.getInstance().scope("render/liveDisplay/upload")){
            uploadGeometry();
            for(var batch:activeBatches)batch.prepare();
            // Commit only after every pipeline and buffer is ready. Failure keeps ALL vanilla states.
            if(!accepted.isEmpty()){
                state.entityRenderStates.removeIf(accepted::contains);
                if(DisplayExtractionCache.active())for(var acceptedState:accepted)
                    DisplayExtractionCache.accept(extracted.get(acceptedState),(ItemDisplayEntityRenderState)acceptedState);
            }
        }catch(RuntimeException failure){fail(failure);}
        finally{uploadNanos=System.nanoTime()-start;uploadTotal+=uploadNanos;framesTotal++;DisplayCostProbe.global("Live geometry upload",uploadNanos);extracted.clear();}
    }
    private static Reason eligible(Display.ItemDisplay entity,ItemDisplayEntityRenderState state){
        if(state.renderState==null||state.item.isEmpty())return Reason.EMPTY;
        if(state.appearsGlowing()||state.displayFireAnimation||state.nameTag!=null||state.scoreText!=null||!state.shadowPieces.isEmpty()
                ||state.leashStates!=null&&!state.leashStates.isEmpty())return Reason.EXTRA;
        if(state.outlineColor!=0)return Reason.FOIL;
        return DisplayModelBudget.hasSafeLayers(entity,state,frameNow)?Reason.READY:Reason.DYNAMIC;
    }
    private static void add(Instance instance){
        var mesh=instance.mesh;var type=mesh.batches;
        int chunk=type.count++/MAX_INSTANCES;
        if(chunk==type.chunks.size())type.chunks.add(new Batch(type.type));
        var batch=type.chunks.get(chunk);
        if(batch.instances.isEmpty())activeBatches.add(batch);
        batch.instances.add(instance);quads+=mesh.vertexCount/4;
        if(mesh.visibleFrame!=frame){
            mesh.visibleFrame=frame;visibleMeshCount++;visibleVertices+=mesh.vertexCount;
            for(var sprite:mesh.sprites)animated.add(sprite);
        }
    }
    private static Geometry geometry(List<BakedQuad> quads){
        var cached=aliases.get(quads);if(cached!=null)return cached;
        if(aliases.size()>=MAX_ALIASES||quads.size()>16384||keyQuads+(long)quads.size()>1_048_576)return Geometry.CAPACITY;
        var key=new QuadKey(quads);cached=geometries.get(key);
        if(cached!=null){aliases.put(quads,cached);return cached;}
        if(buildsThisFrame>=4||buildsThisFrame>0&&buildNanos>2_000_000)return Geometry.WARMING;
        long start=System.nanoTime();buildsThisFrame++;
        try(var ignored=RenderMetrics.getInstance().scope("render/liveDisplay/buildGeometry")){
            var grouped=new LinkedHashMap<Material,List<BakedQuad>>();
            for(var quad:quads){
                var info=quad.materialInfo();var type=info.itemRenderType();
                if(type.hasBlending()||type.sortOnUpload())return remember(key,quads,Geometry.TRANSPARENT);
                var pipeline=type.pipeline();
                if(!pipeline.getVertexShader().equals(ITEM_SHADER)||!pipeline.getFragmentShader().equals(ITEM_SHADER)
                        ||!pipeline.getShaderDefines().flags().isEmpty()
                        ||pipeline.getShaderDefines().values().keySet().stream().anyMatch(name->!name.equals("ALPHA_CUTOUT")))
                    return remember(key,quads,Geometry.UNSUPPORTED);
                if(!pipelines.containsKey(pipeline)&&pipelines.size()>=32)return remember(key,quads,Geometry.UNSUPPORTED);
                grouped.computeIfAbsent(new Material(type,info.isTinted()?info.tintIndex():-1,info.lightEmission()),k->new ArrayList<>()).add(quad);
            }
            if(meshCount+grouped.size()>MAX_MESHES||vertices+(long)quads.size()*4>maxVertices)return remember(key,quads,Geometry.CAPACITY);
            // Compile before accepting a single part; shader errors cannot make the vanilla model vanish.
            for(var material:grouped.keySet())pipeline(material.type.pipeline());
            ensureCpuCapacity((vertices+quads.size()*4)*VERTEX_BYTES);
            var meshes=new ArrayList<Mesh>(grouped.size());
            for(var entry:grouped.entrySet()){
                int offset=vertices;
                Set<TextureAtlasSprite> sprites=Collections.newSetFromMap(new IdentityHashMap<>());
                for(var quad:entry.getValue()){
                    var n=quad.direction().getUnitVec3f();
                    if(quad.materialInfo().sprite().isAnimated())sprites.add(quad.materialInfo().sprite());
                    for(int v=0;v<4;v++){
                        var p=quad.position(v);long uv=quad.packedUV(v);
                        geometryData.putFloat(p.x()).putFloat(p.y()).putFloat(p.z()).putFloat(UVPair.unpackU(uv));
                        geometryData.putFloat(n.x()).putFloat(n.y()).putFloat(n.z()).putFloat(UVPair.unpackV(uv));vertices++;
                    }
                }
                meshes.add(new Mesh(entry.getKey().type,offset,vertices-offset,entry.getKey().tint,entry.getKey().emission,List.copyOf(sprites)));
                meshCount++;meshBuilds++;
            }
            return remember(key,quads,new Geometry(Reason.READY,List.copyOf(meshes)));
        }finally{buildNanos+=System.nanoTime()-start;}
    }
    private static Geometry remember(QuadKey key,List<BakedQuad> quads,Geometry geometry){
        if(geometries.size()>=MAX_MESHES)return Geometry.CAPACITY;
        geometries.put(key,geometry);keyQuads+=key.quads.length;aliases.put(quads,geometry);return geometry;
    }
    private static RenderPipeline pipeline(RenderPipeline base){
        var cached=pipelines.get(base);if(cached!=null)return cached;
        var snippet=new RenderPipeline.Snippet(Optional.of(VERTEX_SHADER),Optional.of(base.getFragmentShader()),
                Optional.of(base.getShaderDefines()),Optional.of(base.getBindGroupLayouts()),base.getColorTargetStates(),
                base.getColorTargetStates().length,Optional.ofNullable(base.getDepthStencilState()),Optional.of(base.getPolygonMode()),
                Optional.of(base.isCull()),new VertexFormat[com.mojang.blaze3d.systems.RenderPass.MAX_VERTEX_BUFFERS],Optional.of(base.getPrimitiveTopology()));
        var result=RenderPipeline.builder(snippet).withLocation(Identifier.fromNamespaceAndPath("drawless","live/"+base.getLocation().getPath()))
                .withBindGroupLayout(MESH_LAYOUT).build();
        if(!RenderSystem.getDevice().precompilePipeline(result).isValid())throw new IllegalStateException("Live Item Display shader could not compile");
        pipelines.put(base,result);return result;
    }
    private static void ensureCpuCapacity(int size){
        if(geometryData==null)geometryData=MemoryUtil.memAlloc(Math.min(maxVertices*VERTEX_BYTES,Math.max(65536,size)));
        else if(size>geometryData.capacity())geometryData=MemoryUtil.memRealloc(geometryData,Math.min(maxVertices*VERTEX_BYTES,Math.max(size,geometryData.capacity()*2)));
        geometryData.position(vertices*VERTEX_BYTES);
    }
    private static void uploadGeometry(){
        if(vertices==uploadedVertices)return;
        int size=vertices*VERTEX_BYTES;
        var encoder=RenderSystem.getDevice().createCommandEncoder();
        if(geometryBuffer==null||geometryBuffer.size()<size){
            var next=RenderSystem.getDevice().createBuffer(()->"DrawLess shared local Item Display geometry",
                    GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER|GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_COPY_SRC,geometryData.capacity());
            if(geometryBuffer!=null){encoder.copyToBuffer(geometryBuffer.slice(0,(long)uploadedVertices*VERTEX_BYTES),next.slice(0,(long)uploadedVertices*VERTEX_BYTES));geometryBuffer.close();}
            geometryBuffer=next;
        }
        int offset=uploadedVertices*VERTEX_BYTES;
        encoder.writeToBuffer(geometryBuffer.slice(offset,size-offset),geometryData.slice(offset,size-offset));
        geometryUploadedBytes+=size-offset;geometryUploads++;uploadedVertices=vertices;
    }
    private static void draw(LevelRenderContext context){
        if(!enabled||!requested()||world!=Minecraft.getInstance().level)return;
        long start=System.nanoTime();draws=0;
        var matrix=RenderSystem.getModelViewStack();matrix.pushMatrix();
        try(var ignored=RenderMetrics.getInstance().scope("render/liveDisplay/draw")){
            matrix.set(context.levelState().cameraRenderState.viewRotationMatrix);
            if(SODIUM)for(var sprite:animated)SodiumSprites.mark(sprite);
            for(var batch:activeBatches)batch.draw();
        }catch(RuntimeException failure){fail(failure);}
        finally{matrix.popMatrix();drawNanos=System.nanoTime()-start;drawTotal+=drawNanos;DisplayCostProbe.global("Live geometry draw CPU",drawNanos);}
        var profiler=RenderMetrics.getInstance();if(profiler.isEnabled()){
            profiler.incrementCounter("liveDisplay/instances",instances);profiler.incrementCounter("liveDisplay/draws",draws);
            profiler.incrementCounter("liveDisplay/vanillaFallback",fallbacks);
            profiler.incrementCounter("liveDisplay/poseReused",poseReused);profiler.incrementCounter("liveDisplay/poseSubmitted",poseSubmitted);
        }
    }
    private static void fail(RuntimeException error){
        if(!blocked.equals("runtime-fallback"))DrawLess.LOGGER.warn("Live Item Display geometry disabled; using vanilla rendering",error);
        enabled=false;blocked="runtime-fallback";activeBatches.clear();
    }
    public static String diagnostic(){
        if(!requested())return "off";
        var reasonsText=new StringJoiner(",");for(var r:Reason.values())if(reasons[r.ordinal()]>0)reasonsText.add(r.label+":"+reasons[r.ordinal()]);
        return String.format(Locale.ROOT,"%s; status=%s; meshes=%d; quadSets=%d; aliases=%d; parts=%d; interpolatingParts=%d; visibleMeshes=%d; visibleVertices=%d; instances=%d; quads=%d; draws=%d; fallback=%d; localBytes=%d; indexBytes=%d; meshBuilds=%d; indexRebuilds=%d; geometryUploads=%d; geometryUploadedBytes=%d; instanceUploadedBytes=%d; animatedSprites=%d; pausedTicksTotal=0; poseReused=%d; poseSubmitted=%d; poseReusedTotal=%d; poseSubmittedTotal=%d; capture=%.3fms; upload=%.3fms; drawCpu=%.3fms; framesTotal=%d; captureTotalMs=%.3f; uploadTotalMs=%.3f; drawTotalMs=%.3f; admission=%s",
                liveMode==null?DrawLessConfig.get().extremeDisplayMode:liveMode,enabled?"on":!DrawLessConfig.get().displayModelBudget?"layer-cache-off":blocked,meshCount,geometries.size(),aliases.size(),accepted.size(),interpolatingParts,visibleMeshCount,visibleVertices,instances,quads,draws,fallbacks,(long)vertices*VERTEX_BYTES,indexBytes,
                meshBuilds,indexRebuilds,geometryUploads,geometryUploadedBytes,instanceUploadedBytes,animated.size(),poseReused,poseSubmitted,poseReusedTotal,poseSubmittedTotal,captureNanos/1e6,uploadNanos/1e6,drawNanos/1e6,framesTotal,captureTotal/1e6,uploadTotal/1e6,drawTotal/1e6,reasonsText)
                +String.format(Locale.ROOT,"; entityLoop=%.3fms; entityLoopTotalMs=%.3f; entityLoopFramesTotal=%d",entityLoopNanos/1e6,entityLoopTotal/1e6,entityLoopFrames)
                +"; "+DisplayExtractionCache.diagnostic();
    }
    private enum Reason{
        READY("live-cached"),EMPTY("live-empty"),DYNAMIC("live-unsafe-model"),EXTRA("live-extra-visual"),
        FOIL("live-foil-outline"),TRANSPARENT("live-transparency"),UNSUPPORTED("live-pipeline"),WARMING("live-warming"),CAPACITY("live-capacity");
        final String label;Reason(String label){this.label=label;}
    }
    private record Material(RenderType type,int tint,int emission){}
    private static final class Mesh{
        final RenderType type;final int firstVertex,vertexCount,tint,emission;final List<TextureAtlasSprite> sprites;
        final TypeBatches batches;int visibleFrame=-1;
        Mesh(RenderType type,int firstVertex,int vertexCount,int tint,int emission,List<TextureAtlasSprite> sprites){
            this.type=type;this.firstVertex=firstVertex;this.vertexCount=vertexCount;this.tint=tint;this.emission=emission;this.sprites=sprites;
            batches=LiveDisplayGeometry.batches.computeIfAbsent(type,TypeBatches::new);
        }
    }
    private record Geometry(Reason reason,List<Mesh> meshes){
        static final Geometry TRANSPARENT=new Geometry(Reason.TRANSPARENT,List.of()),UNSUPPORTED=new Geometry(Reason.UNSUPPORTED,List.of()),
                WARMING=new Geometry(Reason.WARMING,List.of()),CAPACITY=new Geometry(Reason.CAPACITY,List.of());
    }
    /** Full sequence equality is only needed on a new list identity, never on a warm frame. */
    private static final class QuadKey{
        final BakedQuad[] quads;final int hash;
        QuadKey(List<BakedQuad> source){quads=source.toArray(BakedQuad[]::new);int h=1;for(var q:quads)h=31*h+System.identityHashCode(q);hash=h;}
        @Override public int hashCode(){return hash;}
        @Override public boolean equals(Object other){
            if(!(other instanceof QuadKey key)||hash!=key.hash||quads.length!=key.quads.length)return false;
            for(int i=0;i<quads.length;i++)if(quads[i]!=key.quads[i])return false;return true;
        }
    }
    private static final class Part{
        final ArrayList<Instance> instances=new ArrayList<>();int used,seen;long indexBytes;
        ItemStackRenderState layers;Display.RenderState render;
        float interpolation,yRot,xRot,cameraXRot,cameraYRot;
        Reason reason;boolean replayable;
        boolean matches(ItemDisplayEntityRenderState state){
            if(!replayable||layers!=state.item)return false;
            // These rejections depend only on immutable item layers, not on the display pose.
            if(reason==Reason.TRANSPARENT||reason==Reason.UNSUPPORTED||reason==Reason.FOIL||reason==Reason.EMPTY)return true;
            if(reason!=Reason.READY||render!=state.renderState||interpolation!=state.interpolationProgress
                    ||yRot!=state.entityYRot||xRot!=state.entityXRot)return false;
            return switch(render.billboardConstraints()){
                case FIXED -> true;
                case HORIZONTAL -> cameraXRot==state.cameraXRot;
                case VERTICAL -> cameraYRot==state.cameraYRot;
                case CENTER -> cameraXRot==state.cameraXRot&&cameraYRot==state.cameraYRot;
            };
        }
        void remember(ItemDisplayEntityRenderState state,Reason reason,boolean replayable){
            layers=state.item;render=state.renderState;interpolation=state.interpolationProgress;
            yRot=state.entityYRot;xRot=state.entityXRot;cameraXRot=state.cameraXRot;cameraYRot=state.cameraYRot;
            this.reason=reason;this.replayable=replayable;indexBytes=0;
            if(reason==Reason.READY)for(int i=0;i<used;i++)indexBytes+=(long)instances.get(i).mesh.vertexCount/4*6*4;
        }
    }
    private static final class Instance{
        Mesh mesh;final Matrix4f transform=new Matrix4f();final float[] normals=new float[9];int color,light,overlay;
        float localX,localY,localZ;
        void set(Mesh mesh,PoseStack.Pose pose,int color,int light,int overlay){
            this.mesh=mesh;transform.set(pose.pose());this.color=color;this.light=LightCoordsUtil.lightCoordsWithEmission(light,mesh.emission);this.overlay=overlay;
            localX=transform.m30();localY=transform.m31();localZ=transform.m32();
            // BakedQuad normals are axis aligned. Transform/normalize and quantize three bases exactly
            // as vanilla's packed signed-byte normal writer, once per instance rather than per quad.
            for(int i=0;i<3;i++){
                pose.transformNormal(i==0?1:0,i==1?1:0,i==2?1:0,normal);
                normals[i*3]=(byte)(int)(normal.x*127)/127f;normals[i*3+1]=(byte)(int)(normal.y*127)/127f;normals[i*3+2]=(byte)(int)(normal.z*127)/127f;
            }
        }
        void position(float x,float y,float z,int light){
            // Always derive from the original local translation; never accumulate camera deltas.
            transform.m30(localX+x).m31(localY+y).m32(localZ+z);
            this.light=LightCoordsUtil.lightCoordsWithEmission(light,mesh.emission);
        }
        void write(ByteBuffer data){
            int offset=data.position();transform.get(offset,data);data.position(offset+64);
            for(int i=0;i<3;i++)data.putFloat(normals[i*3]).putFloat(normals[i*3+1]).putFloat(normals[i*3+2]).putFloat(0);
            data.putInt(color).putInt(light).putInt(overlay).putInt(0);
        }
    }
    private static final class Capture extends SubmitNodeStorage{
        Part part;Reason reason=Reason.READY;
        void reset(Part part){this.part=part;reason=Reason.READY;getSubmitsPerOrder().clear();if(part!=null)part.used=0;}
        @Override public void submitItem(PoseStack pose,ItemDisplayContext context,int light,int overlay,int outline,int[] tints,List<BakedQuad> quads,ItemStackRenderState.FoilType foil){
            if(reason!=Reason.READY)return;
            if(foil!=ItemStackRenderState.FoilType.NONE||outline!=0){reason=Reason.FOIL;return;}
            if(!pose.last().pose().isFinite()||!pose.last().normal().isFinite()){reason=Reason.EXTRA;return;}
            var geometry=geometry(quads);if(geometry.reason!=Reason.READY){reason=geometry.reason;return;}
            if(part.used+geometry.meshes.size()>128){reason=Reason.CAPACITY;return;}
            for(var mesh:geometry.meshes){
                if(part.used==part.instances.size())part.instances.add(new Instance());
                int color=mesh.tint>=0&&mesh.tint<tints.length?tints[mesh.tint]:-1;
                part.instances.get(part.used++).set(mesh,pose.last(),color,light,overlay);
            }
        }
    }
    private static final class TypeBatches{final RenderType type;final ArrayList<Batch> chunks=new ArrayList<>();int count;TypeBatches(RenderType type){this.type=type;}}
    private static final class Batch implements AutoCloseable{
        final RenderType type;final ArrayList<Instance> instances=new ArrayList<>();
        final ArrayList<Instance> previous=new ArrayList<>();final ArrayList<Mesh> previousMeshes=new ArrayList<>();
        GpuBuffer indices;MappableRingBuffer live;int indexCount;
        Batch(RenderType type){this.type=type;}
        void prepare(){
            boolean same=previous.size()==instances.size();
            if(same)for(int i=0;i<instances.size();i++)if(previous.get(i)!=instances.get(i)||previousMeshes.get(i)!=instances.get(i).mesh){same=false;break;}
            if(!same){
                int count=0;for(var i:instances)count+=i.mesh.vertexCount/4*6;
                var data=MemoryUtil.memAlloc(count*4);
                try{
                    for(int i=0;i<instances.size();i++){
                        var mesh=instances.get(i).mesh;
                        for(int v=0;v<mesh.vertexCount;v+=4){
                            int base=mesh.firstVertex+v;
                            data.putInt(index(base,i)).putInt(index(base+1,i)).putInt(index(base+2,i));
                            data.putInt(index(base+2,i)).putInt(index(base+3,i)).putInt(index(base,i));
                        }
                    }
                    data.flip();var next=RenderSystem.getDevice().createBuffer(()->"DrawLess live Item Display references",GpuBuffer.USAGE_INDEX,data);
                    if(indices!=null)indices.close();indices=next;indexCount=count;indexRebuilds++;
                    previous.clear();previousMeshes.clear();for(var i:instances){previous.add(i);previousMeshes.add(i.mesh);}
                }finally{MemoryUtil.memFree(data);}
            }
            int size=instances.size()*INSTANCE_BYTES;
            if(live==null||live.size()<size){if(live!=null)live.close();live=new MappableRingBuffer(()->"DrawLess live Item Display transforms",GpuBuffer.USAGE_MAP_WRITE|GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER,Math.max(size,live==null?1024:live.size()*2));}
            try(var mapping=live.currentBuffer().slice(0,size).map(false,true)){
                var data=mapping.data();for(var instance:instances)instance.write(data);
            }
            instanceUploadedBytes+=size;
        }
        void draw(){
            PreparedRenderType prepared=type.prepare();var target=prepared.outputTarget().getRenderTarget();
            var color=RenderSystem.outputColorTextureOverride!=null?RenderSystem.outputColorTextureOverride:target.getColorTextureView();
            var depth=target.useDepth?(RenderSystem.outputDepthTextureOverride!=null?RenderSystem.outputDepthTextureOverride:target.getDepthTextureView()):null;
            try(var pass=RenderSystem.getDevice().createCommandEncoder().createRenderPass(()->"DrawLess live Item Displays",color,Optional.empty(),depth,OptionalDouble.empty())){
                pass.setPipeline(pipelines.get(type.pipeline()));RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms",prepared.dynamicTransforms());pass.setUniform("FHGeometry",geometryBuffer);pass.setUniform("FHInstances",live.currentBuffer());
                var scissor=prepared.scissorState();if(scissor.enabled())pass.enableScissor(scissor.x(),scissor.y(),scissor.width(),scissor.height());
                for(var texture:prepared.textures())pass.bindTexture(texture.name(),texture.textureView(),texture.sampler());
                pass.setIndexBuffer(indices,IndexType.INT);pass.drawIndexed(indexCount,1,0,0,0);draws++;
            }finally{live.rotate();}
        }
        @Override public void close(){if(indices!=null)indices.close();if(live!=null)live.close();}
    }
    private static final class SodiumSprites{static void mark(TextureAtlasSprite sprite){net.caffeinemc.mods.sodium.api.texture.SpriteUtil.INSTANCE.markSpriteActive(sprite);}}
}
