package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.feature.TextFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import ru.wilyfox.drawless.config.DrawLessConfig;
import ru.wilyfox.drawless.diagnostics.RenderMetrics;
import ru.wilyfox.drawless.performance.PassLocalBufferReuse;

/** Pass-scoped reuse shared by text and name tags, with active-buffer validation. */
@Mixin(RenderTypeFeatureRenderer.class)
public abstract class TextBufferReuseMixin {
  @Unique
  private final PassLocalBufferReuse<RenderType, VertexConsumer> drawless$buffers =
      new PassLocalBufferReuse<>();

  @Unique private StagedVertexBuffer drawless$staging;
  @Unique private boolean drawless$count;
  @Unique private long drawless$requests, drawless$hits;

  @WrapMethod(method = "prepareGroup")
  private void drawless$prepare(
      FeatureFrameContext context,
      List<SubmitNode> submits,
      boolean ordered,
      Operation<Void> original) {
    var config = DrawLessConfig.get();
    boolean textRenderer =
        (Object) this instanceof TextFeatureRenderer
            || (Object) this instanceof NameTagFeatureRenderer;
    boolean requested = config.enabled && config.textBufferReuse && textRenderer;
    var metrics = RenderMetrics.getInstance();
    boolean profiling = config.enabled && textRenderer && metrics.isEnabled();
    if (!requested && !profiling && !drawless$buffers.active()) {
      original.call(context, submits, ordered);
      return;
    }
    drawless$buffers.begin(requested);
    StagedVertexBuffer previous = drawless$staging;
    drawless$staging = context.stagedVertexBuffer();
    boolean previousCount = drawless$count;
    long previousRequests = drawless$requests, previousHits = drawless$hits;
    drawless$count = profiling && !previousCount;
    drawless$requests = drawless$hits = 0;
    var scope = drawless$count ? metrics.scope("render/textBufferReuse/prepare") : null;
    try {
      original.call(context, submits, ordered);
    } finally {
      if (drawless$count) {
        metrics.incrementCounter("textBufferReuse/requests", drawless$requests);
        metrics.incrementCounter("textBufferReuse/reused", drawless$hits);
        metrics.incrementCounter("textBufferReuse/lookups", drawless$requests - drawless$hits);
        scope.close();
      }
      drawless$buffers.end();
      drawless$staging = previous;
      drawless$count = previousCount;
      drawless$requests = previousRequests;
      drawless$hits = previousHits;
    }
  }

  @WrapMethod(method = "getVertexBuilder")
  private VertexConsumer drawless$buffer(RenderType type, Operation<VertexConsumer> original) {
    if (drawless$count) drawless$requests++;
    if (!drawless$buffers.active()) return original.call(type);
    var access = (StagedBufferReuseAccessor) drawless$staging;
    // Another renderer may have ended this builder; uploads also end the append phase.
    VertexConsumer current =
        access.drawless$uploadedBuffer() == null ? access.drawless$activeBuilder() : null;
    VertexConsumer reused = drawless$buffers.find(type, current);
    if (reused != null) {
      if (drawless$count) drawless$hits++;
      return reused;
    }
    VertexConsumer resolved = original.call(type);
    // Preserve vanilla semantics for types that require a distinct draw per lookup.
    if (type.canConsolidateConsecutiveGeometry()) drawless$buffers.remember(type, resolved);
    return resolved;
  }
}
