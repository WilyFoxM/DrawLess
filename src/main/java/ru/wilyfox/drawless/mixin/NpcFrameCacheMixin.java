package ru.wilyfox.drawless.mixin;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.wilyfox.drawless.performance.NpcFrameCache;

@Mixin(EntityRenderDispatcher.class)
public class NpcFrameCacheMixin {
    @Redirect(method="extractEntity",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/entity/EntityRenderer;createRenderState(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"))
    private <E extends Entity,S extends EntityRenderState> EntityRenderState drawless$npcPose(EntityRenderer<E,S> renderer,E entity,float partialTick) {
        // Typed redirect avoids callback objects and varargs allocations for every entity, including when OFF.
        boolean item=renderer instanceof DisplayRenderer.ItemDisplayRenderer;
        var cost=item&&ru.wilyfox.drawless.diagnostics.DisplayCostProbe.frame()?ru.wilyfox.drawless.diagnostics.DisplayCostProbe.cost(entity):null;
        long start=cost==null?0:System.nanoTime();
        var profiler=ru.wilyfox.drawless.diagnostics.RenderMetrics.getInstance();
        var scope=item&&profiler.isEnabled()?profiler.scope("render/displayExtraction"):null;
        EntityRenderState result=null;
        try {
        if(item){
            var frozen=ru.wilyfox.drawless.performance.ExtremeDisplayPerformance.reuse(entity);if(frozen!=null){result=frozen;return result;}
            var cached=NpcFrameCache.reuse(entity);if(cached!=null){result=cached;return result;}
        }
        var state=renderer.createRenderState(entity,partialTick);
        if(item)NpcFrameCache.capture(entity,state);
        result=state;return result;
        }finally{
            if(item&&result!=null)ru.wilyfox.drawless.performance.ExtremeDisplayPerformance.recordState(entity,result);
            if(item&&result!=null)ru.wilyfox.drawless.performance.LiveDisplayGeometry.recordState(entity,result);
            if(cost!=null&&result!=null){
                long nanos=System.nanoTime()-start;
                ru.wilyfox.drawless.diagnostics.DisplayCostProbe.extracted(entity,result,cost,nanos);
                ru.wilyfox.drawless.diagnostics.DisplayCostProbe.global("Item Display extraction (capture excluded)",nanos);
            }
            if(scope!=null)scope.close();
        }
    }
}
