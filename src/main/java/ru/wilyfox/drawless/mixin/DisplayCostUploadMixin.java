package ru.wilyfox.drawless.mixin;

import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.wilyfox.drawless.diagnostics.DisplayCostProbe;

@Mixin(StagedVertexBuffer.class)
public class DisplayCostUploadMixin {
  @Unique private long drawless$uploadStarted;

  @Inject(method = "upload", at = @At("HEAD"))
  private void drawless$beginUpload(CallbackInfo ci) {
    drawless$uploadStarted = DisplayCostProbe.frame() ? System.nanoTime() : 0;
  }

  @Inject(method = "upload", at = @At("RETURN"))
  private void drawless$endUpload(CallbackInfo ci) {
    if (drawless$uploadStarted != 0) {
      DisplayCostProbe.global(
          "Staged vertex upload (all features)", System.nanoTime() - drawless$uploadStarted);
      drawless$uploadStarted = 0;
    }
  }
}
