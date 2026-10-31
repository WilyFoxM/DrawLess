package ru.wilyfox.drawless.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelRenderer.class)
public interface DisplayProbeViewAreaAccessor {
  @Accessor("viewArea")
  ViewArea drawless$viewArea();
}
