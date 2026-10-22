package ru.wilyfox.drawless.mixin;

import net.minecraft.client.particle.QuadParticleGroup;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.wilyfox.drawless.performance.ParticleWorkProbe;

/** Leaves the vanilla decision intact; counts only during an explicit short diagnostic. */
@Mixin(QuadParticleGroup.class)
public abstract class ParticleVisibilityProbeMixin {
  @Redirect(
      method = "extractRenderState",
      at =
          @At(
              value = "INVOKE",
              target = "Lnet/minecraft/client/renderer/culling/Frustum;pointInFrustum(DDD)Z"))
  private boolean drawless$particleVisible(Frustum frustum, double x, double y, double z) {
    return ParticleWorkProbe.point(frustum.pointInFrustum(x, y, z));
  }
}
