package ru.wilyfox.drawless.mixin;

import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.state.DisplayEntityRenderState;
import net.minecraft.world.entity.Display;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(DisplayRenderer.class)
public interface DisplayOrientationAccessor {
  @Invoker("calculateOrientation")
  Quaternionf drawless$orientation(
      Display.RenderState render, DisplayEntityRenderState state, Quaternionf orientation);
}
