package ru.wilyfox.drawless.mixin;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.wilyfox.drawless.diagnostics.DisplayCostProbe;

@Mixin(EntityRenderDispatcher.class)
public class DisplayCostSubmitMixin {
    @Redirect(method="submit",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/entity/EntityRenderer;submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V"))
    private <S extends EntityRenderState> void drawless$submitCost(EntityRenderer<?,S> renderer,S state,PoseStack pose,SubmitNodeCollector collector,CameraRenderState camera){
        var cost=DisplayCostProbe.source(state);
        if(cost==null){renderer.submit(state,pose,collector,camera);return;}
        var previous=DisplayCostProbe.bind(cost);long start=System.nanoTime();
        try{renderer.submit(state,pose,collector,camera);}
        finally{cost.submitNanos+=System.nanoTime()-start;DisplayCostProbe.bind(previous);}
    }
}
