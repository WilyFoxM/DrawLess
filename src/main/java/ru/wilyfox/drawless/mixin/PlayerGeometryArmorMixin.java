package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import ru.wilyfox.drawless.performance.PlayerGeometryReuse;
import ru.wilyfox.drawless.performance.PlayerRenderOrigin;

@Mixin(HumanoidArmorLayer.class)
public class PlayerGeometryArmorMixin {
  @WrapMethod(
      method =
          "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V")
  private void drawless$armor(
      PoseStack pose,
      SubmitNodeCollector collector,
      int light,
      HumanoidRenderState state,
      float yaw,
      float pitch,
      Operation<Void> original) {
    int previous =
        PlayerGeometryReuse.armor(
            state instanceof PlayerRenderOrigin origin && origin.drawless$isPlayer());
    try {
      original.call(pose, collector, light, state, yaw, pitch);
    } finally {
      PlayerGeometryReuse.restoreArmor(previous);
    }
  }
}
