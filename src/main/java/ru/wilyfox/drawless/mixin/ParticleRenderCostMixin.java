package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.List;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import ru.wilyfox.drawless.performance.ParticleWorkProbe;

@Mixin(QuadParticleFeatureRenderer.class)
public abstract class ParticleRenderCostMixin {
  @WrapMethod(method = "prepareGroup")
  private void drawless$prepareCost(
      FeatureFrameContext context,
      List<QuadParticleFeatureRenderer.Submit> submits,
      boolean outline,
      Operation<Void> original) {
    long started = ParticleWorkProbe.begin();
    try {
      original.call(context, submits, outline);
    } finally {
      ParticleWorkProbe.prepareEnd(started);
    }
  }

  @WrapMethod(method = "executeGroup")
  private void drawless$executeCost(
      FeatureFrameContext context,
      int group,
      List<QuadParticleFeatureRenderer.Submit> submits,
      boolean outline,
      Operation<Void> original) {
    long started = ParticleWorkProbe.begin();
    try {
      original.call(context, group, submits, outline);
    } finally {
      ParticleWorkProbe.executeEnd(started);
    }
  }
}
