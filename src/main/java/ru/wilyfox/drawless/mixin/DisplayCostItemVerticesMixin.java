package ru.wilyfox.drawless.mixin;

import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.wilyfox.drawless.diagnostics.DisplayCostProbe;

@Mixin(ItemFeatureRenderer.class)
public class DisplayCostItemVerticesMixin {
  @Redirect(
      method = "prepareSubmit",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer;prepareMainSubmit(Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer$Submit;)V"))
  private void drawless$main(ItemFeatureRenderer renderer, ItemFeatureRenderer.Submit submit) {
    measure(renderer, submit, 0);
  }

  @Redirect(
      method = "prepareSubmit",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer;prepareOutlineSubmit(Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer$Submit;)V"))
  private void drawless$outline(ItemFeatureRenderer renderer, ItemFeatureRenderer.Submit submit) {
    measure(renderer, submit, 1);
  }

  @Redirect(
      method = "prepareSubmit",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer;prepareFoilSubmit(Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer$Submit;)V"))
  private void drawless$foil(ItemFeatureRenderer renderer, ItemFeatureRenderer.Submit submit) {
    measure(renderer, submit, 2);
  }

  private static void measure(
      ItemFeatureRenderer renderer, ItemFeatureRenderer.Submit submit, int kind) {
    var cost = DisplayCostProbe.submitCost(submit);
    long start = cost == null ? 0 : System.nanoTime();
    try {
      var invoke = (DisplayCostItemAccessor) renderer;
      switch (kind) {
        case 0 -> invoke.drawless$main(submit);
        case 1 -> invoke.drawless$outline(submit);
        default -> invoke.drawless$foil(submit);
      }
    } finally {
      if (cost != null) {
        cost.vertexNanos += System.nanoTime() - start;
        if (kind == 0) cost.quads += submit.quads().size();
      }
    }
  }
}
