package ru.wilyfox.drawless.diagnostics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;

/**
 * Diagnostic block-level occlusion of a player-sized box: rays from the camera eye to the eight
 * slightly inset corners and the center. A ray is blocked only by a block rendered as a full opaque
 * cube ({@code isSolidRender}); glass, leaves, slabs and other see-through blocks never block. The
 * box is hidden only when every ray is blocked. This counts candidates; it decides nothing.
 */
final class RayOcclusion {
  private static final double HALF = 0.25, BOTTOM = 0.05, TOP = 1.75;
  private static final int MAX_CELLS = 512;

  private RayOcclusion() {}

  static boolean hidden(BlockGetter level, Vec3 eye, double x, double y, double z) {
    var cell = new BlockPos.MutableBlockPos();
    if (!blocked(level, cell, eye, x, y + 0.9, z)) return false;
    for (int i = 0; i < 8; i++)
      if (!blocked(
          level,
          cell,
          eye,
          x + ((i & 1) == 0 ? -HALF : HALF),
          y + ((i & 2) == 0 ? BOTTOM : TOP),
          z + ((i & 4) == 0 ? -HALF : HALF))) return false;
    return true;
  }

  /**
   * Voxel traversal (Amanatides and Woo) from the eye to the target, ignoring the eye's own cell
   * and the target's cell. Too long a ray counts as not blocked.
   */
  private static boolean blocked(
      BlockGetter level, BlockPos.MutableBlockPos cell, Vec3 eye, double tx, double ty, double tz) {
    double dx = tx - eye.x, dy = ty - eye.y, dz = tz - eye.z;
    int x = floor(eye.x), y = floor(eye.y), z = floor(eye.z);
    int ex = floor(tx), ey = floor(ty), ez = floor(tz);
    int sx = Integer.signum((int) Math.signum(dx)),
        sy = Integer.signum((int) Math.signum(dy)),
        sz = Integer.signum((int) Math.signum(dz));
    double nx = next(eye.x, dx, sx), ny = next(eye.y, dy, sy), nz = next(eye.z, dz, sz);
    double ix = sx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
    double iy = sy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy);
    double iz = sz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
    for (int steps = 0; steps < MAX_CELLS; steps++) {
      if (nx <= ny && nx <= nz) {
        x += sx;
        nx += ix;
      } else if (ny <= nz) {
        y += sy;
        ny += iy;
      } else {
        z += sz;
        nz += iz;
      }
      if (x == ex && y == ey && z == ez) return false;
      if (level.getBlockState(cell.set(x, y, z)).isSolidRender()) return true;
    }
    return false;
  }

  /** Ray parameter (0..1 along the segment) of the first cell boundary on one axis. */
  private static double next(double origin, double delta, int step) {
    if (step == 0) return Double.POSITIVE_INFINITY;
    double boundary = step > 0 ? Math.floor(origin) + 1 : Math.floor(origin);
    return (boundary - origin) / delta;
  }

  private static int floor(double value) {
    return (int) Math.floor(value);
  }
}
