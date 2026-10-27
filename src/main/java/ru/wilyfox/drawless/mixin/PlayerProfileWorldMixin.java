package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import ru.wilyfox.drawless.diagnostics.PlayerProfile;

@Mixin(LevelRenderer.class)
public class PlayerProfileWorldMixin {
  @WrapMethod(
      method =
          "render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V")
  private void drawless$render(
      GraphicsResourceAllocator allocator,
      DeltaTracker delta,
      boolean flag,
      CameraRenderState camera,
      Matrix4fc view,
      GpuBufferSlice fog,
      Vector4f skyColor,
      boolean flag7,
      Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("world_render") : 0;
    try {
      original.call(allocator, delta, flag, camera, view, fog, skyColor, flag7);
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(
      method =
          "submitFeatures(Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;Z)V")
  private void drawless$submitFeatures(
      LevelRenderState state,
      SubmitNodeCollector collector,
      boolean flag,
      Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("world_feature_submit") : 0;
    try {
      original.call(state, collector, flag);
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(
      method =
          "submitEntities(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V")
  private void drawless$submitEntities(
      PoseStack pose,
      LevelRenderState state,
      SubmitNodeCollector collector,
      Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("entities_submit") : 0;
    try {
      original.call(pose, state, collector);
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(
      method =
          "submitBlockEntities(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V")
  private void drawless$submitBlockEntities(
      PoseStack pose,
      LevelRenderState state,
      SubmitNodeCollector collector,
      Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("block_entities_submit") : 0;
    try {
      original.call(pose, state, collector);
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(
      method =
          "submitBlockDestroyAnimation(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V")
  private void drawless$submitBlockDestroyAnimation(
      PoseStack pose,
      SubmitNodeCollector collector,
      LevelRenderState state,
      Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("block_damage_submit") : 0;
    try {
      original.call(pose, collector, state);
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(
      method =
          "submitBlockOutline(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V")
  private void drawless$submitBlockOutline(
      PoseStack pose,
      SubmitNodeCollector collector,
      LevelRenderState state,
      Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("block_outline_submit") : 0;
    try {
      original.call(pose, collector, state);
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(
      method =
          "prepareChunkRenders(Lorg/joml/Matrix4fc;)Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;")
  private ChunkSectionsToRender drawless$prepareChunkRenders(
      Matrix4fc view, Operation<ChunkSectionsToRender> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("terrain_prepare") : 0;
    try {
      return original.call(view);
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(
      method = "compileSections(Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V")
  private void drawless$compileSections(CameraRenderState camera, Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("terrain_compile_cpu") : 0;
    try {
      original.call(camera);
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(method = "scheduleTranslucentSectionResort(Lnet/minecraft/world/phys/Vec3;)V")
  private void drawless$scheduleTranslucentSectionResort(Vec3 position, Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("terrain_translucency_sort") : 0;
    try {
      original.call(position);
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(method = "doEntityOutline()V")
  private void drawless$doEntityOutline(Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("entity_outline_postprocess") : 0;
    try {
      original.call();
    } finally {
      PlayerProfile.end(token);
    }
  }
}
