package ru.wilyfox.drawless.dev;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import ru.wilyfox.drawless.config.DrawLessConfig;
import ru.wilyfox.drawless.diagnostics.*;
import ru.wilyfox.drawless.observe.Observation;
import ru.wilyfox.drawless.performance.*;

/** Diagnostics add-on: connects the observer and adds the commands and reports. */
public final class DrawLessDev implements ClientModInitializer {
  @Override
  public void onInitializeClient() {
    Observation.install(new DiagnosticsObserver());
    DisplayProbe.register();
    LongRecording.register();
    DisplayCostProbe.register();
    BenchmarkSeries.register();
    PlayerProfile.register();
    ClientCommandRegistrationCallback.EVENT.register(
        (dispatcher, access) ->
            dispatcher.register(
                ClientCommands.literal("drawless")
                    .then(
                        ClientCommands.literal("status")
                            .executes(
                                context -> {
                                  context.getSource().sendFeedback(Component.literal(status()));
                                  return 1;
                                }))
                    .then(
                        ClientCommands.literal("record")
                            .then(
                                ClientCommands.literal("stop")
                                    .executes(
                                        context -> {
                                          context
                                              .getSource()
                                              .sendFeedback(
                                                  ru.wilyfox.drawless.diagnostics.LongRecording
                                                      .stop(context.getSource().getClient()));
                                          return 1;
                                        }))
                            .then(
                                ClientCommands.literal("status")
                                    .executes(
                                        context -> {
                                          context
                                              .getSource()
                                              .sendFeedback(
                                                  ru.wilyfox.drawless.diagnostics.LongRecording
                                                      .status());
                                          return 1;
                                        }))
                            .then(
                                ClientCommands.argument("label", StringArgumentType.word())
                                    .executes(
                                        context -> {
                                          context
                                              .getSource()
                                              .sendFeedback(
                                                  ru.wilyfox.drawless.diagnostics.LongRecording
                                                      .start(
                                                          context.getSource().getClient(),
                                                          StringArgumentType.getString(
                                                              context, "label")));
                                          return 1;
                                        })))
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
                                          if (BenchmarkSeries.running() || PlayerProfile.busy()) {
                                            context
                                                .getSource()
                                                .sendError(
                                                    Component.translatable(
                                                        "drawless.player_profile.busy"));
                                            return 0;
                                          }
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
                                          if (BenchmarkSeries.running() || PlayerProfile.busy()) {
                                            context
                                                .getSource()
                                                .sendError(
                                                    Component.translatable(
                                                        "drawless.player_profile.busy"));
                                            return 0;
                                          }
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
            : ru.wilyfox.drawless.compat.IrisShaders.packInUse()
                ? "disabled: Iris shader pack active"
                : FeatureBatching.effectiveMode());
  }
}
