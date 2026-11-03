package ru.wilyfox.drawless.diagnostics;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.concurrent.CompletableFuture;

/**
 * CPU and GPU names for reports, so a series from another machine says where it was recorded. The
 * CPU name is read once in the background (the hardware query can take a moment); until it arrives
 * reports say "pending".
 */
final class Hardware {
  private static volatile String cpu = "pending";
  private static boolean requested;

  private Hardware() {}

  static synchronized void request() {
    if (requested) return;
    requested = true;
    CompletableFuture.runAsync(
        () -> {
          try {
            cpu =
                new oshi.SystemInfo()
                    .getHardware()
                    .getProcessor()
                    .getProcessorIdentifier()
                    .getName()
                    .trim();
          } catch (RuntimeException | LinkageError unavailable) {
            cpu = "unknown";
          }
        });
  }

  /** Render thread only (reads the GPU device). */
  static String describe() {
    request();
    var device = RenderSystem.getDevice().getDeviceInfo();
    return "CPU "
        + cpu
        + "; GPU "
        + device.name()
        + " ("
        + device.vendorName()
        + ", "
        + device.driverInfo()
        + ")";
  }
}
