package ru.wilyfox.drawless.mixin;

import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.wilyfox.drawless.performance.PlayerGeometryReuse;
import ru.wilyfox.drawless.performance.PlayerRenderOrigin;

@Mixin(ModelFeatureRenderer.Submit.class)
public class PlayerGeometrySubmitMixin implements PlayerRenderOrigin {
  @Unique private boolean drawless$player;

  @Inject(method = "<init>", at = @At("RETURN"))
  private void drawless$tag(CallbackInfo ci) {
    drawless$player = PlayerGeometryReuse.tagArmor();
  }

  @Override
  public boolean drawless$isPlayer() {
    return drawless$player;
  }

  @Override
  public void drawless$setPlayer(boolean player) {
    drawless$player = player;
  }
}
