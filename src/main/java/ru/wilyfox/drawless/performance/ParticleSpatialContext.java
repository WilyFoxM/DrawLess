package ru.wilyfox.drawless.performance;

import java.util.Arrays;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.BlockGetter;
import ru.wilyfox.drawless.compat.RenderModSupport;
import ru.wilyfox.drawless.config.DrawLessConfig;

/**
 * Shared particle world/lifecycle ownership, with bounded lazy chunk lookups for collision proofs.
 */
public final class ParticleSpatialContext {
  private static final int CAPACITY = 512;
  private static final long[] KEYS = new long[CAPACITY];
  private static final int[] STAMPS = new int[CAPACITY];
  private static final BlockGetter[] CHUNKS = new BlockGetter[CAPACITY];
  private static Thread owner;
  private static ClientLevel world;
  private static int generation = 1;
  private static long lightGeneration = 1;
  private static long chunkQueries, chunkHits, lightInvalidations;

  private ParticleSpatialContext() {}

  public static void register() {
    owner = Thread.currentThread();
    ClientTickEvents.START_CLIENT_TICK.register(
        client -> {
          var config = DrawLessConfig.get();
          if (config.enabled
              && RenderModSupport.PARTICLE_CACHES
              && (config.particleLightCache || config.particleEmptyCollision)
              && bind(client.level)) {
            nextGeneration();
            lightGeneration++;
          } else clear();
        });
    ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
  }

  public static boolean bind(ClientLevel level) {
    if (Thread.currentThread() != owner || level == null || level.getClass() != ClientLevel.class)
      return false;
    if (world != level) {
      clear();
      world = level;
    }
    return true;
  }

  static boolean owns(ClientLevel level) {
    return Thread.currentThread() == owner && world == level;
  }

  public static boolean clientThread() {
    return Thread.currentThread() == owner;
  }

  public static boolean positionReuse(ClientLevel level) {
    var c = DrawLessConfig.get();
    return owns(level) && RenderModSupport.PARTICLE_CACHES && c.enabled && c.particleLightCache;
  }

  static BlockGetter chunk(ClientLevel level, int x, int z) {
    if (!owns(level)) return level.getChunkForCollisions(x, z);
    chunkQueries++;
    long key = net.minecraft.world.level.ChunkPos.pack(x, z);
    long mixed = key * 0x9e3779b97f4a7c15L;
    int slot =
        net.minecraft.util.Mth.murmurHash3Mixer((int) (mixed ^ (mixed >>> 32))) & (CAPACITY - 1);
    if (STAMPS[slot] == generation && KEYS[slot] == key) {
      chunkHits++;
      return CHUNKS[slot];
    }
    int before = generation;
    BlockGetter chunk = level.getChunkForCollisions(x, z);
    if (before == generation && owns(level)) {
      STAMPS[slot] = generation;
      KEYS[slot] = key;
      CHUNKS[slot] = chunk;
    }
    return chunk;
  }

  public static long lightGeneration() {
    return lightGeneration;
  }

  /** Published light storage, not the number of propagation operations, determines validity. */
  public static void lightPublished(Object chunkSource) {
    if (Thread.currentThread() == owner && world != null && world.getChunkSource() == chunkSource) {
      lightGeneration++;
      lightInvalidations++;
    }
  }

  public static void blocksChanged() {
    if (Thread.currentThread() != owner || world == null) return;
    lightGeneration++;
    ParticleCollisionCache.invalidate();
  }

  public static void chunksChanged() {
    if (Thread.currentThread() != owner || world == null) return;
    nextGeneration();
    Arrays.fill(CHUNKS, null);
    blocksChanged();
  }

  public static void clear() {
    if (owner != null && Thread.currentThread() != owner) return;
    if (world == null) return;
    ParticleCollisionCache.invalidate();
    world = null;
    Arrays.fill(CHUNKS, null);
    nextGeneration();
    lightGeneration++;
  }

  private static void nextGeneration() {
    if (++generation == 0) {
      Arrays.fill(STAMPS, 0);
      generation = 1;
    }
  }

  public static String diagnostic() {
    return "chunkQueriesTotal="
        + chunkQueries
        + "; chunkHitsTotal="
        + chunkHits
        + "; lightInvalidationsTotal="
        + lightInvalidations;
  }
}
