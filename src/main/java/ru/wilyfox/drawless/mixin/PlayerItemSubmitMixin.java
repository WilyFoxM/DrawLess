package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import ru.wilyfox.drawless.performance.PlayerItemGeometry;

@Mixin(targets = "net.minecraft.client.renderer.item.ItemStackRenderState$LayerRenderState")
public class PlayerItemSubmitMixin {
  private static final String SUBMIT =
      "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V";

  @WrapOperation(
      method = SUBMIT,
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitItem(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemDisplayContext;III[ILjava/util/List;Lnet/minecraft/client/renderer/item/ItemStackRenderState$FoilType;)V"))
  private void drawless$item(
      SubmitNodeCollector collector,
      PoseStack pose,
      ItemDisplayContext context,
      int light,
      int overlay,
      int outline,
      int[] tints,
      List<BakedQuad> quads,
      ItemStackRenderState.FoilType foil,
      Operation<Void> original) {
    PlayerItemGeometry.submitItem(
        collector, pose, context, light, overlay, outline, tints, quads, foil, original);
  }

  @WrapOperation(
      method = SUBMIT,
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/special/SpecialModelRenderer;submit(Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IIZI)V"))
  private void drawless$special(
      SpecialModelRenderer<Object> renderer,
      Object argument,
      PoseStack pose,
      SubmitNodeCollector collector,
      int light,
      int overlay,
      boolean foil,
      int outline,
      Operation<Void> original) {
    PlayerItemGeometry.special();
    original.call(renderer, argument, pose, collector, light, overlay, foil, outline);
  }
}
