package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelChunkSection.class)
public abstract class ParticleCollisionSectionMixin {
  @ModifyReturnValue(
      method =
          "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
      at = @At("RETURN"))
  private BlockState drawless$blockChanged(BlockState previous) {
    ru.wilyfox.drawless.performance.ParticleSpatialContext.blocksChanged();
    return previous;
  }

  @Inject(
      method = "read",
      at = {@At("HEAD"), @At("RETURN")})
  private void drawless$sectionChanged(CallbackInfo ci) {
    ru.wilyfox.drawless.performance.ParticleSpatialContext.blocksChanged();
  }
}
