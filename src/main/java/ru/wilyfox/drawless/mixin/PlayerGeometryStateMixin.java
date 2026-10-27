package ru.wilyfox.drawless.mixin;

import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import ru.wilyfox.drawless.performance.PlayerRenderOrigin;

@Mixin(AvatarRenderState.class)
public class PlayerGeometryStateMixin implements PlayerRenderOrigin {
  @Unique private boolean drawless$player;

  @Override
  public boolean drawless$isPlayer() {
    return drawless$player;
  }

  @Override
  public void drawless$setPlayer(boolean player) {
    drawless$player = player;
  }
}
