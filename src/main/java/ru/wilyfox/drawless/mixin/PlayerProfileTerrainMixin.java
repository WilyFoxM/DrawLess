package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.textures.GpuSampler;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import ru.wilyfox.drawless.diagnostics.PlayerProfile;

@Mixin(ChunkSectionsToRender.class)
public class PlayerProfileTerrainMixin {
  @WrapMethod(
      method =
          "renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V")
  private void drawless$renderGroup(
      ChunkSectionLayerGroup layers, GpuSampler sampler, Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("terrain_draw_cpu") : 0;
    try {
      original.call(layers, sampler);
    } finally {
      PlayerProfile.end(token);
    }
  }
}
