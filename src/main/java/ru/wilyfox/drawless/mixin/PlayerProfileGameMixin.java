package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import ru.wilyfox.drawless.diagnostics.PlayerProfile;

@Mixin(GameRenderer.class)
public class PlayerProfileGameMixin {
  @WrapMethod(method = "extract")
  private void drawless$extract(DeltaTracker delta, boolean tick, Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("scene_extract") : 0;
    try {
      original.call(delta, tick);
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(method = "render")
  private void drawless$render(DeltaTracker delta, boolean tick, Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("scene_render") : 0;
    try {
      original.call(delta, tick);
    } finally {
      PlayerProfile.end(token);
    }
  }
}
