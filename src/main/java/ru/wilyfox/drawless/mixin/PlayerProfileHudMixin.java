package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import ru.wilyfox.drawless.diagnostics.PlayerProfile;

@Mixin(Hud.class)
public class PlayerProfileHudMixin {
  @WrapMethod(
      method =
          "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V")
  private void drawless$extractRenderState(
      GuiGraphicsExtractor graphics, DeltaTracker delta, Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("hud_extract") : 0;
    try {
      original.call(graphics, delta);
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(method = "extractDebugOverlay(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V")
  private void drawless$extractDebugOverlay(
      GuiGraphicsExtractor graphics, Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("debug_overlay_extract") : 0;
    try {
      original.call(graphics);
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(
      method =
          "extractScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V")
  private void drawless$extractScoreboardSidebar(
      GuiGraphicsExtractor graphics, DeltaTracker delta, Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("scoreboard_extract") : 0;
    try {
      original.call(graphics, delta);
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(
      method =
          "extractTabList(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V")
  private void drawless$extractTabList(
      GuiGraphicsExtractor graphics, DeltaTracker delta, Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("tab_list_extract") : 0;
    try {
      original.call(graphics, delta);
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(
      method =
          "extractChat(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V")
  private void drawless$extractChat(
      GuiGraphicsExtractor graphics, DeltaTracker delta, Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("chat_extract") : 0;
    try {
      original.call(graphics, delta);
    } finally {
      PlayerProfile.end(token);
    }
  }
}
