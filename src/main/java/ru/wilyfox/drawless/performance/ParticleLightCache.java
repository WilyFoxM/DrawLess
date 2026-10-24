package ru.wilyfox.drawless.performance;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.Arrays;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndLightGetter;

/**
 * Exact base-light memoization with published-light and client-tick invalidation. Integrations
 * without temporal validity retain the pass-local path. No particle states are retained.
 */
public final class ParticleLightCache {
  private static final int CAPACITY = 8192;
  private static final long[] KEYS = new long[CAPACITY];
  private static final int[] VALUES = new int[CAPACITY], STAMPS = new int[CAPACITY];
  private static int generation;
  private static final long[] STORED_FRAMES = new long[CAPACITY];
  private static Thread owner;
  private static BlockAndLightGetter world;
  private static boolean active, lastEnabled;
  private static long queries, hits, framesTotal, queriesTotal, hitsTotal;
  private static long frameId,
      observedLightGeneration,
      crossFrameHits,
      crossFrameHitsTotal,
      conflicts,
      conflictsTotal;
  private static boolean persistent, wasPersistent;

  private ParticleLightCache() {}

  public static void beginFrame(boolean enabled) {
    beginFrame(enabled, null);
  }

  public static void beginFrame(boolean enabled, ClientLevel level) {
    if (!ParticleSpatialContext.clientThread()) return;
    active = lastEnabled = enabled;
    queries = hits = crossFrameHits = conflicts = 0;
    owner = enabled ? Thread.currentThread() : null;
    world = level;
    persistent =
        enabled
            && level != null
            && ParticleSpatialContext.bind(level)
            && ru.wilyfox.drawless.compat.RenderModSupport.TEMPORAL_PARTICLE_LIGHT;
    if (enabled) {
      frameId++;
      if (!persistent
          || !wasPersistent
          || observedLightGeneration != ParticleSpatialContext.lightGeneration()) nextGeneration();
      observedLightGeneration = ParticleSpatialContext.lightGeneration();
    }
    wasPersistent = persistent;
  }

  public static void endFrame() {
    if (!ParticleSpatialContext.clientThread()) return;
    if (active) {
      framesTotal++;
      queriesTotal += queries;
      hitsTotal += hits;
      crossFrameHitsTotal += crossFrameHits;
      conflictsTotal += conflicts;
    }
    active = false;
    owner = null;
    world = null;
  }

  public static int light(BlockAndLightGetter level, BlockPos pos, Operation<Integer> original) {
    if (!active || Thread.currentThread() != owner) return original.call(level, pos);
    if (persistent) {
      if (world != level || !ParticleSpatialContext.owns((ClientLevel) level))
        return original.call(level, pos);
      if (observedLightGeneration != ParticleSpatialContext.lightGeneration()) {
        observedLightGeneration = ParticleSpatialContext.lightGeneration();
        nextGeneration();
      }
    }
    if (world != level) {
      world = level;
      nextGeneration();
    }
    queries++;
    long key = pos.asLong();
    // BlockPos packing truncates coordinates beyond vanilla's range; never alias those positions.
    if (BlockPos.getX(key) != pos.getX()
        || BlockPos.getY(key) != pos.getY()
        || BlockPos.getZ(key) != pos.getZ()) return original.call(level, pos);
    int slot = net.minecraft.util.Mth.murmurHash3Mixer((int) (key ^ (key >>> 32))) & (CAPACITY - 1);
    if (STAMPS[slot] == generation && KEYS[slot] == key) {
      hits++;
      if (STORED_FRAMES[slot] != frameId) crossFrameHits++;
      return VALUES[slot];
    }
    if (STAMPS[slot] == generation) conflicts++;
    int before = generation;
    long lightBefore = ParticleSpatialContext.lightGeneration();
    int value = original.call(level, pos);
    if (before == generation
        && world == level
        && (!persistent || lightBefore == ParticleSpatialContext.lightGeneration())) {
      KEYS[slot] = key;
      VALUES[slot] = value;
      STAMPS[slot] = generation;
      STORED_FRAMES[slot] = frameId;
    }
    return value;
  }

  private static void nextGeneration() {
    if (++generation == 0) {
      Arrays.fill(STAMPS, 0);
      generation = 1;
    }
  }

  /** Called only when a diagnostic sample is requested, never for each particle. */
  public static String diagnostic() {
    return "enabled="
        + lastEnabled
        + "; queries="
        + queries
        + "; hits="
        + hits
        + "; vanillaLookups="
        + (queries - hits)
        + "; framesTotal="
        + framesTotal
        + "; queriesTotal="
        + queriesTotal
        + "; hitsTotal="
        + hitsTotal
        + "; "
        + reuseDiagnostic();
  }

  public static String reuseDiagnostic() {
    return "mode="
        + (!lastEnabled ? "off" : wasPersistent ? "tick_local" : "pass_local")
        + "; crossFrameHits="
        + crossFrameHits
        + "; crossFrameHitsTotal="
        + crossFrameHitsTotal
        + "; conflictsTotal="
        + conflictsTotal
        + "; "
        + ParticleSpatialContext.diagnostic();
  }
}
