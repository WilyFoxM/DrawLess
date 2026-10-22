package ru.wilyfox.drawless.mixin;

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
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.wilyfox.drawless.performance.DisplayModelBudget;

@Mixin(DisplayRenderer.ItemDisplayRenderer.class)
public class DisplayModelBudgetMixin {
  @Redirect(
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
      Display.ItemDisplay entity,
      ItemDisplayEntityRenderState state,
      float partialTick) {
    if (!DisplayModelBudget.apply(resolver, stack, context, entity, state))
      resolver.updateForNonLiving(DisplayModelBudget.writableLayers(state), stack, context, source);
  }

  @Redirect(
      method =
          "extractRenderState(Lnet/minecraft/world/entity/Display$ItemDisplay;Lnet/minecraft/client/renderer/entity/state/ItemDisplayEntityRenderState;F)V",
      at =
          @At(
              value = "INVOKE",
              target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;clear()V"))
  private void drawless$clearOwnedLayers(
      ItemStackRenderState item,
      Display.ItemDisplay entity,
      ItemDisplayEntityRenderState state,
      float partialTick) {
    DisplayModelBudget.writableLayers(state).clear();
  }
}
