package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import ru.wilyfox.drawless.diagnostics.PlayerProfile;

@Mixin(ItemFeatureRenderer.class)
public class PlayerProfileItemMixin {
  @WrapMethod(method = "prepareSubmit")
  private void drawless$prepare(
      ItemFeatureRenderer.Submit submit, boolean outline, Operation<Void> original) {
    var tag = PlayerProfile.submit(submit);
    if (tag == null) {
      original.call(submit, outline);
      return;
    }
    var previous = PlayerProfile.bind(tag);
    long token = PlayerProfile.begin("player_item/" + tag.layer());
    PlayerProfile.count("item_preparations", 1);
    try {
      original.call(submit, outline);
    } finally {
      PlayerProfile.end(token);
      PlayerProfile.bind(previous);
    }
  }

  @WrapMethod(method = "prepareMainSubmit")
  private void drawless$main(ItemFeatureRenderer.Submit submit, Operation<Void> original) {
    measure("main_encode", submit, original);
  }

  @WrapMethod(method = "prepareFoilSubmit")
  private void drawless$foil(ItemFeatureRenderer.Submit submit, Operation<Void> original) {
    measure("glint_encode", submit, original);
  }

  @WrapMethod(method = "prepareOutlineSubmit")
  private void drawless$outline(ItemFeatureRenderer.Submit submit, Operation<Void> original) {
    measure("outline_encode", submit, original);
  }

  private static void measure(
      String stage, ItemFeatureRenderer.Submit submit, Operation<Void> original) {
    long token = PlayerProfile.attributed() ? PlayerProfile.begin(stage) : 0;
    try {
      original.call(submit);
    } finally {
      PlayerProfile.end(token);
    }
  }
}
