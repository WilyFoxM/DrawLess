package ru.wilyfox.drawless.diagnostics;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import ru.wilyfox.drawless.performance.FrameWork;

/**
 * Every measured frame of one probe phase: its interval, render-thread allocation, rendered avatars
 * and DrawLess first-time work, plus the JVM's garbage collections during the phase. Frames are
 * recorded on the render thread; GC notifications arrive on a JMX thread and only go to a queue.
 */
final class FrameRecorder {
  private static final int MAX_FRAMES = 200_000;
  private static final int SLOWEST = 10;
  private static final com.sun.management.ThreadMXBean THREADS = threads();
  private static int pendingTicks;

  private final Queue<GcEvents.Event> collections = GcEvents.subscribe();
  private final long uptimeOffset;
  private final long start;
  private long[] ends = new long[8192], intervals = new long[8192], allocated = new long[8192];
  private long[] work = new long[8192];
  private int[] avatars = new int[8192];
  private byte[] ticks = new byte[8192];
  private final Map<Integer, String> workText = new HashMap<>();
  private int count;
  private long lastAllocated;

  record Collector(String name, boolean pause, int count, double totalMs, double maxMs) {}

  record SlowFrame(
      double seconds,
      double ms,
      double timesMedian,
      double gcPauseMs,
      double allocKb,
      int avatars,
      int avatarDelta,
      int clientTicks,
      String drawlessWork) {}

  record Summary(
      FrameTail tail,
      FrameTail tickFrames,
      FrameTail otherFrames,
      int slowWithTick,
      List<Collector> gc,
      int framesWithGcPause,
      double gcPauseMsInFrames,
      double renderAllocKbPerFrame,
      double renderAllocMbPerSecond,
      int framesWithDrawlessWork,
      double drawlessWorkMs,
      int slowWithGcPause,
      int slowWithDrawlessWork,
      int slowWithAvatarChange,
      List<SlowFrame> slowest) {
    static final Summary EMPTY =
        new Summary(
            FrameTail.EMPTY,
            FrameTail.EMPTY,
            FrameTail.EMPTY,
            0,
            List.of(),
            0,
            0,
            -1,
            -1,
            0,
            0,
            0,
            0,
            0,
            List.of());
  }

  /** Starts recording; {@code now} is the first measured frame's timestamp. */
  FrameRecorder(long now) {
    start = now;
    uptimeOffset = now - ManagementFactory.getRuntimeMXBean().getUptime() * 1_000_000L;
    lastAllocated = allocated();
    FrameWork.reset();
  }

  /** A client tick ran; the next recorded frame contains it. */
  static void noteTick() {
    pendingTicks++;
  }

  /** One measured frame ending at {@code now}; interval 0 marks the first frame after a gap. */
  void frame(long now, long interval, int avatarCount) {
    int frameTicks = pendingTicks;
    pendingTicks = 0;
    long bytes = allocated();
    long frameAllocated = lastAllocated >= 0 && bytes >= 0 ? bytes - lastAllocated : -1;
    lastAllocated = bytes;
    long workNanos = FrameWork.pendingNanos();
    String text = workNanos > 0 ? FrameWork.drain() : "";
    if (interval <= 0 || count >= MAX_FRAMES) return;
    if (count == ends.length) grow();
    ends[count] = now;
    intervals[count] = interval;
    allocated[count] = frameAllocated;
    avatars[count] = avatarCount;
    ticks[count] = (byte) Math.min(frameTicks, 127);
    work[count] = workNanos;
    if (!text.isEmpty()) workText.put(count, text);
    count++;
  }

