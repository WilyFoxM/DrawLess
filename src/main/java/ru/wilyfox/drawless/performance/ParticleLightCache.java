package ru.wilyfox.drawless.performance;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndLightGetter;

/**
 * Exact block-position memoization for one particle extraction pass. No particle states are
 * retained.
 */
public final class ParticleLightCache {
  private static final int CAPACITY = 8192;
  private static final long[] KEYS = new long[CAPACITY];
  private static final int[] VALUES = new int[CAPACITY], STAMPS = new int[CAPACITY];
  private static int generation;
  private static Thread owner;
  private static BlockAndLightGetter world;
  private static boolean active, lastEnabled;
  private static long queries, hits, framesTotal, queriesTotal, hitsTotal;

  private ParticleLightCache() {}

  public static void beginFrame(boolean enabled) {
    active = lastEnabled = enabled;
    queries = hits = 0;
    owner = enabled ? Thread.currentThread() : null;
    world = null;
    if (enabled) nextGeneration();
  }

  public static void endFrame() {
    if (active) {
      framesTotal++;
      queriesTotal += queries;
      hitsTotal += hits;
    }
    active = false;
    owner = null;
    world = null;
  }

  public static int light(BlockAndLightGetter level, BlockPos pos, Operation<Integer> original) {
    if (!active || Thread.currentThread() != owner) return original.call(level, pos);
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
      return VALUES[slot];
    }
    int value = original.call(level, pos);
    KEYS[slot] = key;
    VALUES[slot] = value;
    STAMPS[slot] = generation;
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
        + hitsTotal;
  }
}
