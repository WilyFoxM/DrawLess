package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockAndLightGetter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import ru.wilyfox.drawless.performance.ParticleLightCache;

@Mixin(Particle.class)
public abstract class ParticleLightCacheMixin {
  @Shadow @Final protected ClientLevel level;
  @Unique private BlockPos drawless$lightPosition;

  @WrapOperation(
      method = "getLightCoords",
      at =
          @At(
              value = "INVOKE",
              target = "Lnet/minecraft/core/BlockPos;containing(DDD)Lnet/minecraft/core/BlockPos;"))
  private BlockPos drawless$reusePosition(
      double x, double y, double z, Operation<BlockPos> original) {
    if (!ru.wilyfox.drawless.performance.ParticleSpatialContext.positionReuse(level))
      return original.call(x, y, z);
    if (drawless$lightPosition == null
        || drawless$lightPosition.getX() != Mth.floor(x)
        || drawless$lightPosition.getY() != Mth.floor(y)
        || drawless$lightPosition.getZ() != Mth.floor(z))
      drawless$lightPosition = original.call(x, y, z).immutable();
    return drawless$lightPosition;
  }

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
