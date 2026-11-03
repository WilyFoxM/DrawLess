package ru.wilyfox.drawless.diagnostics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Bounded render-thread timing tree. Self time excludes measured children, not observer overhead.
 */
public final class ProfileTimings {
  private static final int LIMIT = 384, DEPTH = 96;
  private final LongSupplier clock;
  private final Map<String, Node> nodes = new LinkedHashMap<>();
  private final Node[] stack = new Node[DEPTH];
  private final long[] starts = new long[DEPTH],
      children = new long[DEPTH],
      handles = new long[DEPTH];
  private int depth;
  private long serial, bookkeeping, overflow;
  private boolean active, valid;

  public ProfileTimings() {
    this(System::nanoTime);
  }

  ProfileTimings(LongSupplier clock) {
    this.clock = clock;
  }

  public void beginFrame() {
    depth = 0;
    active = valid = true;
  }

  public long begin(String stage) {
    if (!active || !valid) return 0;
    long before = clock.getAsLong();
    if (depth == DEPTH) {
      valid = false;
      overflow++;
      return 0;
    }
    String path = depth == 0 ? stage : stack[depth - 1].path + "/" + stage;
    Node node = nodes.get(path);
    if (node == null) {
      if (nodes.size() == LIMIT) {
        overflow++;
        return 0;
      }
      node = new Node(path);
      nodes.put(path, node);
    }
    stack[depth] = node;
    children[depth] = 0;
    handles[depth] = ++serial;
    long started = clock.getAsLong();
    starts[depth++] = started;
    bookkeeping += started - before;
    return serial;
  }

  public void end(long handle) {
    if (handle == 0 || !active || !valid) return;
    long ended = clock.getAsLong();
    if (depth == 0 || handles[depth - 1] != handle) {
      valid = false;
      return;
    }
    int i = --depth;
    long elapsed = Math.max(0, ended - starts[i]);
    Node node = stack[i];
    node.calls++;
    node.nanos += elapsed;
    node.self += Math.max(0, elapsed - children[i]);
    node.max = Math.max(node.max, elapsed);
    stack[i] = null;
    if (depth != 0) children[depth - 1] += elapsed;
    bookkeeping += clock.getAsLong() - ended;
  }

  public boolean endFrame() {
    boolean result = active && valid && depth == 0;
    active = false;
    java.util.Arrays.fill(stack, null);
    depth = 0;
    return result;
  }

  public long rootNanos() {
    Node node = nodes.get("frame");
    return node == null ? 0 : node.nanos;
  }

  public long bookkeepingNanos() {
    return bookkeeping;
  }

  public long overflow() {
    return overflow;
  }

  public List<Map<String, Object>> snapshot(int frames, double intervalNanos) {
    var result = new ArrayList<Map<String, Object>>();
    for (Node n : nodes.values()) {
      var row = new LinkedHashMap<String, Object>();
      row.put("path", n.path);
      row.put("calls", n.calls);
      row.put("inclusive_ms", n.nanos / 1e6);
      row.put("self_ms", n.self / 1e6);
      row.put("max_call_ms", n.max / 1e6);
      row.put("ms_per_sampled_frame", frames == 0 ? 0 : n.nanos / 1e6 / frames);
      row.put("percent_sampled_render_frame", rootNanos() == 0 ? 0 : 100.0 * n.nanos / rootNanos());
      row.put(
          "percent_sampled_frame_interval",
          intervalNanos > 0 ? 100.0 * n.nanos / intervalNanos : null);
      result.add(row);
    }
    return result;
  }

  private static final class Node {
    final String path;
    long calls, nanos, self, max;

    Node(String path) {
      this.path = path;
    }
  }
}
