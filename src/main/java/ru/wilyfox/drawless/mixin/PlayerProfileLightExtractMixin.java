package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import ru.wilyfox.drawless.diagnostics.PlayerProfile;

@Mixin(LightmapRenderStateExtractor.class)
public class PlayerProfileLightExtractMixin {
  @WrapMethod(method = "extract(Lnet/minecraft/client/renderer/state/LightmapRenderState;F)V")
  private void drawless$extract(
      LightmapRenderState state, float partialTick, Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("lightmap_extract") : 0;
    try {
      original.call(state, partialTick);
    } finally {
      PlayerProfile.end(token);
    }
  }
}
