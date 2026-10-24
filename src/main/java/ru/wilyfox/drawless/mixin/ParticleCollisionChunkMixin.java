package ru.wilyfox.drawless.mixin;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientChunkCache.class)
public abstract class ParticleCollisionChunkMixin {
  @Inject(
      method = {"drop", "updateViewCenter", "updateViewRadius"},
      at = {@At("HEAD"), @At("RETURN")})
  private void drawless$chunksChanged(CallbackInfo ci) {
    ru.wilyfox.drawless.performance.ParticleSpatialContext.chunksChanged();
  }

  @Inject(
      method = "replaceWithPacketData",
      at = {@At("HEAD"), @At("RETURN")})
  private void drawless$chunkReplaced(CallbackInfoReturnable<LevelChunk> cir) {
    ru.wilyfox.drawless.performance.ParticleSpatialContext.chunksChanged();
  }
}
