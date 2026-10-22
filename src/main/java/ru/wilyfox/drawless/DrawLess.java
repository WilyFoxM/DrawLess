package ru.wilyfox.drawless;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.wilyfox.drawless.config.DrawLessConfig;
import ru.wilyfox.drawless.diagnostics.*;
import ru.wilyfox.drawless.gui.OpenSettings;
import ru.wilyfox.drawless.performance.*;

public final class DrawLess implements ClientModInitializer {
  public static final Logger LOGGER = LoggerFactory.getLogger("drawless");

  @Override
  public void onInitializeClient() {
    DrawLessConfig.load();
    net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(
        OpenSettings::openPending);
    DisplayModelBudget.register();
    NpcFrameCache.register();
    ExtremeDisplayPerformance.register();
    LiveDisplayGeometry.register();
    DisplayProbe.register();
    DisplayCostProbe.register();
    SodiumBrushableBlockEntityCulling.registerIfAvailable();
    ClientCommandRegistrationCallback.EVENT.register(
        (dispatcher, access) ->
            dispatcher.register(
                ClientCommands.literal("drawless")
                    .executes(
                        context -> {
                          new OpenSettings().run();
                          return 1;
                        })
                    .then(
                        ClientCommands.literal("settings")
                            .executes(
                                context -> {
                                  new OpenSettings().run();
                                  return 1;
                                }))
                    .then(
                        ClientCommands.literal("status")
                            .executes(
                                context -> {
                                  context.getSource().sendFeedback(Component.literal(status()));
                                  return 1;
                                }))
                    .then(
                        ClientCommands.literal("probe")
                            .then(
                                ClientCommands.literal("status")
                                    .executes(
                                        context -> {
                                          context
                                              .getSource()
                                              .sendFeedback(
                                                  Component.literal(DisplayProbe.status()));
                                          return 1;
                                        }))
                            .then(
                                ClientCommands.argument("label", StringArgumentType.word())
                                    .executes(
                                        context -> {
                                          context
                                              .getSource()
                                              .sendFeedback(
                                                  Component.literal(
                                                      DisplayProbe.start(
                                                          Minecraft.getInstance(),
                                                          StringArgumentType.getString(
                                                              context, "label"))));
                                          return 1;
                                        })))
                    .then(
                        ClientCommands.literal("profile")
                            .then(
                                ClientCommands.literal("start")
                                    .executes(
                                        context -> {
                                          var metrics = RenderMetrics.getInstance();
                                          metrics.reset();
                                          metrics.start();
                                          context
                                              .getSource()
                                              .sendFeedback(
                                                  Component.literal(
                                                      "DrawLess CPU scopes enabled; use /drawless profile stop."));
                                          return 1;
                                        }))
                            .then(
                                ClientCommands.literal("stop")
                                    .executes(
                                        context -> {
                                          var metrics = RenderMetrics.getInstance();
                                          metrics.stop();
                                          var text =
                                              String.join("\n", metrics.buildReportLines(""));
                                          try {
                                            var path =
                                                Minecraft.getInstance()
                                                    .gameDirectory
                                                    .toPath()
                                                    .resolve("drawless-reports/cpu-scopes.txt");
                                            ru.wilyfox.drawless.util.AtomicFileWriter.write(
                                                path, w -> w.write(text));
                                            context
                                                .getSource()
                                                .sendFeedback(
                                                    Component.literal("CPU report saved: " + path));
                                          } catch (Exception error) {
                                            context
                                                .getSource()
                                                .sendError(
                                                    Component.literal(
                                                        "Could not save CPU report: "
                                                            + error.getMessage()));
                                          }
                                          return 1;
                                        })))));
    LOGGER.info("DrawLess initialized: {}", status());
  }

  public static String status() {
    return "DrawLess "
        + (DrawLessConfig.get().enabled ? "enabled" : "disabled")
        + "; Display "
        + ExtremeDisplayPerformance.diagnostic()
        + "; layers "
        + DisplayModelBudget.diagnostic()
        + "; text buffer reuse "
        + (ru.wilyfox.drawless.compat.RenderModSupport.IMMEDIATELY_FAST
            ? "disabled: ImmediatelyFast present"
            : DrawLessConfig.get().enabled && DrawLessConfig.get().textBufferReuse ? "on" : "off")
        + "; feature batching "
        + (ru.wilyfox.drawless.compat.RenderModSupport.IMMEDIATELY_FAST
            ? "disabled: ImmediatelyFast present"
            : ru.wilyfox.drawless.compat.RenderModSupport.IRIS
                ? "disabled: Iris present"
                : FeatureBatching.effectiveMode());
  }
}
