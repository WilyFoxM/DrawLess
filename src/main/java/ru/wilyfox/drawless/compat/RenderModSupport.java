package ru.wilyfox.drawless.compat;

import net.fabricmc.loader.api.FabricLoader;

/** Loader-only capabilities shared by mixin selection and rendering paths. */
public final class RenderModSupport {
  private static final FabricLoader LOADER = FabricLoader.getInstance();
  public static final boolean SODIUM = LOADER.isModLoaded("sodium");
  public static final boolean IRIS = LOADER.isModLoaded("iris");
  public static final boolean IMMEDIATELY_FAST = LOADER.isModLoaded("immediatelyfast");

  private RenderModSupport() {}
}
