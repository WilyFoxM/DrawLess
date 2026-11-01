package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import ru.wilyfox.drawless.performance.PlayerArmorGeometry;

@Mixin(LivingEntityRenderer.class)
public class PlayerBodySubmitMixin {
  /** The body submit; layers (armor, items, cape) submit separately afterwards. */
  @WrapOperation(
      method =
          "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;ILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V"))
  private void drawless$body(
      SubmitNodeCollector collector,
      Model<?> model,
      Object state,
      PoseStack pose,
      RenderType type,
      int light,
      int overlay,
      int color,
      TextureAtlasSprite sprite,
      int outline,
      ModelFeatureRenderer.CrumblingOverlay crumbling,
      Operation<Void> original) {
    if (!PlayerArmorGeometry.body(
        collector, model, state, pose, type, light, overlay, color, sprite, outline, crumbling))
      original.call(
          collector, model, state, pose, type, light, overlay, color, sprite, outline, crumbling);
  }
}
