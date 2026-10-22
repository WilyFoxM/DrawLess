package ru.wilyfox.drawless.performance;

import net.caffeinemc.mods.sodium.api.blockentity.BlockEntityRenderHandler;
import net.caffeinemc.mods.sodium.api.blockentity.BlockEntityRenderPredicate;
import net.minecraft.world.level.block.entity.BrushableBlockEntity;

/** Isolated so a client without Sodium never resolves Sodium API classes. */
final class SodiumBrushableBlockEntityCullingImpl {
  private static final BlockEntityRenderPredicate<BrushableBlockEntity> ACTIVE_BRUSHING_ONLY =
      (world, pos, blockEntity) ->
          SodiumBrushableBlockEntityCulling.shouldRender(blockEntity.getBlockState());

  private SodiumBrushableBlockEntityCullingImpl() {}

  static void register() {
    BlockEntityRenderHandler.instance()
        .addRenderPredicate(
            net.minecraft.world.level.block.entity.BlockEntityTypes.BRUSHABLE_BLOCK,
            ACTIVE_BRUSHING_ONLY);
  }
}
