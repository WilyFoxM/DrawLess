package ru.wilyfox.drawless.performance;

import java.util.Arrays;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import ru.wilyfox.drawless.compat.RenderModSupport;
import ru.wilyfox.drawless.config.DrawLessConfig;

/**
 * Shared particle world/lifecycle ownership. Bounded chunk and block facts serve collision scans;
 * lighting shares lifecycle validity while retaining its separate light results.
 */
public final class ParticleSpatialContext {
  private static final int CAPACITY = 512;
  private static final long[] KEYS = new long[CAPACITY];
  private static final int[] STAMPS = new int[CAPACITY];
  private static final BlockGetter[] CHUNKS = new BlockGetter[CAPACITY];
  private static final int BLOCK_CAPACITY = 4096;
  private static final long[] BLOCK_KEYS = new long[BLOCK_CAPACITY];
  private static final int[] BLOCK_STAMPS = new int[BLOCK_CAPACITY];
  private static final BlockState[] BLOCKS = new BlockState[BLOCK_CAPACITY];
  private static int blockGeneration = 1;
  private static Thread owner;
  private static ClientLevel world;
  private static int generation = 1;
  private static long lightGeneration = 1;
  private static long chunkQueries, chunkHits, lightInvalidations;
  private static long blockQueries, blockHits, blockReads;

  private ParticleSpatialContext() {}

  public static void register() {
    owner = Thread.currentThread();
    ClientTickEvents.START_CLIENT_TICK.register(client -> beginTick(client.level));
    ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
  }

  static void beginTick(ClientLevel level) {
    if (!clientThread()) return;
    long start = ParticleWorkProbe.begin();
    try {
      var config = DrawLessConfig.get();
      if (config.enabled
          && RenderModSupport.PARTICLE_CACHES
          && (config.particleLightCache || config.particleEmptyCollision)
          && bind(level)) {
        nextGeneration();
        nextBlockGeneration();
        lightChanged();
      } else clear();
    } finally {
      ParticleWorkProbe.contextEnd(start);
    }
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

  /**
   * Tick-local facts for a chunk obtained from chunk() in this active world. Context-dependent
   * collision shapes are deliberately not cached.
   */
  static BlockState blockState(ClientLevel level, BlockGetter chunk, BlockPos pos) {
    if (!owns(level)) return chunk.getBlockState(pos);
    blockQueries++;
    long key = pos.asLong();
    if (chunk.getClass() != LevelChunk.class
        || BlockPos.getX(key) != pos.getX()
        || BlockPos.getY(key) != pos.getY()
        || BlockPos.getZ(key) != pos.getZ()) {
      blockReads++;
      return chunk.getBlockState(pos);
    }
    int slot =
        net.minecraft.util.Mth.murmurHash3Mixer((int) (key ^ key >>> 32)) & (BLOCK_CAPACITY - 1);
    if (BLOCK_STAMPS[slot] == blockGeneration && BLOCK_KEYS[slot] == key) {
      blockHits++;
      return BLOCKS[slot];
    }
    int before = blockGeneration;
    blockReads++;
    var state = chunk.getBlockState(pos);
    if (before == blockGeneration && owns(level)) {
      BLOCK_KEYS[slot] = key;
      BLOCKS[slot] = state;
      BLOCK_STAMPS[slot] = blockGeneration;
    }
    return state;
  }

  static long blockReads() {
    return blockReads;
  }

  /** Published light storage, not the number of propagation operations, determines validity. */
  public static void lightPublished(Object chunkSource) {
    if (Thread.currentThread() == owner && world != null && world.getChunkSource() == chunkSource) {
      lightChanged();
      lightInvalidations++;
    }
  }

  public static void blocksChanged() {
    if (Thread.currentThread() != owner || world == null) return;
    nextBlockGeneration();
    lightChanged();
    ParticleCollisionCache.invalidate();
  }

  public static void chunksChanged() {
    if (Thread.currentThread() != owner || world == null) return;
    nextGeneration();
    Arrays.fill(CHUNKS, null);
    Arrays.fill(BLOCKS, null);
    blocksChanged();
  }

  public static void clear() {
    if (owner != null && Thread.currentThread() != owner) return;
    if (world == null) return;
    ParticleCollisionCache.invalidate();
    world = null;
    Arrays.fill(CHUNKS, null);
    Arrays.fill(BLOCKS, null);
    nextBlockGeneration();
    nextGeneration();
    lightChanged();
  }

  private static void lightChanged() {
    lightGeneration++;
  }

  private static void nextGeneration() {
    if (++generation == 0) {
      Arrays.fill(STAMPS, 0);
      generation = 1;
    }
  }

  private static void nextBlockGeneration() {
    if (++blockGeneration == 0) {
      Arrays.fill(BLOCK_STAMPS, 0);
      blockGeneration = 1;
    }
  }

  public static String diagnostic() {
    return "chunkQueriesTotal="
        + chunkQueries
        + "; chunkHitsTotal="
        + chunkHits
        + "; lightInvalidationsTotal="
        + lightInvalidations
        + "; blockQueriesTotal="
        + blockQueries
        + "; blockHitsTotal="
        + blockHits
        + "; blockReadsTotal="
        + blockReads;
  }
}
