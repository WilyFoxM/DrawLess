package ru.wilyfox.drawless.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import ru.wilyfox.drawless.DrawLess;
import ru.wilyfox.drawless.util.AtomicFileWriter;

/** Standalone rendering settings with bounded values and atomic persistence. */
public final class DrawLessConfig {
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static DrawLessConfig instance = new DrawLessConfig();
  public int schemaVersion = 1;
  public boolean enabled = true;
  public boolean textBufferReuse = false;
  public boolean playerArmorGeometry = false;
  public boolean featureBatching = true;
  public boolean featureBatchingReorder = true;
  public boolean displayModelBudget = true;
  public int displayModelBudgetMicros = 500;
  public boolean adaptiveDisplayModelBudget = true;
  public ExtremeDisplayMode extremeDisplayMode = ExtremeDisplayMode.LIVE_CACHE;
  public boolean extremeAllItemDisplays = true;
  public boolean extremePauseDisplayTicks = false;
  public boolean modelEngineNpcBudget = false;
  public int npcGroupsPerFrame = 2;
  public boolean adaptiveNpcBudget = true;
  public boolean particleLightCache = true;
  public boolean particleEmptyCollision = true;
  public boolean debug = false;

  public static DrawLessConfig get() {
    return instance;
  }

  public static Path path() {
    return FabricLoader.getInstance().getConfigDir().resolve("drawless.json");
  }

  public static DrawLessConfig decode(String text) {
    var json = JsonParser.parseString(text);
    var config = GSON.fromJson(json, DrawLessConfig.class);
    if (config == null) throw new IllegalArgumentException("Config must be an object");
    if (config.schemaVersion != 1)
      throw new IllegalArgumentException("Unsupported schema version " + config.schemaVersion);
    // Boolean-only batching settings used true for LIMITED before the reorder flag existed.
    if (json.getAsJsonObject().has("featureBatching")
        && !json.getAsJsonObject().has("featureBatchingReorder")) {
      config.featureBatchingReorder = false;
    }
    config.sanitize();
    return config;
  }

  public void sanitize() {
    displayModelBudgetMicros = Math.clamp(displayModelBudgetMicros, 100, 5000);
    npcGroupsPerFrame = Math.clamp(npcGroupsPerFrame, 1, 100);
    if (extremeDisplayMode == null) extremeDisplayMode = ExtremeDisplayMode.LIVE_CACHE;
  }

  public static void load() {
    try {
      if (Files.isRegularFile(path())) instance = decode(Files.readString(path()));
      else save();
    } catch (Exception failure) {
      // Preserve the unreadable original; a later explicit settings change may replace it.
      DrawLess.LOGGER.warn("Could not read drawless.json; using defaults", failure);
    }
  }

  public static void save() {
    instance.sanitize();
    try {
      AtomicFileWriter.write(path(), writer -> GSON.toJson(instance, writer));
    } catch (Exception failure) {
      throw new IllegalStateException("Could not save drawless.json", failure);
    }
  }

  public static String snapshot() {
    return GSON.toJson(instance);
  }

  public static void restoreSnapshot(String json) {
    instance = decode(json);
  }

  public static void defaults() {
    instance = new DrawLessConfig();
  }
}
