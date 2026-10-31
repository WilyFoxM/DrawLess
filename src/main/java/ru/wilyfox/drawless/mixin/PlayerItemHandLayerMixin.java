package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import ru.wilyfox.drawless.performance.PlayerItemGeometry;

/** Both hands, including a player's item held to the eye. */
@Mixin(ItemInHandLayer.class)
public class PlayerItemHandLayerMixin {
  @WrapMethod(
      method =
          "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/ArmedEntityRenderState;FF)V")
  private void drawless$items(
      PoseStack pose,
      SubmitNodeCollector collector,
      int light,
      ArmedEntityRenderState state,
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
