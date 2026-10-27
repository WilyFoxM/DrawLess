package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import ru.wilyfox.drawless.diagnostics.PlayerProfile;

@Mixin(AvatarRenderer.class)
public class PlayerProfileAvatarMixin {
  @WrapMethod(
      method =
          "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V")
  private void drawless$extract(
      Avatar entity, AvatarRenderState state, float delta, Operation<Void> original) {
    var tag = entity instanceof Player ? PlayerProfile.extracted(entity, state) : null;
    if (tag == null) {
      original.call(entity, state, delta);
      return;
    }
    var previous = PlayerProfile.bind(tag);
    long token = PlayerProfile.begin("player_extract");
    try {
      original.call(entity, state, delta);
    } finally {
      PlayerProfile.end(token);
      PlayerProfile.bind(previous);
    }
  }
}
