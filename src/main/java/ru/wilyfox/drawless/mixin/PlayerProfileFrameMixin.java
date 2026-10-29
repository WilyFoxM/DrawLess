package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuSurface;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import ru.wilyfox.drawless.diagnostics.PlayerProfile;

@Mixin(Minecraft.class)
public class PlayerProfileFrameMixin {
  @WrapMethod(method = "renderFrame")
  private void drawless$playerFrame(boolean render, Operation<Void> original) {
    long token = PlayerProfile.frameBegin((Minecraft) (Object) this);
    boolean success = false;
    try {
      original.call(render);
      success = true;
    } finally {
      PlayerProfile.frameEnd(token, success);
    }
  }

  @WrapOperation(
      method = "renderFrame",
      at =
          @At(
              value = "INVOKE",
              target = "Lcom/mojang/blaze3d/systems/GpuSurface;acquireNextTexture()V"))
  private void drawless$acquire(GpuSurface surface, Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("surface_acquire_cpu") : 0;
    try {
      original.call(surface);
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapOperation(
      method = "renderFrame",
      at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuSurface;present()V"))
  private void drawless$present(GpuSurface surface, Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("presentation_cpu_wait") : 0;
    try {
      original.call(surface);
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapOperation(
      method = "renderFrame",
      at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/CommandEncoder;submit()V"))
  private void drawless$commandSubmit(CommandEncoder encoder, Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("shared_command_submit_cpu") : 0;
    try {
      original.call(encoder);
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapOperation(
      method = "renderFrame",
      at =
          @At(
              value = "INVOKE",
              target = "Lnet/minecraft/client/FramerateLimiter;limitDisplayFPS(I)V"))
  private void drawless$limit(int limit, Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("fps_limiter_wait") : 0;
    try {
      original.call(limit);
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapOperation(
      method = "renderFrame",
      at =
          @At(
              value = "INVOKE",
              target = "Lcom/mojang/blaze3d/systems/RenderSystem;executePendingTasks()V"))
  private void drawless$pending(Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("pending_render_tasks_cpu") : 0;
    try {
      original.call();
    } finally {
      PlayerProfile.end(token);
    }
  }
}
