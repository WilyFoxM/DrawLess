package ru.wilyfox.drawless.performance;

import java.util.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import ru.wilyfox.drawless.config.DrawLessConfig;
import ru.wilyfox.drawless.diagnostics.RenderMetrics;

/**
 * Aggressive render-only pose decimation. Cached poses submit every frame; game/network ticks
 * remain live.
 */
public final class NpcFrameCache {
  private static final int MAX_PARTS = 4096;
  private static final IdentityHashMap<Display.ItemDisplay, Part> parts = new IdentityHashMap<>();
  private static final LinkedHashMap<Object, Group> groups = new LinkedHashMap<>();
  private static final HashSet<UUID> engineRoots = new HashSet<>();
  private static final ArrayList<Group> active = new ArrayList<>();
  private static ClientLevel world;
  private static boolean enabled, registered;
  private static long frame, started, previous, selectNanos, reused, updated, mandatory;
  private static double interval = 1e9 / 60, maxAgeMs;
  private static int cursor, quota;

  private NpcFrameCache() {}

  public static void register() {
    if (registered) return;
    registered = true;
    ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> parts.remove(entity));
    ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
  }

  public static void clear() {
    parts.clear();
    groups.clear();
    engineRoots.clear();
    active.clear();
    world = null;
    enabled = false;
    frame = started = previous = selectNanos = reused = updated = mandatory = 0;
    cursor = quota = 0;
    maxAgeMs = 0;
    interval = 1e9 / 60;
  }

  public static void beginFrame(Minecraft client) {
    if (!DrawLessConfig.get().enabled
        || !DrawLessConfig.get().modelEngineNpcBudget
        || ExtremeDisplayPerformance.requested()
        || client.level == null) {
      if (enabled || !parts.isEmpty()) clear();
      return;
    }
    if (world != client.level) clear();
    world = client.level;
    enabled = true;
    frame++;
    started = System.nanoTime();
    if (previous != 0)
      interval = interval * .9 + Math.min(250_000_000, Math.max(1, started - previous)) * .1;
    previous = started;
    reused = updated = mandatory = 0;
    maxAgeMs = 0;
    int configured = Math.clamp(DrawLessConfig.get().npcGroupsPerFrame, 1, 100);
    quota =
        DrawLessConfig.get().adaptiveNpcBudget
            ? Math.max(1, (int) Math.floor(configured * Math.min(1, (1e9 / interval) / 60)))
            : configured;
    try (var ignored = RenderMetrics.getInstance().scope("render/npcBudget/select")) {
      engineRoots.clear();
      active.clear();
      for (var entity : world.entitiesForRendering())
        if (entity instanceof Display.ItemDisplay item && !item.isRemoved() && enginePart(item)) {
          var root = item.getRootVehicle();
          if (root != item && !(root instanceof Player)) engineRoots.add(root.getUUID());
        }
      for (var entity : world.entitiesForRendering())
        if (entity instanceof Display.ItemDisplay item && !item.isRemoved()) {
          var root = item.getRootVehicle();
          if (root instanceof Player
              || item.getItemStack().isEmpty()
              || !enginePart(item) && !engineRoots.contains(root.getUUID())) continue;
          var part = parts.get(item);
          if (part == null) {
            if (parts.size() >= MAX_PARTS) continue;
            part = new Part();
            parts.put(item, part);
          }
          Object key = part.key(item, root);
          var group = groups.computeIfAbsent(key, Group::new);
          if (part.group != group) {
            part.cached = null;
            part.group = group;
          }
          if (group.seen != frame) {
            group.seen = frame;
            group.members = 0;
            group.admitted = false;
          }
          group.members++;
          part.seen = frame;
        }
      parts.values().removeIf(part -> part.seen != frame);
      groups.values().removeIf(group -> group.seen != frame);
      active.addAll(groups.values());
      if (!active.isEmpty()) {
        cursor %= active.size();
        for (int i = 0; i < Math.min(quota, active.size()); i++)
          active.get((cursor + i) % active.size()).admitted = true;
        cursor = (cursor + quota) % active.size();
      }
    } finally {
      selectNanos = System.nanoTime() - started;
    }
  }

  private static boolean enginePart(Display.ItemDisplay item) {
    var stack = item.getItemStack();
    var id = stack.get(DataComponents.ITEM_MODEL);
    return id != null && id.getNamespace().startsWith("modelengine")
        || stack.is(Items.BONE)
            && (stack.has(DataComponents.CUSTOM_MODEL_DATA)
                || id != null && !id.equals(Identifier.withDefaultNamespace("bone")));
  }

  public static EntityRenderState reuse(Entity entity) {
    if (!enabled
        || !DrawLessConfig.get().modelEngineNpcBudget
        || entity.level() != world
        || entity.isRemoved()
        || !(entity instanceof Display.ItemDisplay item)) return null;
    var part = parts.get(item);
    if (part == null || part.seen != frame) return null;
    if (part.cached == null) {
      if (!part.group.admitted) {
        part.group.admitted = true;
        mandatory++;
      }
      return null;
    }
    if (part.group.admitted) return null;
    reused++;
    maxAgeMs = Math.max(maxAgeMs, (started - part.updatedAt) / 1e6);
    return part.cached;
  }

  public static void capture(Entity entity, EntityRenderState state) {
    if (!enabled || entity.level() != world || !(entity instanceof Display.ItemDisplay item))
      return;
    var part = parts.get(item);
    if (part == null || part.seen != frame || part.cached == state) return;
    try (var ignored = RenderMetrics.getInstance().scope("render/npcBudget/freeze")) {
      if (state
              instanceof
              net.minecraft.client.renderer.entity.state.ItemDisplayEntityRenderState pose
          && pose.renderState != null) {
        // Evaluate once, so cached draws do not interpolate the same transformation repeatedly.
        var render = pose.renderState;
        float progress = pose.interpolationProgress;
        pose.renderState =
            new Display.RenderState(
                Display.GenericInterpolator.constant(render.transformation().get(progress)),
                render.billboardConstraints(),
                render.brightnessOverride(),
                Display.FloatInterpolator.constant(render.shadowRadius().get(progress)),
                Display.FloatInterpolator.constant(render.shadowStrength().get(progress)),
                render.glowColorOverride());
        pose.interpolationProgress = 0;
      }
      part.cached = state;
      part.updatedAt = started;
      updated++;
    }
  }

  public static void endFrame() {
    if (!enabled) return;
    var p = RenderMetrics.getInstance();
    if (!p.isEnabled()) return;
    p.incrementCounter("npcBudget/reusedParts", reused);
    p.incrementCounter("npcBudget/updatedParts", updated);
    p.incrementCounter("npcBudget/groups", active.size());
    p.incrementCounter("npcBudget/mandatoryGroups", mandatory);
  }

  public static String diagnostic() {
    return enabled
        ? String.format(
            Locale.ROOT,
            "on; groups=%d; quota=%d; updatedParts=%d; reusedParts=%d; mandatoryGroups=%d; maxPoseAge=%.1fms; selection=%.3fms",
            active.size(),
            quota,
            updated,
            reused,
            mandatory,
            maxAgeMs,
            selectNanos / 1e6)
        : "off";
  }

  public static String groupDiagnostic() {
    if (!enabled) return "NPC pose budget is off.\n";
    var result =
        new StringBuilder(
            "| Group key (root UUID or approximate cell) | Parts | Part UUIDs (up to 32) |\n| --- | ---: | --- |\n");
    active.stream()
        .sorted(Comparator.comparingInt((Group g) -> g.members).reversed())
        .limit(16)
        .forEach(
            group -> {
              var ids = new StringJoiner(", ");
              int count = 0;
              for (var entry : parts.entrySet())
                if (entry.getValue().group == group && count++ < 32)
                  ids.add(entry.getKey().getUUID().toString());
              result
                  .append("| ")
                  .append(group.key)
                  .append(" | ")
                  .append(group.members)
                  .append(" | ")
                  .append(ids)
                  .append(" |\n");
            });
    return result.toString();
  }

  private static final class Group {
    final Object key;
    long seen;
    int members;
    boolean admitted;

    Group(Object key) {
      this.key = key;
    }
  }

  private record Cell(String family, int x, int y, int z) {}

  private static final class Part {
    Group group;
    EntityRenderState cached;
    long seen, updatedAt;
    Identifier model;
    String family;
    Cell cell;

    Object key(Display.ItemDisplay item, Entity root) {
      if (root != item) return root.getUUID();
      var current = item.getItemStack().get(DataComponents.ITEM_MODEL);
      if (!Objects.equals(model, current) || family == null) {
        model = current;
        String path = current == null ? "legacy-bone" : current.getPath();
        int slash = path.indexOf('/');
        family =
            current != null
                ? current.getNamespace() + ":" + (slash < 0 ? path : path.substring(0, slash))
                : "legacy-bone";
        cell = null;
      }
      int x = (int) Math.floor(item.getX()),
          y = (int) Math.floor(item.getY()),
          z = (int) Math.floor(item.getZ());
      if (cell == null || cell.x != x || cell.y != y || cell.z != z)
        cell = new Cell(family, x, y, z);
      return cell;
    }
  }
}
