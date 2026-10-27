package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import org.spongepowered.asm.mixin.Mixin;
import ru.wilyfox.drawless.diagnostics.PlayerProfile;

@Mixin(ParticleEngine.class)
public class PlayerProfileParticleExtractMixin {
  @WrapMethod(
      method =
          "extract(Lnet/minecraft/client/renderer/state/level/ParticlesRenderState;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/Camera;F)V")
  private void drawless$extract(
      ParticlesRenderState state,
      Frustum frustum,
      Camera camera,
      float partialTick,
      Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("particles_extract") : 0;
    try {
      original.call(state, frustum, camera, partialTick);
    } finally {
      PlayerProfile.end(token);
    }
  }
}
