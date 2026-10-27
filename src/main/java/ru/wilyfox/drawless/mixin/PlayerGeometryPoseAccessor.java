package ru.wilyfox.drawless.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PoseStack.Pose.class)
public interface PlayerGeometryPoseAccessor {
  @Accessor("trustedNormals")
  boolean drawless$trustedNormals();
}
