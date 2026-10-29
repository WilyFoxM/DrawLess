package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import ru.wilyfox.drawless.performance.PlayerArmorGeometry;

@Mixin(LevelRenderer.class)
public class PlayerArmorWorldMixin {
  @WrapMethod(
      method =
          "submitEntities(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V")
  private void drawless$armorScope(
      PoseStack pose,
      LevelRenderState state,
      SubmitNodeCollector collector,
      Operation<Void> original) {
    PlayerArmorGeometry.beginLevelSubmit(Minecraft.getInstance());
    try {
      original.call(pose, state, collector);
    } finally {
      PlayerArmorGeometry.endLevelSubmit();
    }
  }
}
