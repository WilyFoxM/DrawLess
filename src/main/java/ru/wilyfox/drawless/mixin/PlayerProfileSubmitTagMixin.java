package ru.wilyfox.drawless.mixin;

import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.wilyfox.drawless.diagnostics.PlayerProfile;

@Mixin({
  ModelFeatureRenderer.Submit.class,
  ItemFeatureRenderer.Submit.class,
  NameTagFeatureRenderer.Submit.class
})
public class PlayerProfileSubmitTagMixin {
  @Inject(method = "<init>", at = @At("RETURN"))
  private void drawless$tag(CallbackInfo ci) {
    PlayerProfile.tag(this);
  }
}
