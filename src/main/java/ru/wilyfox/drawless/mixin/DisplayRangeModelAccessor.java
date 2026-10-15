package ru.wilyfox.drawless.mixin;

import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.RangeSelectItemModel;
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperty;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(RangeSelectItemModel.class)
public interface DisplayRangeModelAccessor {
    @Accessor("property") RangeSelectItemModelProperty drawless$property();
    @Accessor("models") ItemModel[] drawless$models();
    @Accessor("fallback") ItemModel drawless$fallback();
    @Accessor("scale") float drawless$scale();
    @Accessor("thresholds") float[] drawless$thresholds();
    @Invoker("lastIndexLessOrEqual") static int drawless$lastIndex(float[] thresholds, float value) { throw new AssertionError(); }
}
