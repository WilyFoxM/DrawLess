package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import ru.wilyfox.drawless.performance.PlayerGeometryReuse;

@Mixin(ModelPart.Cube.class)
public class PlayerGeometryCubeMixin {
  @WrapMethod(method = "compile")
  private void drawless$vertices(
      PoseStack.Pose pose,
      VertexConsumer vertices,
      int light,
      int overlay,
      int tint,
      Operation<Void> original) {
    if (PlayerGeometryReuse.geometry())
      PlayerGeometryReuse.compile(
          (ModelPart.Cube) (Object) this, pose, vertices, light, overlay, tint, original);
    else original.call(pose, vertices, light, overlay, tint);
  }
}
