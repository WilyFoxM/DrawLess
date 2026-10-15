package ru.wilyfox.drawless.mixin;

import net.minecraft.client.renderer.entity.state.ItemDisplayEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.wilyfox.drawless.bridge.DisplayLayerOwnership;
import ru.wilyfox.drawless.performance.DisplayModelBudget;

@Mixin(ItemDisplayEntityRenderState.class)
public class DisplayItemStateAllocationMixin implements DisplayLayerOwnership {
    @Unique private boolean drawless$borrowedLayers;
    @Unique private long drawless$safeLayerFrame;
    @Redirect(method="<init>",at=@At(value="NEW",target="net/minecraft/client/renderer/item/ItemStackRenderState"))
    private ItemStackRenderState drawless$initialLayers() {
        var cached=DisplayModelBudget.takeConstructorLayers();
        drawless$borrowedLayers=cached!=null;
        return cached!=null?cached:new ItemStackRenderState();
    }
    @Override public boolean drawless$borrowedLayers(){return drawless$borrowedLayers;}
    @Override public void drawless$ownLayers(){drawless$borrowedLayers=false;drawless$safeLayerFrame=0;}
    @Override public void drawless$borrowLayers(){drawless$borrowedLayers=true;drawless$safeLayerFrame=0;}
    @Override public long drawless$safeLayerFrame(){return drawless$safeLayerFrame;}
    @Override public void drawless$safeLayerFrame(long frame){drawless$safeLayerFrame=frame;}
}
