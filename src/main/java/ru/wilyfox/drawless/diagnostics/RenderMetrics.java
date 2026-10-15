package ru.wilyfox.drawless.diagnostics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Explicit opt-in CPU measurements. No clocks, collections or objects in disabled scopes. */
public final class RenderMetrics {
    private static final RenderMetrics INSTANCE = new RenderMetrics();
    private static final Scope NOOP = new Scope(null, null, 0);
    private final Map<String, Totals> sections = new LinkedHashMap<>();
    private final Map<String, Long> counters = new LinkedHashMap<>();
    private boolean enabled;
    private long generation;
    public static RenderMetrics getInstance() { return INSTANCE; }
    public boolean isEnabled() { return enabled; }
    public void start() { enabled = true; generation++; }
    public void stop() { enabled = false; generation++; }
    public void reset() { stop(); sections.clear(); counters.clear(); }
    public Scope scope(String name) {
        if (!enabled) return NOOP;
        if (!sections.containsKey(name) && sections.size() >= 256) return NOOP;
        return new Scope(this, name, generation);
    }
    public void incrementCounter(String name, long amount) {
        if (enabled && (counters.containsKey(name) || counters.size() < 512)) counters.merge(name, amount, Long::sum);
    }
    public void recordClientEvent(String name, String detail) { incrementCounter("event/" + name, 1); }
    public List<String> buildReportLines(String prefix) {
        var lines = new ArrayList<String>();
        lines.add("CPU inclusive totals; nested scopes must not be added together. GPU time is not measured.");
        sections.forEach((name,t) -> { if (name.startsWith(prefix)) lines.add(String.format(Locale.ROOT,
                "%s: calls=%d; totalMs=%.5f; maxMs=%.5f", name,t.calls,t.nanos/1e6,t.max/1e6)); });
        counters.forEach((name,value) -> lines.add(name + "=" + value));
        return lines;
    }
    private static final class Totals { long calls, nanos, max; }
    public static final class Scope implements AutoCloseable {
        private final RenderMetrics metrics;
        private final String name;
        private final long started, generation;
        private boolean closed;
        private Scope(RenderMetrics metrics, String name, long generation) {
            this.metrics=metrics; this.name=name; this.generation=generation;
            started=metrics==null?0:System.nanoTime();
        }
        @Override public void close() {
            if (metrics==null || closed) return;
            closed=true;
            if (!metrics.enabled || generation!=metrics.generation) return;
            if (!metrics.sections.containsKey(name) && metrics.sections.size()>=256) return;
            long elapsed=System.nanoTime()-started;
            var totals=metrics.sections.computeIfAbsent(name, ignored->new Totals());
            totals.calls++; totals.nanos+=elapsed; totals.max=Math.max(totals.max,elapsed);
        }
    }
}
