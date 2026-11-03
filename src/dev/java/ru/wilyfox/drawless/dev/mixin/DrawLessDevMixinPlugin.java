package ru.wilyfox.drawless.dev.mixin;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import ru.wilyfox.drawless.compat.RenderModSupport;

/** Skips the diagnostic hooks whose targets another rendering mod replaces. */
public final class DrawLessDevMixinPlugin implements IMixinConfigPlugin {
  @Override
  public boolean shouldApplyMixin(String target, String mixin) {
    // Sodium replaces the vanilla section graph that this diagnostic reads.
    if (mixin.endsWith(".DisplayProbeViewAreaAccessor")) return !RenderModSupport.SODIUM;
    return true;
  }

  @Override
  public void onLoad(String mixinPackage) {}

  @Override
  public String getRefMapperConfig() {
    return null;
  }

  @Override
  public void acceptTargets(Set<String> mine, Set<String> others) {}

  @Override
  public List<String> getMixins() {
    return null;
  }

  @Override
  public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}

  @Override
  public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
