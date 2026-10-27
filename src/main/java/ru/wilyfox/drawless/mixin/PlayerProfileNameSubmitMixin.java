package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import ru.wilyfox.drawless.diagnostics.PlayerProfile;

@Mixin(EntityRenderer.class)
public class PlayerProfileNameSubmitMixin {
  @WrapMethod(
      method =
          "submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;I)V")
  private void drawless$name(
      EntityRenderState state,
      PoseStack pose,
      SubmitNodeCollector collector,
      CameraRenderState camera,
      int color,
      Operation<Void> original) {
    var tag = PlayerProfile.state(state);
    if (tag == null) {
      original.call(state, pose, collector, camera, color);
      return;
    }
    var previous = PlayerProfile.bind(new PlayerProfile.Tag(tag.actor(), "name_tag"));
    long token = PlayerProfile.begin("player_name_tag_submit");
    try {
      original.call(state, pose, collector, camera, color);
    } finally {
      PlayerProfile.end(token);
      PlayerProfile.bind(previous);
    }
  }
}
