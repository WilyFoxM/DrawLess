package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import ru.wilyfox.drawless.diagnostics.PlayerProfile;

@Mixin(ModelPart.class)
public class PlayerProfilePartMixin {
  @WrapMethod(method = "translateAndRotate")
  private void drawless$transform(PoseStack pose, Operation<Void> original) {
    long token = PlayerProfile.deep() ? PlayerProfile.begin("part_transform") : 0;
    try {
      original.call(pose);
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(method = "compile")
  private void drawless$encode(
      PoseStack.Pose pose,
      VertexConsumer vertices,
      int light,
      int overlay,
      int tint,
      Operation<Void> original) {
    long token = PlayerProfile.deep() ? PlayerProfile.begin("part_encode") : 0;
    try {
      original.call(pose, vertices, light, overlay, tint);
    } finally {
      PlayerProfile.end(token);
    }
  }
}
