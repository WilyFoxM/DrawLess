package ru.wilyfox.drawless.observe;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.entity.Entity;

/**
 * What the optimizations report to diagnostics. Reports only: nothing here steers an optimization.
 * With no observer installed every method does nothing, without clocks, collections or objects.
 */
public interface Observer {
  Observer NONE = new Observer() {};

  Scope NO_SCOPE = () -> {};

  /** A measured section, closed by try-with-resources. */
  interface Scope extends AutoCloseable {
    @Override
    void close();
  }

  /** Opaque per-model record of a sampled frame, owned by the observer. */
  interface Sample {}

  /** First-time work a retained owner did inside a frame. */
  enum Work {
    ITEM_MESH("itemMesh"),
    PART_MESH("partMesh"),
    SKIN("skin"),
    COMPACTION("compaction");

    public final String label;

    Work(String label) {
      this.label = label;
    }
  }

  default boolean isEnabled() {
    return false;
  }

  default Scope scope(String name) {
    return NO_SCOPE;
  }

  default void incrementCounter(String name, long amount) {}

  default void work(Work kind, long nanos) {}

  /** True during a frame sampled per Item Display model. */
  default boolean sampling() {
    return false;
  }

  default void global(String stage, long nanos) {}

  default Sample cost(Entity entity) {
    return null;
  }

  default Sample source(EntityRenderState state) {
    return null;
  }

  default Entity entity(EntityRenderState state) {
    return null;
  }

  default void admission(Sample sample, String reason) {}

  default void extracted(Entity entity, EntityRenderState state, Sample sample, long nanos) {}

  default void frameBegin(Minecraft client) {}

  default void sceneEnd() {}

  default void frameEnd(Minecraft client, Camera camera, Frustum frustum, LevelRenderState state) {}

  /** Start of a coarse particle pass; 0 when it is not timed. */
  default long particleStart() {
    return 0;
  }

  default long particleFrameStart() {
    return 0;
  }

  default void particleTickEnd(long start) {}

  default void particleFrameEnd(long start) {}

  default void particleContextEnd(long start) {}

  default void settingsOpened(Minecraft client) {}
}
