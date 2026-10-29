package ru.wilyfox.drawless.mixin;

import java.util.List;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ModelPart.class)
public interface ModelPartCubesAccessor {
  @Accessor("cubes")
  List<ModelPart.Cube> drawless$cubes();
}
