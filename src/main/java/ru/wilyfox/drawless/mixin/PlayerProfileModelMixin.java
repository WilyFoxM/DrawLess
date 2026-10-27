package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import ru.wilyfox.drawless.diagnostics.PlayerProfile;

@Mixin(ModelFeatureRenderer.class)
public class PlayerProfileModelMixin {
  @WrapMethod(method = "prepareModel")
  private void drawless$prepare(ModelFeatureRenderer.Submit<?> submit, Operation<Void> original) {
    var tag = PlayerProfile.submit(submit);
    if (tag == null) {
      original.call(submit);
      return;
    }
    var previous = PlayerProfile.bind(tag);
    long token =
        PlayerProfile.begin(
            "player_model/" + tag.layer() + "/" + PlayerProfile.material(submit.renderType()));
    PlayerProfile.model(submit);
    try {
      original.call(submit);
    } finally {
      PlayerProfile.end(token);
      PlayerProfile.bind(previous);
    }
  }

  @SuppressWarnings("rawtypes")
  @WrapOperation(
      method = "prepareModel",
      at =
          @At(
              value = "INVOKE",
              target = "Lnet/minecraft/client/model/Model;setupAnim(Ljava/lang/Object;)V"))
  private void drawless$pose(Model model, Object state, Operation<Void> original) {
    long token = PlayerProfile.attributed() ? PlayerProfile.begin("setup_animation") : 0;
    if (token != 0) PlayerProfile.count("setup_animation_calls", 1);
    try {
      original.call(model, state);
    } finally {
      PlayerProfile.end(token);
    }
  }

  @SuppressWarnings("rawtypes")
  @WrapOperation(
      method = "prepareModel",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/model/Model;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"))
  private void drawless$vertices(
      Model model,
      PoseStack pose,
      VertexConsumer consumer,
      int light,
      int overlay,
      int tint,
      Operation<Void> original) {
    long token = PlayerProfile.attributed() ? PlayerProfile.begin("geometry_encode") : 0;
    try {
      original.call(model, pose, consumer, light, overlay, tint);
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapOperation(
      method = "prepareModel",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer;getVertexBuilder(Lnet/minecraft/client/renderer/rendertype/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
  private VertexConsumer drawless$material(
      ModelFeatureRenderer renderer, RenderType type, Operation<VertexConsumer> original) {
    long token = PlayerProfile.attributed() ? PlayerProfile.begin("material_buffer") : 0;
    try {
      return original.call(renderer, type);
    } finally {
      PlayerProfile.end(token);
    }
  }
}
