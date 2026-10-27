package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import ru.wilyfox.drawless.diagnostics.PlayerProfile;

@Mixin(targets = "net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer$Group")
public class PlayerProfileGroupMixin {
  @WrapMethod(method = "getVertexBuilder")
  private VertexConsumer drawless$use(RenderType type, Operation<VertexConsumer> original) {
    PlayerProfile.groupUse(this);
    return original.call(type);
  }

  @WrapMethod(method = "getOrAddDraw")
  private StagedVertexBuffer.Draw drawless$match(
      RenderType type, Operation<StagedVertexBuffer.Draw> original) {
    long token = PlayerProfile.attributed() ? PlayerProfile.begin("material_match") : 0;
    try {
      return original.call(type);
    } finally {
      PlayerProfile.end(token);
    }
  }
}
