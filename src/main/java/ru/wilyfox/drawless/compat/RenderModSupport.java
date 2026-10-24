package ru.wilyfox.drawless.compat;

import net.fabricmc.loader.api.FabricLoader;

/** Loader-only capabilities shared by mixin selection and rendering paths. */
public final class RenderModSupport {
  private static final FabricLoader LOADER = FabricLoader.getInstance();
  public static final boolean SODIUM = LOADER.isModLoaded("sodium");
  public static final boolean IRIS = LOADER.isModLoaded("iris");
  public static final boolean IMMEDIATELY_FAST = LOADER.isModLoaded("immediatelyfast");
  public static final boolean PARTICLE_CORE = LOADER.isModLoaded("particle_core");
  public static final boolean ASYNC_PARTICLES = LOADER.isModLoaded("asyncparticles");
  public static final boolean PARTICLE_CACHES = !PARTICLE_CORE && !ASYNC_PARTICLES;
  public static final boolean TEMPORAL_PARTICLE_LIGHT =
      PARTICLE_CACHES
          && !IRIS
          && !LOADER.isModLoaded("lambdynlights")
          && !LOADER.isModLoaded("ryoamiclights")
          && !LOADER.isModLoaded("dynamiclights");

  private RenderModSupport() {}
}
