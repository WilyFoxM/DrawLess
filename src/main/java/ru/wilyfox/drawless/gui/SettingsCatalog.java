package ru.wilyfox.drawless.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import ru.wilyfox.drawless.compat.RenderModSupport;
import ru.wilyfox.drawless.config.DrawLessConfig;
import ru.wilyfox.drawless.config.ExtremeDisplayMode;
import ru.wilyfox.drawless.performance.FeatureBatching;

final class SettingsCatalog {
  enum Page {
    RENDERING,
    PLAYERS,
    PARTICLES,
    ADVANCED,
    DIAGNOSTICS;

    Component title() {
      return Component.translatable("drawless.page." + name().toLowerCase(java.util.Locale.ROOT));
    }
  }

  enum Kind {
    TOGGLE,
    CHOICE,
    NUMBER
  }

  record Option(
      String id,
      Page page,
      Kind kind,
      Supplier<Component> value,
      IntConsumer change,
      BooleanSupplier selected,
      Supplier<Component> unavailable,
      IntSupplier number,
      IntConsumer setNumber,
      int min,
      int max,
      int step) {
    Component title() {
      return Component.translatable("drawless.setting." + id);
    }

    Component description() {
      return Component.translatable("drawless.setting." + id + ".description");
    }
  }

  private SettingsCatalog() {}

  static List<Option> create() {
    var options = new ArrayList<Option>();
    options.add(toggle("enabled", Page.RENDERING, () -> c().enabled, v -> c().enabled = v));
    options.add(
        toggle(
            "player_armor_geometry",
            Page.PLAYERS,
            () -> c().playerArmorGeometry,
            v -> c().playerArmorGeometry = v,
            () -> RenderModSupport.IRIS ? reason("iris") : null));
    options.add(
        choice(
            "display_mode",
            Page.RENDERING,
            () -> modeName(c().extremeDisplayMode),
            direction -> {
              var modes =
                  List.of(
                      ExtremeDisplayMode.OFF,
                      ExtremeDisplayMode.LIVE_CACHE,
                      ExtremeDisplayMode.STATIC_CACHE,
                      ExtremeDisplayMode.LIVE_EXTRACT);
              c().extremeDisplayMode =
                  modes.get(
                      Math.floorMod(
                          modes.indexOf(c().extremeDisplayMode) + direction, modes.size()));
            },
            () -> null));
    options.add(
        choice(
            "batching",
            Page.RENDERING,
            () ->
                Component.translatable(
                    "drawless.batching."
                        + FeatureBatching.requestedMode().toLowerCase(java.util.Locale.ROOT)),
            direction -> {
              int index = !c().featureBatching ? 0 : c().featureBatchingReorder ? 2 : 1;
              int next = Math.floorMod(index + direction, 3);
              c().featureBatching = next != 0;
              c().featureBatchingReorder = next == 2;
            },
            () ->
                RenderModSupport.IMMEDIATELY_FAST
                    ? reason("if")
                    : RenderModSupport.IRIS ? reason("iris") : null));
    options.add(
        new Option(
            "text_reuse",
            Page.RENDERING,
            Kind.TOGGLE,
            () -> onOff(c().textBufferReuse),
            d -> c().textBufferReuse = !c().textBufferReuse,
            () -> c().textBufferReuse,
            () -> RenderModSupport.IMMEDIATELY_FAST ? reason("if") : null,
            null,
            null,
            0,
            0,
            0));
    options.add(
        toggle(
            "model_cache",
            Page.RENDERING,
            () -> c().displayModelBudget,
            v -> c().displayModelBudget = v));
    options.add(
        toggle(
            "adaptive_budget",
            Page.RENDERING,
            () -> c().adaptiveDisplayModelBudget,
            v -> c().adaptiveDisplayModelBudget = v));
    options.add(
        number(
            "model_budget",
            Page.RENDERING,
            () -> c().displayModelBudgetMicros,
            v -> c().displayModelBudgetMicros = v,
            100,
            5000,
            100,
            true));
    options.add(
        toggle(
            "particle_light",
            Page.PARTICLES,
            () -> c().particleLightCache,
            v -> c().particleLightCache = v,
            () -> RenderModSupport.PARTICLE_CACHES ? null : reason("particle_pipeline")));
    options.add(
        toggle(
            "particle_collision",
            Page.PARTICLES,
            () -> c().particleEmptyCollision,
            v -> c().particleEmptyCollision = v,
            () -> RenderModSupport.PARTICLE_CACHES ? null : reason("particle_pipeline")));
    options.add(
        toggle(
            "npc_budget",
            Page.ADVANCED,
            () -> c().modelEngineNpcBudget,
            v -> c().modelEngineNpcBudget = v));
    options.add(
        number(
            "npc_groups",
            Page.ADVANCED,
            () -> c().npcGroupsPerFrame,
            v -> c().npcGroupsPerFrame = v,
            1,
            100,
            1,
            false));
    options.add(
        toggle(
            "adaptive_npc",
            Page.ADVANCED,
            () -> c().adaptiveNpcBudget,
            v -> c().adaptiveNpcBudget = v));
    options.add(
        choice(
            "diagnostic_mode",
            Page.DIAGNOSTICS,
            () ->
                switch (c().extremeDisplayMode) {
                  case FREEZE, UNRENDER, TICKS_ONLY -> modeName(c().extremeDisplayMode);
                  default -> onOff(false);
                },
            direction -> {
              var modes =
                  List.of(
                      ExtremeDisplayMode.LIVE_CACHE,
                      ExtremeDisplayMode.FREEZE,
                      ExtremeDisplayMode.UNRENDER,
                      ExtremeDisplayMode.TICKS_ONLY);
              int index = Math.max(0, modes.indexOf(c().extremeDisplayMode));
              c().extremeDisplayMode = modes.get(Math.floorMod(index + direction, modes.size()));
            },
            () -> null));
    options.add(
        toggle(
            "all_displays",
            Page.DIAGNOSTICS,
            () -> c().extremeAllItemDisplays,
            v -> c().extremeAllItemDisplays = v));
    options.add(
        toggle(
            "pause_ticks",
            Page.DIAGNOSTICS,
            () -> c().extremePauseDisplayTicks,
            v -> c().extremePauseDisplayTicks = v));
    return List.copyOf(options);
  }

