package ru.wilyfox.drawless.performance;

import java.util.Arrays;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Proves an entire vanilla collision scan volume contains only air. Never caches resolved movement. */
public final class ParticleCollisionCache {
    private static final int CAPACITY = 8192;
    private static final long[] KEYS = new long[CAPACITY];
    private static final int[] STAMPS = new int[CAPACITY], SIZES = new int[CAPACITY];
    private static final byte[] RESULTS = new byte[CAPACITY];
    private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();
    private static int generation;
    private static Thread owner;
    private static ClientLevel world;
    private static boolean enabled, lastEnabled;
    private static long queries, hits, skipped, fallback, blockReads, invalidations;
    private static long ticksTotal, queriesTotal, hitsTotal, skippedTotal, fallbackTotal, blockReadsTotal, invalidationsTotal;
    private ParticleCollisionCache() {}

    public static void beginTick(boolean on, ClientLevel level) {
        lastEnabled = on;
        enabled = on && level != null && level.getClass() == ClientLevel.class;
        world = enabled ? level : null; owner = enabled ? Thread.currentThread() : null;
        queries = hits = skipped = fallback = blockReads = invalidations = 0;
        if (enabled) nextGeneration();
    }
    public static void endTick() {
        if (enabled) {
            ticksTotal++; queriesTotal += queries; hitsTotal += hits; skippedTotal += skipped;
            fallbackTotal += fallback; blockReadsTotal += blockReads; invalidationsTotal += invalidations;
        }
        enabled = false; owner = null; world = null;
    }
    /** Client block/chunk mutations are applied on the same thread as particle ticks. */
    public static void invalidate() {
        if (enabled && Thread.currentThread() == owner) { invalidations++; nextGeneration(); }
    }
    public static Vec3 collide(CollisionContext context, Vec3 movement, AABB box, Level level, List<VoxelShape> extra) {
        if (!enabled || level != world || Thread.currentThread() != owner)
            return Entity.collideBoundingBox(context, movement, box, level, extra);
        queries++;
        if (extra.isEmpty() && emptyVolume(box, movement)) { skipped++; return movement; }
        fallback++; return Entity.collideBoundingBox(context, movement, box, level, extra);
    }
    private static boolean emptyVolume(AABB box, Vec3 movement) {
        double minX = box.minX + Math.min(0, movement.x), minY = box.minY + Math.min(0, movement.y), minZ = box.minZ + Math.min(0, movement.z);
        double maxX = box.maxX + Math.max(0, movement.x), maxY = box.maxY + Math.max(0, movement.y), maxZ = box.maxZ + Math.max(0, movement.z);
        if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
                || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)
                || minX > maxX || minY > maxY || minZ > maxZ
                || Math.abs(minX) > 30_000_000 || Math.abs(maxX) > 30_000_000
                || Math.abs(minZ) > 30_000_000 || Math.abs(maxZ) > 30_000_000
                || minY < -2045 || maxY > 2045) return false;
        // Match BlockCollisions' swept box + epsilon + one-block neighbourhood. Include even its
        // skipped outer corners: proving a larger region empty is conservative for every context.
        int x0 = Mth.floor(minX - 1e-7) - 1, y0 = Mth.floor(minY - 1e-7) - 1, z0 = Mth.floor(minZ - 1e-7) - 1;
        int x1 = Mth.floor(maxX + 1e-7) + 1, y1 = Mth.floor(maxY + 1e-7) + 1, z1 = Mth.floor(maxZ + 1e-7) + 1;
        int sx = x1 - x0 + 1, sy = y1 - y0 + 1, sz = z1 - z0 + 1;
        if (sx > 4 || sy > 4 || sz > 4) return false; // At most 64 reads for a new proof.
        int size = sx | sy << 3 | sz << 6;
        long key = BlockPos.asLong(x0, y0, z0);
        int slot = Mth.murmurHash3Mixer((int) (key ^ (key >>> 32)) ^ size * 0x9e3779b9) & (CAPACITY - 1);
        if (STAMPS[slot] == generation && KEYS[slot] == key && SIZES[slot] == size) { hits++; return RESULTS[slot] == 1; }
        int before = generation;
        boolean clear = scanAir(x0, y0, z0, x1, y1, z1);
        if (before != generation) return false;
        STAMPS[slot] = generation; KEYS[slot] = key; SIZES[slot] = size; RESULTS[slot] = (byte) (clear ? 1 : 2);
        return clear;
    }
    private static boolean scanAir(int x0, int y0, int z0, int x1, int y1, int z1) {
        BlockGetter chunk = null; int lastX = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            int cx = x >> 4, cz = z >> 4;
            if (cx != lastX || cz != lastZ) { chunk = world.getChunkForCollisions(cx, cz); lastX = cx; lastZ = cz; }
            if (chunk == null) return false; // Never load chunks for this optimization.
            for (int y = y0; y <= y1; y++) {
                var state = chunk.getBlockState(POS.set(x, y, z)); blockReads++;
                if (!(state.is(Blocks.AIR) || state.is(Blocks.CAVE_AIR) || state.is(Blocks.VOID_AIR))) return false;
            }
        }
        return true;
    }
    private static void nextGeneration() {
        if (++generation == 0) { Arrays.fill(STAMPS, 0); generation = 1; }
    }
    public static String diagnostic() {
        return "enabled=" + lastEnabled + "; moves=" + queries + "; proofHits=" + hits + "; emptySkips=" + skipped
                + "; fallback=" + fallback + "; blockReads=" + blockReads + "; invalidations=" + invalidations
                + "; ticksTotal=" + ticksTotal + "; movesTotal=" + queriesTotal + "; proofHitsTotal=" + hitsTotal
                + "; emptySkipsTotal=" + skippedTotal + "; fallbackTotal=" + fallbackTotal
                + "; blockReadsTotal=" + blockReadsTotal + "; invalidationsTotal=" + invalidationsTotal;
    }
}
