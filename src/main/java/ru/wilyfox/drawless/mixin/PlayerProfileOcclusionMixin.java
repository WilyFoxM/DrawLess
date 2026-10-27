package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ChunkLoadingRenderState;
import org.spongepowered.asm.mixin.Mixin;
import ru.wilyfox.drawless.diagnostics.PlayerProfile;

@Mixin(SectionOcclusionGraph.class)
public class PlayerProfileOcclusionMixin {
  @WrapMethod(
      method =
          "update(Lnet/minecraft/client/renderer/state/level/CameraRenderState;ILnet/minecraft/client/renderer/state/level/ChunkLoadingRenderState;)V")
  private void drawless$update(
      CameraRenderState camera,
      int fov,
      ChunkLoadingRenderState loading,
      Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("terrain_occlusion_update") : 0;
    try {
      original.call(camera, fov, loading);
    } finally {
      PlayerProfile.end(token);
    }
  }
}
