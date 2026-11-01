package ru.wilyfox.drawless.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import ru.wilyfox.drawless.DrawLess;

/**
 * Whether an Iris shader pack currently replaces the vanilla programs. Without an active pack Iris
 * renders with vanilla programs, so DrawLess paths stay available; with one, paths that draw with
 * their own pipelines or regroup draws use ordinary rendering. Read from the Iris API on every
 * call, since a pack can be toggled in-game; treated as active when the API is missing.
 */
public final class IrisShaders {
  private static final MethodHandle IN_USE = resolve();

  private IrisShaders() {}

  public static boolean packInUse() {
    if (!RenderModSupport.IRIS) return false;
    if (IN_USE == null) return true;
    try {
      return (boolean) IN_USE.invokeExact();
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new IllegalStateException("Iris shader pack state", e);
    }
  }

  /** Report environment suffix; empty without Iris so other reports keep their format. */
  public static String environment() {
    return RenderModSupport.IRIS ? "; shaderPack=" + packInUse() : "";
  }

  private static MethodHandle resolve() {
    if (!RenderModSupport.IRIS) return null;
    try {
      var api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
      var instance = api.getMethod("getInstance").invoke(null);
      return MethodHandles.publicLookup()
          .findVirtual(api, "isShaderPackInUse", MethodType.methodType(boolean.class))
          .bindTo(instance);
    } catch (ReflectiveOperationException e) {
      DrawLess.LOGGER.warn("Iris API not found; DrawLess treats a shader pack as always active", e);
      return null;
    }
  }
}
