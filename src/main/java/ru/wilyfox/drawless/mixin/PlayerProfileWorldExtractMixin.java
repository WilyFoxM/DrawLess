package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import ru.wilyfox.drawless.diagnostics.PlayerProfile;

@Mixin(LevelExtractor.class)
public class PlayerProfileWorldExtractMixin {
  @WrapMethod(
      method = "extract(Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/Camera;F)V")
  private void drawless$extract(
      DeltaTracker delta, Camera camera, float partialTick, Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("world_extract") : 0;
    try {
      original.call(delta, camera, partialTick);
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(
      method =
          "extractVisibleEntities(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V")
  private void drawless$extractVisibleEntities(
      Camera camera,
      Frustum frustum,
      DeltaTracker delta,
      LevelRenderState state,
      Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("entities_extract") : 0;
    try {
      original.call(camera, frustum, delta, state);
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(
      method =
          "extractVisibleBlockEntities(Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/state/level/LevelRenderState;)V")
  private void drawless$extractVisibleBlockEntities(
      Camera camera, float partialTick, LevelRenderState state, Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("block_entities_extract") : 0;
    try {
      original.call(camera, partialTick, state);
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(
      method =
          "extractBlockOutline(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V")
  private void drawless$extractBlockOutline(
      Camera camera, LevelRenderState state, Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("block_outline_extract") : 0;
    try {
      original.call(camera, state);
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(
      method =
          "extractBlockDestroyAnimation(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V")
  private void drawless$extractBlockDestroyAnimation(
      Camera camera, LevelRenderState state, Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("block_damage_extract") : 0;
    try {
      original.call(camera, state);
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(method = "extractGizmos()V")
  private void drawless$extractGizmos(Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("gizmos_extract") : 0;
    try {
      original.call();
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(method = "applyFrustum(Lnet/minecraft/client/renderer/culling/Frustum;)V")
  private void drawless$applyFrustum(Frustum frustum, Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("terrain_frustum_apply") : 0;
    try {
      original.call(frustum);
    } finally {
      PlayerProfile.end(token);
    }
  }
}
