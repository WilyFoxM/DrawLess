package ru.wilyfox.drawless.mixin;

import net.minecraft.client.renderer.item.SelectItemModel;
import net.minecraft.client.renderer.item.properties.select.SelectItemModelProperty;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SelectItemModel.class)
public interface DisplaySelectModelAccessor {
    @Accessor("property") SelectItemModelProperty<?> drawless$property();
    @Accessor("models") SelectItemModel.ModelSelector<?> drawless$models();
}