  Summary finish() {
    GcEvents.unsubscribe(collections);
    if (count == 0) return Summary.EMPTY;
    var tail = FrameTail.of(intervals, count);
    var gcs = new ArrayList<>(collections);
    double[] gcInFrame = new double[count];
    var byName = new LinkedHashMap<String, double[]>();
    var pauseByName = new HashMap<String, Boolean>();
    for (var gc : gcs) {
      var totals = byName.computeIfAbsent(gc.name(), k -> new double[3]);
      totals[0]++;
      totals[1] += gc.durationMs();
      totals[2] = Math.max(totals[2], gc.durationMs());
      pauseByName.put(gc.name(), gc.pause());
      if (!gc.pause()) continue;
      long gcStart = uptimeOffset + gc.startMs() * 1_000_000L;
      long gcEnd = uptimeOffset + gc.endMs() * 1_000_000L;
      for (int i = 0; i < count; i++) {
        long frameStart = ends[i] - intervals[i];
        long overlap = Math.min(ends[i], gcEnd) - Math.max(frameStart, gcStart);
        if (overlap > 0) gcInFrame[i] += overlap / 1e6;
      }
    }
    var collectors = new ArrayList<Collector>();
    byName.forEach(
        (name, t) ->
            collectors.add(new Collector(name, pauseByName.get(name), (int) t[0], t[1], t[2])));
    int withGc = 0, withWork = 0;
    double gcMs = 0, workMs = 0;
    long allocSum = 0;
    int allocFrames = 0;
    for (int i = 0; i < count; i++) {
      if (gcInFrame[i] > 0) {
        withGc++;
        gcMs += gcInFrame[i];
      }
      if (work[i] > 0) {
        withWork++;
        workMs += work[i] / 1e6;
      }
      if (allocated[i] >= 0) {
        allocSum += allocated[i];
        allocFrames++;
      }
    }
    double seconds = (ends[count - 1] - (ends[0] - intervals[0])) / 1e9;
    double slowLimit = 2 * tail.medianMs();
    int slowGc = 0, slowWork = 0, slowAvatars = 0, slowTick = 0;
    long[] tickIntervals = new long[count], otherIntervals = new long[count];
    int tickCount = 0, otherCount = 0;
    Integer[] order = new Integer[count];
    for (int i = 0; i < count; i++) {
      order[i] = i;
      if (ticks[i] > 0) tickIntervals[tickCount++] = intervals[i];
      else otherIntervals[otherCount++] = intervals[i];
      if (intervals[i] / 1e6 < slowLimit) continue;
      if (ticks[i] > 0) slowTick++;
      if (gcInFrame[i] > 0) slowGc++;
      if (work[i] > 0) slowWork++;
      if (i > 0 && avatars[i] != avatars[i - 1]) slowAvatars++;
    }
    Arrays.sort(order, Comparator.comparingLong((Integer i) -> intervals[i]).reversed());
    var slowest = new ArrayList<SlowFrame>();
    for (int k = 0; k < Math.min(SLOWEST, count); k++) {
      int i = order[k];
      double ms = intervals[i] / 1e6;
      slowest.add(
          new SlowFrame(
              (ends[i] - start) / 1e9,
              ms,
              tail.medianMs() > 0 ? ms / tail.medianMs() : 0,
              gcInFrame[i],
              allocated[i] >= 0 ? allocated[i] / 1024.0 : -1,
              avatars[i],
              i > 0 ? avatars[i] - avatars[i - 1] : 0,
              ticks[i],
              workText.getOrDefault(i, "")));
    }
    return new Summary(
        tail,
        FrameTail.of(tickIntervals, tickCount),
        FrameTail.of(otherIntervals, otherCount),
        slowTick,
        List.copyOf(collectors),
        withGc,
        gcMs,
        allocFrames > 0 ? allocSum / 1024.0 / allocFrames : -1,
        allocFrames > 0 && seconds > 0 ? allocSum / 1048576.0 / seconds : -1,
        withWork,
        workMs,
        slowGc,
        slowWork,
        slowAvatars,
        List.copyOf(slowest));
  }

