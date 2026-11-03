package ru.wilyfox.drawless.util;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;

/**
 * The end of a play connection, delivered on the client thread. Fabric fires its event on the
 * network thread, where render-thread state and GPU resources must not be touched, and leaving a
 * world drops queued client tasks, so subscribers run at the start of the next client tick instead.
 */
public final class ClientDisconnect {
  private static final List<Consumer<Minecraft>> SUBSCRIBERS = new CopyOnWriteArrayList<>();
  private static volatile boolean pending;
  private static boolean registered;

  private ClientDisconnect() {}

  public static synchronized void subscribe(Consumer<Minecraft> subscriber) {
    SUBSCRIBERS.add(subscriber);
    if (registered) return;
    registered = true;
    ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> pending = true);
    ClientTickEvents.START_CLIENT_TICK.register(
        client -> {
          if (!pending) return;
          pending = false;
          for (var each : SUBSCRIBERS) each.accept(client);
        });
  }
}
