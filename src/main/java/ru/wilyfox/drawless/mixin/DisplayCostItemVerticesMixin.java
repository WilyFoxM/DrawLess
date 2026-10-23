package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import ru.wilyfox.drawless.diagnostics.DisplayCostProbe;

@Mixin(ItemFeatureRenderer.class)
public class DisplayCostItemVerticesMixin {
  @WrapOperation(
      method = "prepareSubmit",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer;prepareMainSubmit(Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer$Submit;)V"))
  private void drawless$main(
      ItemFeatureRenderer renderer, ItemFeatureRenderer.Submit submit, Operation<Void> original) {
    measure(renderer, submit, original, true);
  }

  @WrapOperation(
      method = "prepareSubmit",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer;prepareOutlineSubmit(Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer$Submit;)V"))
  private void drawless$outline(
      ItemFeatureRenderer renderer, ItemFeatureRenderer.Submit submit, Operation<Void> original) {
    measure(renderer, submit, original, false);
  }

  @WrapOperation(
      method = "prepareSubmit",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer;prepareFoilSubmit(Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer$Submit;)V"))
  private void drawless$foil(
      ItemFeatureRenderer renderer, ItemFeatureRenderer.Submit submit, Operation<Void> original) {
    measure(renderer, submit, original, false);
  }

  private static void measure(
      ItemFeatureRenderer renderer,
      ItemFeatureRenderer.Submit submit,
      Operation<Void> original,
      boolean main) {
    var cost = DisplayCostProbe.submitCost(submit);
    long start = cost == null ? 0 : System.nanoTime();
    try {
      original.call(renderer, submit);
    } finally {
      if (cost != null) {
        cost.vertexNanos += System.nanoTime() - start;
        if (main) cost.quads += submit.quads().size();
      }
    }
  }
}
