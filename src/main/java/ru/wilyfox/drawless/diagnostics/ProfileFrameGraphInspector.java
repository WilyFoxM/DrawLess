package ru.wilyfox.drawless.diagnostics;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;

/** Keeps the existing inspector and measures pass execution on the sampled render thread. */
public final class ProfileFrameGraphInspector
    implements FrameGraphBuilder.Inspector, AutoCloseable {
  private final FrameGraphBuilder.Inspector delegate;
  private long pass;

  public ProfileFrameGraphInspector(FrameGraphBuilder.Inspector delegate) {
    this.delegate = delegate;
  }

  @Override
  public void acquireResource(String name) {
    delegate.acquireResource(name);
  }

  @Override
  public void releaseResource(String name) {
    delegate.releaseResource(name);
  }

  @Override
  public void beforeExecutePass(String name) {
    delegate.beforeExecutePass(name);
    // Pass names come from the renderer, never from entity names or UUIDs.
    String label = name.length() > 96 ? name.substring(0, 96) : name;
    pass = PlayerProfile.begin("frame_pass/" + label.replace('/', '_'));
  }

  @Override
  public void afterExecutePass(String name) {
    close();
    delegate.afterExecutePass(name);
  }

  @Override
  public void close() {
    PlayerProfile.end(pass);
    pass = 0;
  }
}
