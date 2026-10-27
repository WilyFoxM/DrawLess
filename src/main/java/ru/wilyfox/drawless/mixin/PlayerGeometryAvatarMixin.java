package ru.wilyfox.drawless.mixin;

import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.wilyfox.drawless.performance.PlayerRenderOrigin;

@Mixin(AvatarRenderer.class)
public class PlayerGeometryAvatarMixin {
  @Inject(
      method =
          "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
      at = @At("HEAD"))
  private void drawless$origin(
      Avatar entity, AvatarRenderState state, float delta, CallbackInfo ci) {
    ((PlayerRenderOrigin) state).drawless$setPlayer(entity instanceof Player);
  }
}
