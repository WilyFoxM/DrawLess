package ru.wilyfox.drawless.mixin;

import net.minecraft.client.renderer.entity.state.ItemDisplayEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ItemDisplayEntityRenderState.class)
public interface DisplayItemStateAccessor {
    @Mutable @Accessor("item") void drawless$setItem(ItemStackRenderState state);
}
