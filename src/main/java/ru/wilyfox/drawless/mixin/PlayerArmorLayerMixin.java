package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.EquipmentAsset;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import ru.wilyfox.drawless.performance.PlayerArmorGeometry;

@Mixin(EquipmentLayerRenderer.class)
public class PlayerArmorLayerMixin {
  private static final String RENDER_LAYERS =
      "renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;II)V";

  /** One equipment piece: base, glint and trim submits share a single retained/vanilla path. */
  @WrapMethod(method = RENDER_LAYERS)
  private void drawless$piece(
      EquipmentClientInfo.LayerType layer,
      ResourceKey<EquipmentAsset> asset,
      Model<?> model,
      Object state,
      ItemStack item,
      PoseStack pose,
      SubmitNodeCollector collector,
      int light,
      Identifier texture,
      int outline,
      int order,
      Operation<Void> original) {
    PlayerArmorGeometry.beginPiece();
    boolean complete = false;
    try {
      original.call(
          layer, asset, model, state, item, pose, collector, light, texture, outline, order);
      complete = true;
    } finally {
      if (complete) PlayerArmorGeometry.endPiece();
      else PlayerArmorGeometry.abortPiece();
    }
  }

  @WrapOperation(
      method = RENDER_LAYERS,
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/rendertype/RenderTypes;armorCutoutNoCull(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/rendertype/RenderType;"))
  private RenderType drawless$armorTexture(Identifier texture, Operation<RenderType> original) {
    var type = original.call(texture);
    PlayerArmorGeometry.armorTexture(type, texture);
    return type;
  }

  @WrapOperation(
      method = RENDER_LAYERS,
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/OrderedSubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;ILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V"))
  private void drawless$submit(
      OrderedSubmitNodeCollector collector,
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
    PlayerArmorGeometry.submit(
        collector, model, state, pose, type, light, overlay, color, sprite, outline, crumbling,
        original);
  }
}
