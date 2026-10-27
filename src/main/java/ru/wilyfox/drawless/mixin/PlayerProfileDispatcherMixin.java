package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame;
import org.spongepowered.asm.mixin.Mixin;
import ru.wilyfox.drawless.diagnostics.PlayerProfile;

@Mixin(FeatureRenderDispatcher.class)
public class PlayerProfileDispatcherMixin {
  @WrapMethod(
      method =
          "prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;")
  private PreparedFrame drawless$prepareFrame(
      SubmitNodeStorage storage, Operation<PreparedFrame> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("feature_frame_prepare") : 0;
    try {
      return original.call(storage);
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(method = "renderAllFeatures(Lnet/minecraft/client/renderer/SubmitNodeStorage;)V")
  private void drawless$renderAllFeatures(SubmitNodeStorage storage, Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("feature_render_all") : 0;
    try {
      original.call(storage);
    } finally {
      PlayerProfile.end(token);
    }
  }
}
