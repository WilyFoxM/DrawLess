package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.particle.QuadParticleGroup;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import ru.wilyfox.drawless.performance.ParticleWorkProbe;

/** Leaves the vanilla decision intact; counts only during an explicit short diagnostic. */
@Mixin(QuadParticleGroup.class)
public abstract class ParticleVisibilityProbeMixin {
  @WrapOperation(
      method = "extractRenderState",
      at =
          @At(
              value = "INVOKE",
              target = "Lnet/minecraft/client/renderer/culling/Frustum;pointInFrustum(DDD)Z"))
  private boolean drawless$particleVisible(
      Frustum frustum, double x, double y, double z, Operation<Boolean> original) {
    return ParticleWorkProbe.point(original.call(frustum, x, y, z));
  }
}
