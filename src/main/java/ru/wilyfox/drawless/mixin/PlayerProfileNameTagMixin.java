package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import ru.wilyfox.drawless.diagnostics.PlayerProfile;

@Mixin(NameTagFeatureRenderer.class)
public class PlayerProfileNameTagMixin {
  @WrapMethod(method = "prepareText")
  private static Font.PreparedText drawless$text(
      Font font, NameTagFeatureRenderer.Submit submit, Operation<Font.PreparedText> original) {
    var tag = PlayerProfile.submit(submit);
    if (tag == null) return original.call(font, submit);
    var previous = PlayerProfile.bind(tag);
    long token = PlayerProfile.begin("player_name_tag/text_prepare");
    PlayerProfile.count("name_tag_preparations", 1);
    try {
      return original.call(font, submit);
    } finally {
      PlayerProfile.end(token);
      PlayerProfile.bind(previous);
    }
  }

  @WrapOperation(
      method = "buildGroup",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/gui/Font$PreparedText;visit(Lnet/minecraft/client/gui/Font$GlyphVisitor;)V"))
  private void drawless$glyphs(
      Font.PreparedText text,
      Font.GlyphVisitor visitor,
      Operation<Void> original,
      @Local NameTagFeatureRenderer.Submit submit) {
    if (!PlayerProfile.stages()) {
      original.call(text, visitor);
      return;
    }
    var tag = PlayerProfile.submit(submit);
    var previous = PlayerProfile.bind(tag);
    long token = tag == null ? 0 : PlayerProfile.begin("player_name_tag/glyph_encode");
    if (tag != null) PlayerProfile.count("name_tag_glyph_passes", 1);
    try {
      original.call(text, visitor);
    } finally {
      PlayerProfile.end(token);
      PlayerProfile.bind(previous);
    }
  }
}
