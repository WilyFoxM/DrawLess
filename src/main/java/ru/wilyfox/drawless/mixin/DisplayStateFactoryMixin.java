package ru.wilyfox.drawless.mixin;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.wilyfox.drawless.performance.DisplayModelBudget;

@Mixin(EntityRenderer.class)
public class DisplayStateFactoryMixin {
    @Redirect(method="createRenderState(Lnet/minecraft/world/entity/Entity;F)Lnet/minecraft/client/renderer/entity/state/EntityRenderState;",
            at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/entity/EntityRenderer;createRenderState()Lnet/minecraft/client/renderer/entity/state/EntityRenderState;"))
    private EntityRenderState drawless$createState(EntityRenderer<?,?> renderer,Entity source,float partialTick) {
        if(source instanceof Display.ItemDisplay item){
            var recycled=ru.wilyfox.drawless.performance.DisplayExtractionCache.borrow(renderer,item);
            if(recycled!=null)return recycled;
        }
        if(renderer.getClass()!=DisplayRenderer.ItemDisplayRenderer.class || !(source instanceof Display.ItemDisplay display)
                || !DisplayModelBudget.seedConstructorLayers(display)) return renderer.createRenderState();
        try {return renderer.createRenderState();}
        finally {DisplayModelBudget.clearConstructorLayers();}
    }
}
