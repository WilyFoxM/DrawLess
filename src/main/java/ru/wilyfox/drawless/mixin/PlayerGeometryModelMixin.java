package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import ru.wilyfox.drawless.performance.PlayerGeometryReuse;
import ru.wilyfox.drawless.performance.PlayerRenderOrigin;

@Mixin(ModelFeatureRenderer.class)
public class PlayerGeometryModelMixin {
  @WrapMethod(method = "prepareModel")
  private void drawless$prepare(ModelFeatureRenderer.Submit<?> submit, Operation<Void> original) {
    Object previousState = PlayerGeometryReuse.context();
    int previous =
        PlayerGeometryReuse.prepare(
            ((PlayerRenderOrigin) (Object) submit).drawless$isPlayer(), submit.state());
    try {
      original.call(submit);
    } finally {
      PlayerGeometryReuse.restorePrepare(previous, previousState);
    }
  }
}
