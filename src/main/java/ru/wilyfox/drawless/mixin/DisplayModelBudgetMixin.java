package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.state.ItemDisplayEntityRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import ru.wilyfox.drawless.performance.DisplayModelBudget;

@Mixin(DisplayRenderer.ItemDisplayRenderer.class)
public class DisplayModelBudgetMixin {
  @WrapOperation(
      method =
          "extractRenderState(Lnet/minecraft/world/entity/Display$ItemDisplay;Lnet/minecraft/client/renderer/entity/state/ItemDisplayEntityRenderState;F)V",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/item/ItemModelResolver;updateForNonLiving(Lnet/minecraft/client/renderer/item/ItemStackRenderState;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/world/entity/Entity;)V"))
  private void drawless$cachedModel(
      ItemModelResolver resolver,
      ItemStackRenderState item,
      ItemStack stack,
      ItemDisplayContext context,
      Entity source,
      Operation<Void> original,
      Display.ItemDisplay entity,
      ItemDisplayEntityRenderState state,
      float partialTick) {
    if (!DisplayModelBudget.apply(resolver, stack, context, entity, state))
      original.call(resolver, DisplayModelBudget.writableLayers(state), stack, context, source);
  }

  @WrapOperation(
      method =
          "extractRenderState(Lnet/minecraft/world/entity/Display$ItemDisplay;Lnet/minecraft/client/renderer/entity/state/ItemDisplayEntityRenderState;F)V",
      at =
          @At(
              value = "INVOKE",
              target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;clear()V"))
  private void drawless$clearOwnedLayers(
      ItemStackRenderState item,
      Operation<Void> original,
      Display.ItemDisplay entity,
      ItemDisplayEntityRenderState state,
      float partialTick) {
    original.call(DisplayModelBudget.writableLayers(state));
  }
}
