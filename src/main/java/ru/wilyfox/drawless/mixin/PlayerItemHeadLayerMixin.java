package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import ru.wilyfox.drawless.performance.PlayerItemGeometry;

/** The head item layer; worn skulls submit a model, not an item, and stay ordinary. */
@Mixin(CustomHeadLayer.class)
public class PlayerItemHeadLayerMixin {
  @WrapMethod(
      method =
          "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;FF)V")
  private void drawless$items(
      PoseStack pose,
      SubmitNodeCollector collector,
      int light,
      LivingEntityRenderState state,
      float yRot,
      float xRot,
      Operation<Void> original) {
    boolean entered = PlayerItemGeometry.enterLayer(state);
    try {
      original.call(pose, collector, light, state, yRot, xRot);
    } finally {
      if (entered) PlayerItemGeometry.exitLayer();
    }
  }
}
