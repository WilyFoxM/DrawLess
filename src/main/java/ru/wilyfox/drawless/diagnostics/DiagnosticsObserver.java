package ru.wilyfox.drawless.diagnostics;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.entity.Entity;
import ru.wilyfox.drawless.observe.Observer;

/** Connects the optimizations' reports to the diagnostic sessions and reports. */
public final class DiagnosticsObserver implements Observer {
  private final RenderMetrics metrics = RenderMetrics.getInstance();

  @Override
  public boolean isEnabled() {
    return metrics.isEnabled();
  }

  @Override
  public Scope scope(String name) {
    return metrics.scope(name);
  }

  @Override
  public void incrementCounter(String name, long amount) {
    metrics.incrementCounter(name, amount);
  }

  @Override
  public void work(Work kind, long nanos) {
    FrameWork.add(kind, nanos);
  }

  @Override
  public boolean sampling() {
    return DisplayCostProbe.frame();
  }

  @Override
  public void global(String stage, long nanos) {
    DisplayCostProbe.global(stage, nanos);
  }

  @Override
  public Sample cost(Entity entity) {
    return DisplayCostProbe.cost(entity);
  }

  @Override
  public Sample source(EntityRenderState state) {
    return DisplayCostProbe.source(state);
  }

  @Override
  public Entity entity(EntityRenderState state) {
    return DisplayCostProbe.entity(state);
  }

  @Override
  public void admission(Sample sample, String reason) {
    DisplayCostProbe.admission((DisplayCostProbe.Cost) sample, reason);
  }

  @Override
  public void extracted(Entity entity, EntityRenderState state, Sample sample, long nanos) {
    DisplayCostProbe.extracted(entity, state, (DisplayCostProbe.Cost) sample, nanos);
  }

  @Override
  public void frameBegin(Minecraft client) {
    DisplayCostProbe.beginFrame(client);
  }

  @Override
  public void sceneEnd() {
    DisplayCostProbe.sceneEnd();
  }

  @Override
  public void frameEnd(Minecraft client, Camera camera, Frustum frustum, LevelRenderState state) {
    DisplayProbe.onFrame(client, camera, frustum, state);
    LongRecording.onFrame(client, state);
  }

  @Override
  public long particleStart() {
    return ParticleWorkProbe.begin();
  }

  @Override
  public long particleFrameStart() {
    return ParticleWorkProbe.frameBegin();
  }

  @Override
  public void particleTickEnd(long start) {
    ParticleWorkProbe.tickEnd(start);
  }

  @Override
  public void particleFrameEnd(long start) {
    ParticleWorkProbe.frameEnd(start);
  }

  @Override
  public void particleContextEnd(long start) {
    ParticleWorkProbe.contextEnd(start);
  }

  @Override
  public void settingsOpened(Minecraft client) {
    PlayerProfile.finish(client, "settings_opened");
    BenchmarkSeries.beforeSettings(client);
  }
}
