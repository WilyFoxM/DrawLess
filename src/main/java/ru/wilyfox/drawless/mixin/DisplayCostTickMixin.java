package ru.wilyfox.drawless.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.wilyfox.drawless.diagnostics.DisplayCostProbe;

@Mixin(ClientLevel.class)
public class DisplayCostTickMixin {
  @Redirect(
      method = "tickNonPassenger",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tick()V"))
  private void drawless$tickCost(Entity entity) {
    if (!DisplayCostProbe.tick(entity)) {
      entity.tick();
      return;
    }
    var cost = DisplayCostProbe.cost(entity);
    long start = System.nanoTime();
    try {
      entity.tick();
    } finally {
      cost.tickNanos += System.nanoTime() - start;
      cost.tickCalls++;
    }
  }

  @Redirect(
      method = "tickPassenger",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;rideTick()V"))
  private void drawless$rideCost(Entity entity) {
    if (!DisplayCostProbe.tick(entity)) {
      entity.rideTick();
      return;
    }
    var cost = DisplayCostProbe.cost(entity);
    long start = System.nanoTime();
    try {
      entity.rideTick();
    } finally {
      cost.tickNanos += System.nanoTime() - start;
      cost.tickCalls++;
    }
  }
}
