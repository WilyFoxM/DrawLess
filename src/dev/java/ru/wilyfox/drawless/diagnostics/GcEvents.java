package ru.wilyfox.drawless.diagnostics;

import com.sun.management.GarbageCollectionNotificationInfo;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;

/**
 * The JVM's garbage collections for diagnostic sessions. Notifications arrive on a JMX thread and
 * only go to subscriber queues. Times are JVM uptime milliseconds, the resolution the JVM reports.
 */
final class GcEvents {
  record Event(String name, boolean pause, long startMs, long endMs, long durationMs) {}

  private static final List<Queue<Event>> SUBSCRIBERS = new CopyOnWriteArrayList<>();
  private static boolean listening;

  private GcEvents() {}

  static Queue<Event> subscribe() {
    listen();
    Queue<Event> queue = new ConcurrentLinkedQueue<>();
    SUBSCRIBERS.add(queue);
    return queue;
  }

  static void unsubscribe(Queue<Event> queue) {
    SUBSCRIBERS.remove(queue);
  }

  static List<String> collectors() {
    return ManagementFactory.getGarbageCollectorMXBeans().stream().map(b -> b.getName()).toList();
  }

  /** Concurrent cycles (G1 Concurrent GC, ZGC/Shenandoah Cycles) do not stop the render thread. */
  static boolean pause(String collector) {
    return !collector.contains("Concurrent") && !collector.contains("Cycles");
  }

  private static synchronized void listen() {
    if (listening) return;
    listening = true;
    for (var bean : ManagementFactory.getGarbageCollectorMXBeans()) {
      if (!(bean instanceof NotificationEmitter emitter)) continue;
      emitter.addNotificationListener(
          (notification, handback) -> {
            if (SUBSCRIBERS.isEmpty()
                || !notification
                    .getType()
                    .equals(GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION))
              return;
            var info =
                GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
            var gc = info.getGcInfo();
            var event =
                new Event(
                    info.getGcName(),
                    pause(info.getGcName()),
                    gc.getStartTime(),
                    gc.getEndTime(),
                    gc.getDuration());
            for (var queue : SUBSCRIBERS) queue.add(event);
          },
          null,
          null);
    }
  }
}
