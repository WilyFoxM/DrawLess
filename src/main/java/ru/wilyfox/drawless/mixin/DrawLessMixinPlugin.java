package ru.wilyfox.drawless.mixin;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import ru.wilyfox.drawless.compat.RenderModSupport;

/** Resolve overlapping or unvalidated rendering integrations before installing hooks. */
public final class DrawLessMixinPlugin implements IMixinConfigPlugin {
  @Override
  public boolean shouldApplyMixin(String target, String mixin) {
    if (mixin.endsWith(".ParticleLightPublicationMixin"))
      return RenderModSupport.TEMPORAL_PARTICLE_LIGHT;
    if (mixin.endsWith(".ParticleLightCacheMixin") || mixin.endsWith(".ParticleCollisionMixin"))
      return RenderModSupport.PARTICLE_CACHES;
    if (mixin.endsWith(".FeatureBatchingMixin")) {
      return !RenderModSupport.IMMEDIATELY_FAST && !RenderModSupport.IRIS;
    }
    if (mixin.endsWith(".TextBufferReuseMixin") || mixin.endsWith(".StagedBufferReuseAccessor")) {
      return !RenderModSupport.IMMEDIATELY_FAST;
    }
    // Sodium replaces the vanilla section graph that this diagnostic reads.
    if (mixin.endsWith(".DisplayProbeViewAreaAccessor")) return !RenderModSupport.SODIUM;
    // Shader packs replace the entity programs that retained armor derives its pipelines from.
    if (mixin.contains(".PlayerBody"))
      return !RenderModSupport.IRIS && !RenderModSupport.PLAYER_MODEL_MODS;
    if (mixin.contains(".PlayerArmor")
        || mixin.contains(".PlayerItem")
        || mixin.endsWith(".ModelPartCubesAccessor")) return !RenderModSupport.IRIS;
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
