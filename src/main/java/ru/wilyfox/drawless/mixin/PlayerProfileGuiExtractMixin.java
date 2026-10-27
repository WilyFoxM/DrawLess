package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import ru.wilyfox.drawless.diagnostics.PlayerProfile;

@Mixin(Gui.class)
public class PlayerProfileGuiExtractMixin {
  @WrapMethod(method = "extractRenderState(Lnet/minecraft/client/DeltaTracker;ZZ)V")
  private void drawless$extractRenderState(
      DeltaTracker delta, boolean flag, boolean flag2, Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("gui_extract") : 0;
    try {
      original.call(delta, flag, flag2);
    } finally {
      PlayerProfile.end(token);
    }
  }
}
