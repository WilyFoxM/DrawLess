package ru.wilyfox.drawless.performance;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import static ru.wilyfox.drawless.DrawLess.LOGGER;

/** Loads the Sodium-specific implementation only when Sodium is present. */
public final class SodiumBrushableBlockEntityCulling {
    private static boolean registered;

    private SodiumBrushableBlockEntityCulling() {
    }

    public static void registerIfAvailable() {
        if (registered || !FabricLoader.getInstance().isModLoaded("sodium")) {
            return;
        }

        try {
            SodiumBrushableBlockEntityCullingImpl.register();
            registered = true;
            LOGGER.info("Enabled Sodium culling for inactive brushable block entities");
        } catch (LinkageError | RuntimeException exception) {
            LOGGER.warn("Could not enable Sodium brushable block entity culling", exception);
        }
    }

    static boolean shouldRender(BlockState state) {
        if (!ru.wilyfox.drawless.config.DrawLessConfig.get().enabled) return true;
        return state.hasProperty(BlockStateProperties.DUSTED)
                && isActiveDustedLevel(state.getValue(BlockStateProperties.DUSTED));
    }

    static boolean isActiveDustedLevel(int dusted) {
        return dusted > 0;
    }
}
