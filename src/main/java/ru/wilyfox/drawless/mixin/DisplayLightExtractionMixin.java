package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import ru.wilyfox.drawless.performance.DisplayExtractionCache;

@Mixin(EntityRenderer.class)
public abstract class DisplayLightExtractionMixin {
  @SuppressWarnings({"rawtypes", "unchecked"})
  @WrapOperation(
      method = "extractRenderState",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/entity/EntityRenderer;getPackedLightCoords(Lnet/minecraft/world/entity/Entity;F)I"))
  private int drawless$serverBrightness(
      EntityRenderer renderer, Entity entity, float partialTick, Operation<Integer> original) {
    if (entity instanceof Display.ItemDisplay display && DisplayExtractionCache.active()) {
      int override = DisplayExtractionCache.brightnessOverride(renderer, display);
      if (override != -1) return override;
    }
    return original.call(renderer, entity, partialTick);
  }
}
