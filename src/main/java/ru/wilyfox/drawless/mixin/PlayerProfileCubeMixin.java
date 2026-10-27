package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import ru.wilyfox.drawless.diagnostics.PlayerProfile;

@Mixin(ModelPart.Cube.class)
public class PlayerProfileCubeMixin {
  @WrapMethod(method = "compile")
  private void drawless$compile(
      PoseStack.Pose pose,
      VertexConsumer vertices,
      int light,
      int overlay,
      int tint,
      Operation<Void> original) {
    long token = PlayerProfile.deep() ? PlayerProfile.begin("cube_transform_emit") : 0;
    if (token != 0) {
      PlayerProfile.count("cube_compile_calls", 1);
      PlayerProfile.count(
          "cube_topology_vertices", ((ModelPart.Cube) (Object) this).polygons.length * 4L);
    }
    try {
      original.call(pose, vertices, light, overlay, tint);
    } finally {
      PlayerProfile.end(token);
    }
  }
}
