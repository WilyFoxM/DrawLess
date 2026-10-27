package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import ru.wilyfox.drawless.diagnostics.PlayerProfile;

@Mixin(GameRenderer.class)
public class PlayerProfileSceneMixin {
  @WrapMethod(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V")
  private void drawless$renderLevel(DeltaTracker delta, Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("world_view") : 0;
    try {
      original.call(delta);
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(
      method =
          "renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;FLorg/joml/Matrix4fc;)V")
  private void drawless$renderItemInHand(
      CameraRenderState camera, float partialTick, Matrix4fc view, Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("hand_render") : 0;
    try {
      original.call(camera, partialTick, view);
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(method = "extractWindow()V")
  private void drawless$extractWindow(Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("window_extract") : 0;
    try {
      original.call();
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(method = "extractOptions()V")
  private void drawless$extractOptions(Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("options_extract") : 0;
    try {
      original.call();
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(method = "extractCamera(Lnet/minecraft/client/DeltaTracker;FF)V")
  private void drawless$extractCamera(
      DeltaTracker delta, float partialTick, float partialTick2, Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("camera_extract") : 0;
    try {
      original.call(delta, partialTick, partialTick2);
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(method = "tryTakeScreenshotIfNeeded()V")
  private void drawless$tryTakeScreenshotIfNeeded(Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("world_screenshot_check") : 0;
    try {
      original.call();
    } finally {
      PlayerProfile.end(token);
    }
  }
}
