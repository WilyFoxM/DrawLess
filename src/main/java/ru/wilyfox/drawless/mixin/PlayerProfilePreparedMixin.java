package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame;
import org.spongepowered.asm.mixin.Mixin;
import ru.wilyfox.drawless.diagnostics.PlayerProfile;

@Mixin(PreparedFrame.class)
public class PlayerProfilePreparedMixin {
  @WrapMethod(method = "executeSolid()V")
  private void drawless$executeSolid(Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("feature_execute_solid") : 0;
    try {
      original.call();
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(method = "executeTranslucent()V")
  private void drawless$executeTranslucent(Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("feature_execute_translucent") : 0;
    try {
      original.call();
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(method = "executeOutline()V")
  private void drawless$executeOutline(Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("feature_execute_outline") : 0;
    try {
      original.call();
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(method = "executeTranslucentAfterTerrain()V")
  private void drawless$executeTranslucentAfterTerrain(Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("feature_execute_after_terrain") : 0;
    try {
      original.call();
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(method = "executeAlwaysOnTop()V")
  private void drawless$executeAlwaysOnTop(Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("feature_execute_on_top") : 0;
    try {
      original.call();
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(method = "close()V")
  private void drawless$close(Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("feature_frame_release") : 0;
    try {
      original.call();
    } finally {
      PlayerProfile.end(token);
    }
  }
}
