package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import ru.wilyfox.drawless.diagnostics.PlayerProfile;

@Mixin(LivingEntityRenderer.class)
public class PlayerProfileLivingMixin {
  @WrapMethod(
      method =
          "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V")
  private void drawless$submit(
      LivingEntityRenderState state,
      PoseStack pose,
      SubmitNodeCollector collector,
      CameraRenderState camera,
      Operation<Void> original) {
    var tag = PlayerProfile.state(state);
    if (tag == null) {
      original.call(state, pose, collector, camera);
      return;
    }
    var previous = PlayerProfile.bind(tag);
    long token = PlayerProfile.begin("player_submit");
    PlayerProfile.submitted(tag);
    try {
      original.call(state, pose, collector, camera);
    } finally {
      PlayerProfile.end(token);
      PlayerProfile.bind(previous);
    }
  }

  @SuppressWarnings("unchecked")
  @WrapOperation(
      method =
          "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/entity/layers/RenderLayer;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/EntityRenderState;FF)V"))
  private void drawless$layer(
      RenderLayer layer,
      PoseStack pose,
      SubmitNodeCollector collector,
      int light,
      EntityRenderState state,
      float yaw,
      float pitch,
      Operation<Void> original) {
    var tag = PlayerProfile.layer(layer);
    if (tag == null) {
      original.call(layer, pose, collector, light, state, yaw, pitch);
      return;
    }
    var previous = PlayerProfile.bind(tag);
    long token = PlayerProfile.begin("layer_submit/" + tag.layer());
    PlayerProfile.count("layer_calls/" + tag.layer(), 1);
    try {
      original.call(layer, pose, collector, light, state, yaw, pitch);
    } finally {
      PlayerProfile.end(token);
      PlayerProfile.bind(previous);
    }
  }
}
