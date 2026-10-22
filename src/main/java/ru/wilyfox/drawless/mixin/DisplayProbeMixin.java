package ru.wilyfox.drawless.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.wilyfox.drawless.diagnostics.DisplayProbe;

/** Read the extracted list before submitEntities consumes and clears it. */
@Mixin(LevelExtractor.class)
public class DisplayProbeMixin {
  @Inject(method = "extractVisibleEntities", at = @At("HEAD"))
  private void drawless$prepareDisplayModels(
      Camera camera, Frustum frustum, DeltaTracker delta, LevelRenderState state, CallbackInfo ci) {
    ru.wilyfox.drawless.diagnostics.DisplayCostProbe.beginFrame(Minecraft.getInstance());
    ru.wilyfox.drawless.performance.ExtremeDisplayPerformance.beginFrame(
        Minecraft.getInstance(), state);
    long start = ru.wilyfox.drawless.diagnostics.DisplayCostProbe.frame() ? System.nanoTime() : 0;
    ru.wilyfox.drawless.performance.DisplayModelBudget.beginFrame(Minecraft.getInstance(), camera);
    if (start != 0)
      ru.wilyfox.drawless.diagnostics.DisplayCostProbe.global(
          "Layer-cache refresh", System.nanoTime() - start);
    ru.wilyfox.drawless.performance.NpcFrameCache.beginFrame(Minecraft.getInstance());
    ru.wilyfox.drawless.performance.LiveDisplayGeometry.beginFrame(Minecraft.getInstance());
    ru.wilyfox.drawless.performance.DisplayExtractionCache.beginFrame(
        Minecraft.getInstance(), ru.wilyfox.drawless.performance.LiveDisplayGeometry.ready());
    ru.wilyfox.drawless.performance.LiveDisplayGeometry.startEntityLoop();
  }

  @Inject(method = "extractVisibleEntities", at = @At("RETURN"))
  private void drawless$sampleDisplays(
      Camera camera, Frustum frustum, DeltaTracker delta, LevelRenderState state, CallbackInfo ci) {
    ru.wilyfox.drawless.performance.LiveDisplayGeometry.finishEntityLoop();
    ru.wilyfox.drawless.performance.ExtremeDisplayPerformance.finishFrame(
        Minecraft.getInstance(), state);
    ru.wilyfox.drawless.performance.LiveDisplayGeometry.finishFrame(Minecraft.getInstance(), state);
    ru.wilyfox.drawless.performance.DisplayExtractionCache.endFrame();
    ru.wilyfox.drawless.performance.DisplayModelBudget.endFrame();
    ru.wilyfox.drawless.performance.NpcFrameCache.endFrame();
    ru.wilyfox.drawless.performance.ExtremeDisplayPerformance.endFrame();
    ru.wilyfox.drawless.diagnostics.DisplayCostProbe.sceneEnd();
    DisplayProbe.onFrame(Minecraft.getInstance(), camera, frustum, state);
  }
}
