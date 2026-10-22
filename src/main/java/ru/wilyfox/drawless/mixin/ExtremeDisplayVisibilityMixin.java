package ru.wilyfox.drawless.mixin;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.wilyfox.drawless.performance.ExtremeDisplayPerformance;

@Mixin(LevelExtractor.class)
public class ExtremeDisplayVisibilityMixin {
  @Redirect(
      method = "extractVisibleEntities",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/extract/LevelExtractor;isEntityVisible(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z"))
  private boolean drawless$extremeScene(
      LevelExtractor extractor, Entity entity, Frustum frustum, double x, double y, double z) {
    if (ExtremeDisplayPerformance.staticCache())
      return ExtremeDisplayPerformance.staticVisibility(
          entity, extractor.isEntityVisible(entity, frustum, x, y, z));
    int visible = ExtremeDisplayPerformance.visibility(entity);
    return visible < 0 ? extractor.isEntityVisible(entity, frustum, x, y, z) : visible == 1;
  }
}
