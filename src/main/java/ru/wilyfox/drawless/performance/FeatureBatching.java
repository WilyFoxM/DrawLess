package ru.wilyfox.drawless.performance;

import com.mojang.blaze3d.systems.ScissorState;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import ru.wilyfox.drawless.compat.RenderModSupport;
import ru.wilyfox.drawless.config.DrawLessConfig;

import java.util.List;
import java.util.Objects;

/** Value comparison inside a vanilla-reorderable group; owns no buffers or persistent state. */
public final class FeatureBatching {
    private FeatureBatching() {}

    public static boolean enabled() {
        var config = DrawLessConfig.get();
        return config.enabled && config.featureBatching
                && !RenderModSupport.IMMEDIATELY_FAST && !RenderModSupport.IRIS;
    }

    /** Preserve the first-match rule and every prepared material field. */
    public static int findCompatible(List<PreparedRenderType> materials, PreparedRenderType requested) {
        for (int i = 0; i < materials.size(); i++) {
            var existing = materials.get(i);
            if (existing == requested || sameState(existing, requested)) return i;
        }
        return -1;
    }

    private static boolean sameState(PreparedRenderType a, PreparedRenderType b) {
        return Objects.equals(a.pipeline(), b.pipeline())
                && Objects.equals(a.outputTarget(), b.outputTarget())
                && Objects.equals(a.dynamicTransforms(), b.dynamicTransforms())
                && Objects.equals(a.textures(), b.textures())
                && sameScissor(a.scissorState(), b.scissorState());
    }

    private static boolean sameScissor(ScissorState a, ScissorState b) {
        if (a == b) return true;
        if (a == null || b == null || a.getClass() != ScissorState.class || b.getClass() != ScissorState.class) {
            return Objects.equals(a, b);
        }
        return a.enabled() == b.enabled() && a.x() == b.x() && a.y() == b.y()
                && a.width() == b.width() && a.height() == b.height();
    }
}
