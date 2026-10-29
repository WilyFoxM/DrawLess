package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import ru.wilyfox.drawless.performance.PlayerArmorGeometry;

@Mixin(ModelPart.class)
public class PlayerArmorPartMixin {
  @WrapMethod(method = "compile")
  private void drawless$retainedArmor(
      PoseStack.Pose pose,
      VertexConsumer vertices,
      int light,
      int overlay,
      int color,
      Operation<Void> original) {
    if (PlayerArmorGeometry.capturing(vertices))
      PlayerArmorGeometry.part((ModelPart) (Object) this, pose, light, overlay, color);
    else original.call(pose, vertices, light, overlay, color);
  }
}
