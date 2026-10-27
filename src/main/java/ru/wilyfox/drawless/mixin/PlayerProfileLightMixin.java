package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import ru.wilyfox.drawless.diagnostics.PlayerProfile;

@Mixin(Lightmap.class)
public class PlayerProfileLightMixin {
  @WrapMethod(method = "render(Lnet/minecraft/client/renderer/state/LightmapRenderState;)V")
  private void drawless$render(LightmapRenderState state, Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("lightmap_render_cpu") : 0;
    try {
      original.call(state);
    } finally {
      PlayerProfile.end(token);
    }
  }
}
