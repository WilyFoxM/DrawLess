package ru.wilyfox.drawless.performance;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Records prepared batches in their list order, opening one render pass per output target; vanilla
 * would open one per group draw. Each batch's material state comes from its prepared RenderType.
 */
final class RetainedPass {
  private RetainedPass() {}

  /** Returns the number of draws recorded. Ring buffers rotate even if recording fails. */
  static int draw(
      String label,
      List<? extends RetainedBatch<?>> batches,
      RetainedPipelines pipelines,
      GpuBuffer geometry) {
    RenderPass pass = null;
    GpuTextureView passColor = null, passDepth = null;
    boolean scissored = false;
    int draws = 0;
    try {
      for (var batch : batches) {
        if (batch.used == 0) continue;
        var prepared = batch.type.prepare();
        var target = prepared.outputTarget().getRenderTarget();
        var color =
            RenderSystem.outputColorTextureOverride != null
                ? RenderSystem.outputColorTextureOverride
                : target.getColorTextureView();
        var depth =
            target.useDepth
                ? (RenderSystem.outputDepthTextureOverride != null
                    ? RenderSystem.outputDepthTextureOverride
                    : target.getDepthTextureView())
                : null;
        if (pass == null || color != passColor || depth != passDepth) {
          if (pass != null) pass.close();
          pass =
              RenderSystem.getDevice()
                  .createCommandEncoder()
                  .createRenderPass(
                      () -> label, color, Optional.empty(), depth, OptionalDouble.empty());
          passColor = color;
          passDepth = depth;
          scissored = false;
        }
        pass.setPipeline(pipelines.get(batch.type.pipeline()));
        RenderSystem.bindDefaultUniforms(pass);
        pass.setUniform("DynamicTransforms", prepared.dynamicTransforms());
        pass.setUniform("FHGeometry", geometry);
        pass.setUniform("FHInstances", batch.instanceBuffer());
        var scissor = prepared.scissorState();
        if (scissor.enabled()) {
          pass.enableScissor(scissor.x(), scissor.y(), scissor.width(), scissor.height());
          scissored = true;
        } else if (scissored) {
          pass.disableScissor();
          scissored = false;
        }
        for (var texture : prepared.textures())
          pass.bindTexture(
              texture.name(),
              batch.texture0 != null && texture.name().equals("Sampler0")
                  ? batch.texture0
                  : texture.textureView(),
              texture.sampler());
        pass.setIndexBuffer(batch.indices(), IndexType.INT);
        pass.drawIndexed(batch.indexCount(), 1, 0, 0, 0);
        draws++;
      }
    } finally {
      if (pass != null) pass.close();
      for (var batch : batches) if (batch.used > 0) batch.rotate();
    }
    return draws;
  }
}
