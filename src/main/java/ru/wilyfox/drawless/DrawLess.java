package ru.wilyfox.drawless;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.wilyfox.drawless.config.DrawLessConfig;
import ru.wilyfox.drawless.gui.SettingsKeyBinding;
import ru.wilyfox.drawless.performance.*;

public final class DrawLess implements ClientModInitializer {
  public static final Logger LOGGER = LoggerFactory.getLogger("drawless");

  @Override
  public void onInitializeClient() {
    DrawLessConfig.load();
    SettingsKeyBinding.register();
    DisplayModelBudget.register();
    NpcFrameCache.register();
    ExtremeDisplayPerformance.register();
    LiveDisplayGeometry.register();
    ParticleSpatialContext.register();
    PlayerArmorGeometry.register();
    PlayerItemGeometry.register();
    SodiumBrushableBlockEntityCulling.registerIfAvailable();
    LOGGER.info("DrawLess initialized");
  }
}
