package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import ru.wilyfox.drawless.diagnostics.DisplayCostProbe;

@Mixin(ClientLevel.class)
public class DisplayCostTickMixin {
  @WrapOperation(
      method = "tickNonPassenger",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;tick()V"))
  private void drawless$tickCost(Entity entity, Operation<Void> original) {
    if (!DisplayCostProbe.tick(entity)) {
      original.call(entity);
      return;
    }
    var cost = DisplayCostProbe.cost(entity);
    long start = System.nanoTime();
    try {
      original.call(entity);
    } finally {
      cost.tickNanos += System.nanoTime() - start;
      cost.tickCalls++;
    }
  }

  @WrapOperation(
      method = "tickPassenger",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;rideTick()V"))
  private void drawless$rideCost(Entity entity, Operation<Void> original) {
    if (!DisplayCostProbe.tick(entity)) {
      original.call(entity);
      return;
    }
    var cost = DisplayCostProbe.cost(entity);
    long start = System.nanoTime();
    try {
      original.call(entity);
    } finally {
      cost.tickNanos += System.nanoTime() - start;
      cost.tickCalls++;
    }
  }
}
