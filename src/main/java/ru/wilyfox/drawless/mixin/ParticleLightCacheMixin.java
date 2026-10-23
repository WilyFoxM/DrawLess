package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndLightGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import ru.wilyfox.drawless.performance.ParticleLightCache;

@Mixin(Particle.class)
public abstract class ParticleLightCacheMixin {
  // Only the base block-light query is shared. Subclass brightness/age/emissive overrides still
  // run.
  @WrapOperation(
      method = "getLightCoords",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/util/LightCoordsUtil;getLightCoords(Lnet/minecraft/world/level/BlockAndLightGetter;Lnet/minecraft/core/BlockPos;)I"))
  private int drawless$particleBlockLight(
      BlockAndLightGetter level, BlockPos pos, Operation<Integer> original) {
    return ParticleLightCache.light(level, pos, original);
  }
}
