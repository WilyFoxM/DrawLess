package ru.wilyfox.drawless.mixin;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(ItemFeatureRenderer.class)
public interface DisplayCostItemAccessor {
    @Invoker("prepareMainSubmit") void drawless$main(ItemFeatureRenderer.Submit submit);
    @Invoker("prepareOutlineSubmit") void drawless$outline(ItemFeatureRenderer.Submit submit);
    @Invoker("prepareFoilSubmit") void drawless$foil(ItemFeatureRenderer.Submit submit);
}
