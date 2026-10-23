package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.wilyfox.drawless.diagnostics.DisplayCostProbe;

@Mixin(RenderTypeFeatureRenderer.class)
public class DisplayCostItemGroupMixin {
  @Unique private long drawless$itemGroupStarted;

  @Inject(method = "prepareGroup", at = @At("HEAD"))
  private void drawless$beginGroup(CallbackInfo ci) {
    drawless$itemGroupStarted =
        DisplayCostProbe.frame() && (Object) this instanceof ItemFeatureRenderer
            ? System.nanoTime()
            : 0;
  }

  @Inject(method = "prepareGroup", at = @At("RETURN"))
  private void drawless$endGroup(CallbackInfo ci) {
    if (drawless$itemGroupStarted != 0) {
      DisplayCostProbe.global(
          "Item preparation group (inclusive)", System.nanoTime() - drawless$itemGroupStarted);
      drawless$itemGroupStarted = 0;
    }
  }

  @WrapOperation(
      method = "executeGroup",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/rendertype/PreparedRenderType;drawFromBuffer(Lnet/minecraft/client/renderer/StagedVertexBuffer$ExecuteInfo;)V"))
  private void drawless$itemDraw(
      net.minecraft.client.renderer.rendertype.PreparedRenderType type,
      net.minecraft.client.renderer.StagedVertexBuffer.ExecuteInfo info,
      Operation<Void> original) {
    if ((Object) this instanceof ItemFeatureRenderer) DisplayCostProbe.itemDraw();
    var metrics = ru.wilyfox.drawless.diagnostics.RenderMetrics.getInstance();
    if (metrics.isEnabled()
        && ru.wilyfox.drawless.config.DrawLessConfig.get().enabled
        && !ru.wilyfox.drawless.compat.RenderModSupport.IMMEDIATELY_FAST
        && !ru.wilyfox.drawless.compat.RenderModSupport.IRIS) {
      try (var ignored = metrics.scope("render/featureBatching/submitDraw")) {
        original.call(type, info);
        metrics.incrementCounter("featureBatching/drawSubmissions", 1);
      }
    } else original.call(type, info);
  }
}
