package ru.wilyfox.drawless.mixin;

import net.minecraft.client.resources.model.ModelManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.wilyfox.drawless.performance.DisplayModelBudget;

@Mixin(ModelManager.class)
public class DisplayModelReloadMixin {
  @Inject(method = "apply", at = @At("HEAD"))
  private void drawless$clearDisplayModels(CallbackInfo ci) {
    DisplayModelBudget.clear();
    ru.wilyfox.drawless.performance.NpcFrameCache.clear();
    ru.wilyfox.drawless.performance.ExtremeDisplayPerformance.clear();
    ru.wilyfox.drawless.performance.LiveDisplayGeometry.reload();
  }
}
