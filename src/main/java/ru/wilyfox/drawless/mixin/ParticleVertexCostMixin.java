package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.spongepowered.asm.mixin.Mixin;
import ru.wilyfox.drawless.performance.ParticleWorkProbe;

@Mixin(QuadParticleRenderState.class)
public abstract class ParticleVertexCostMixin {
  @WrapMethod(method = "buildLayer")
  private void drawless$vertexCost(
      SingleQuadParticle.Layer layer, VertexConsumer consumer, Operation<Void> original) {
    long started = ParticleWorkProbe.begin();
    try {
      original.call(layer, consumer);
    } finally {
      ParticleWorkProbe.vertexEnd(started);
    }
  }
}
