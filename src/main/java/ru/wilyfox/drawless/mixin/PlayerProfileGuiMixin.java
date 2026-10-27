package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState.TraverseRange;
import org.spongepowered.asm.mixin.Mixin;
import ru.wilyfox.drawless.diagnostics.PlayerProfile;

@Mixin(GuiRenderer.class)
public class PlayerProfileGuiMixin {
  @WrapMethod(method = "render()V")
  private void drawless$render(Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("gui_render") : 0;
    try {
      original.call();
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(method = "prepare()V")
  private void drawless$prepare(Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("gui_prepare") : 0;
    try {
      original.call();
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(method = "prepareText()V")
  private void drawless$prepareText(Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("gui_text_prepare") : 0;
    try {
      original.call();
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(method = "prepareItemElements()V")
  private void drawless$prepareItemElements(Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("gui_items_prepare") : 0;
    try {
      original.call();
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(method = "preparePictureInPicture()V")
  private void drawless$preparePictureInPicture(Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("gui_pictures_prepare") : 0;
    try {
      original.call();
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(
      method =
          "addElementsToMeshes(Lnet/minecraft/client/renderer/state/gui/GuiRenderState$TraverseRange;)V")
  private void drawless$addElementsToMeshes(TraverseRange range, Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("gui_mesh_encode") : 0;
    try {
      original.call(range);
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(method = "draw()V")
  private void drawless$draw(Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("gui_draw_cpu") : 0;
    try {
      original.call();
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(method = "endFrame()V")
  private void drawless$endFrame(Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("gui_end_frame") : 0;
    try {
      original.call();
    } finally {
      PlayerProfile.end(token);
    }
  }
}
