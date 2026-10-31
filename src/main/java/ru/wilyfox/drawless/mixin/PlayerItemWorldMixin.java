package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import ru.wilyfox.drawless.performance.PlayerItemGeometry;

@Mixin(LevelRenderer.class)
public class PlayerItemWorldMixin {
  @WrapMethod(
      method =
          "submitEntities(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V")
  private void drawless$itemScope(
      PoseStack pose,
      LevelRenderState state,
      SubmitNodeCollector collector,
      Operation<Void> original) {
    PlayerItemGeometry.beginLevelSubmit(Minecraft.getInstance());
    try {
      original.call(pose, state, collector);
    } finally {
      PlayerItemGeometry.endLevelSubmit();
    }
  }
}
