package ru.wilyfox.drawless.mixin;

import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.wilyfox.drawless.performance.ParticleSpatialContext;

/** Invalidate after visible light storage actually changes, including zero-propagation updates. */
@Mixin(LayerLightSectionStorage.class)
public abstract class ParticleLightPublicationMixin {
  @Shadow @Final protected LightChunkGetter chunkSource;

  @Inject(
      method = "swapSectionMap",
      at =
          @At(
              value = "FIELD",
              target =
                  "Lnet/minecraft/world/level/lighting/LayerLightSectionStorage;visibleSectionData:Lnet/minecraft/world/level/lighting/DataLayerStorageMap;",
              opcode = Opcodes.PUTFIELD,
              shift = At.Shift.AFTER))
  private void drawless$publishedLight(CallbackInfo ci) {
    ParticleSpatialContext.lightPublished(chunkSource);
  }
}
