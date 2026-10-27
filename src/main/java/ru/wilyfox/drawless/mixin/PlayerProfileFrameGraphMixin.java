package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import org.spongepowered.asm.mixin.Mixin;
import ru.wilyfox.drawless.diagnostics.PlayerProfile;
import ru.wilyfox.drawless.diagnostics.ProfileFrameGraphInspector;

@Mixin(FrameGraphBuilder.class)
public class PlayerProfileFrameGraphMixin {
  @WrapMethod(
      method =
          "execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V")
  private void drawless$execute(
      GraphicsResourceAllocator allocator,
      FrameGraphBuilder.Inspector inspector,
      Operation<Void> original) {
    if (!PlayerProfile.stages()) {
      original.call(allocator, inspector);
      return;
    }
    long token = PlayerProfile.begin("frame_graph_cpu");
    try (var measured = new ProfileFrameGraphInspector(inspector)) {
      original.call(allocator, measured);
    } finally {
      PlayerProfile.end(token);
    }
  }
}