  static Component onOff(boolean value) {
    return Component.translatable(value ? "drawless.on" : "drawless.off");
  }

  private static Component modeName(ExtremeDisplayMode mode) {
    return Component.translatable(
        "drawless.mode." + mode.name().toLowerCase(java.util.Locale.ROOT));
  }

  private static Component reason(String id) {
    return Component.translatable("drawless.unavailable." + id);
  }

  private static DrawLessConfig c() {
    return DrawLessConfig.get();
  }

  private static Option toggle(String id, Page page, BooleanSupplier get, Consumer<Boolean> set) {
    return toggle(id, page, get, set, () -> null);
  }

  private static Option toggle(
      String id,
      Page page,
      BooleanSupplier get,
      Consumer<Boolean> set,
      Supplier<Component> unavailable) {
    return new Option(
        id,
        page,
        Kind.TOGGLE,
        () -> onOff(get.getAsBoolean()),
        d -> set.accept(!get.getAsBoolean()),
        get,
        unavailable,
        null,
        null,
        0,
        0,
        0);
  }

  private static Option choice(
      String id,
      Page page,
      Supplier<Component> value,
      IntConsumer change,
      Supplier<Component> unavailable) {
    return new Option(
        id, page, Kind.CHOICE, value, change, () -> false, unavailable, null, null, 0, 0, 0);
  }

  private static Option number(
      String id,
      Page page,
      IntSupplier get,
      IntConsumer set,
      int min,
      int max,
      int step,
      boolean micros) {
    return new Option(
        id,
        page,
        Kind.NUMBER,
        () ->
            micros
                ? Component.translatable("drawless.microseconds", get.getAsInt())
                : Component.literal(Integer.toString(get.getAsInt())),
        direction -> set.accept(Math.clamp(get.getAsInt() + direction * step, min, max)),
        () -> false,
        () -> null,
        get,
        set,
        min,
        max,
        step);
  }
}
