package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import ru.wilyfox.drawless.performance.PlayerItemGeometry;

/** One item stack: all of its layers share a single retained/vanilla decision. */
@Mixin(ItemStackRenderState.class)
public class PlayerItemStackMixin {
  @WrapMethod(
      method =
          "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V")
  private void drawless$piece(
      PoseStack pose,
      SubmitNodeCollector collector,
      int light,
      int overlay,
      int outline,
      Operation<Void> original) {
    if (!PlayerItemGeometry.beginPiece()) {
      original.call(pose, collector, light, overlay, outline);
      return;
    }
    boolean complete = false;
    try {
      original.call(pose, collector, light, overlay, outline);
      complete = true;
    } finally {
      if (complete) PlayerItemGeometry.endPiece();
      else PlayerItemGeometry.abortPiece();
    }
  }
}