  static String markdown(Summary s) {
    var t = s.tail();
    var out = new StringBuilder("\n## Frame time distribution\n\n");
    if (t.frames() == 0) return out.append("No frames were measured in this phase.\n").toString();
    out.append(
        "Every measured frame interval between level renders on the render thread (CPU-side wall time; GPU queue and presentation are not separated). Percentiles use the nearest rank; 1% low is the FPS of the 99th percentile frame time. A slow frame takes at least twice the phase median. GC pauses come from the JVM's collectors with millisecond resolution and are matched to the frames they overlap, so shorter pauses (usual for ZGC) appear as 0; concurrent collector cycles are listed but not counted as pauses and can still take CPU from the game. Allocation covers the render thread only. DrawLess first-time work is mesh building, skin admission and mesh compaction by DrawLess itself; chunk building, networking and other mods are not attributed.\n\n");
    out.append(
        "| Frames | Median ms | p95 ms | p99 ms | p99.9 ms | Max ms | 1% low FPS | Slow frames (≥2× median) | Frames ≥33.3 ms |\n| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
    out.append(
        String.format(
            Locale.ROOT,
            "| %d | %.3f | %.3f | %.3f | %.3f | %.3f | %.1f | %d | %d |\n\n",
            t.frames(),
            t.medianMs(),
            t.p95Ms(),
            t.p99Ms(),
            t.p999Ms(),
            t.maxMs(),
            t.low1Fps(),
            t.slowFrames(),
            t.over33Ms()));
    out.append(
        String.format(
            Locale.ROOT,
            "Render-thread allocation: %s per frame, %s. GC pauses overlapped %d frames (%.2f ms in total). DrawLess first-time work ran in %d frames (%.2f ms in total).\n\n",
            s.renderAllocKbPerFrame() >= 0
                ? String.format(Locale.ROOT, "%.1f KiB", s.renderAllocKbPerFrame())
                : "n/a",
            s.renderAllocMbPerSecond() >= 0
                ? String.format(Locale.ROOT, "%.1f MiB/s", s.renderAllocMbPerSecond())
                : "n/a",
            s.framesWithGcPause(),
            s.gcPauseMsInFrames(),
            s.framesWithDrawlessWork(),
            s.drawlessWorkMs()));
    out.append(
        String.format(
            Locale.ROOT,
            "Frames containing a client tick: %d, median %.3f ms, p99 %.3f ms. Other frames: %d, median %.3f ms, p99 %.3f ms.\n\nOf %d slow frames: %d contained a client tick, %d overlapped a GC pause, %d had DrawLess first-time work, %d rendered a different number of avatars than the frame before.\n\n",
            s.tickFrames().frames(),
            s.tickFrames().medianMs(),
            s.tickFrames().p99Ms(),
            s.otherFrames().frames(),
            s.otherFrames().medianMs(),
            s.otherFrames().p99Ms(),
            t.slowFrames(),
            s.slowWithTick(),
            s.slowWithGcPause(),
            s.slowWithDrawlessWork(),
            s.slowWithAvatarChange()));
    if (!s.gc().isEmpty()) {
      out.append(
          "| Collector | Kind | Collections | Total ms | Max ms |\n| --- | --- | ---: | ---: | ---: |\n");
      for (var c : s.gc())
        out.append(
            String.format(
                Locale.ROOT,
                "| %s | %s | %d | %.2f | %.2f |\n",
                c.name(),
                c.pause() ? "pause" : "concurrent",
                c.count(),
                c.totalMs(),
                c.maxMs()));
      out.append('\n');
    } else out.append("No garbage collection was reported during the phase.\n\n");
    out.append(
        "Slowest frames:\n\n| s | Frame ms | × median | Client ticks | GC pause ms | Render alloc KiB | Avatars (Δ) | DrawLess first-time work |\n| ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |\n");
    for (var f : s.slowest())
      out.append(
          String.format(
              Locale.ROOT,
              "| %.3f | %.3f | %.1f | %d | %.2f | %s | %d (%+d) | %s |\n",
              f.seconds(),
              f.ms(),
              f.timesMedian(),
              f.clientTicks(),
              f.gcPauseMs(),
              f.allocKb() >= 0 ? String.format(Locale.ROOT, "%.1f", f.allocKb()) : "n/a",
              f.avatars(),
              f.avatarDelta(),
              f.drawlessWork().isEmpty() ? "–" : f.drawlessWork()));
    return out.toString();
  }

  private void grow() {
    int size = Math.min(MAX_FRAMES, ends.length * 2);
    ends = Arrays.copyOf(ends, size);
    intervals = Arrays.copyOf(intervals, size);
    allocated = Arrays.copyOf(allocated, size);
    work = Arrays.copyOf(work, size);
    avatars = Arrays.copyOf(avatars, size);
    ticks = Arrays.copyOf(ticks, size);
  }

  static long allocated() {
    return THREADS != null ? THREADS.getCurrentThreadAllocatedBytes() : -1;
  }

  private static com.sun.management.ThreadMXBean threads() {
    if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean
        && bean.isThreadAllocatedMemorySupported()) {
      if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
      return bean;
    }
    return null;
  }
}
