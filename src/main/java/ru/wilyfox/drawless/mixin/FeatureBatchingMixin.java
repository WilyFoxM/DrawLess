package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import ru.wilyfox.drawless.config.DrawLessConfig;
import ru.wilyfox.drawless.diagnostics.RenderMetrics;
import ru.wilyfox.drawless.performance.FeatureBatching;

import java.util.List;

/** Limited mode preserves vanilla order; expanded mode also promotes ordered groups. */
@Mixin(targets = "net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer$Group")
public abstract class FeatureBatchingMixin {
    @Shadow @Final private boolean canReorder;

    @ModifyExpressionValue(method = "getOrAddDraw", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/renderer/feature/RenderTypeFeatureRenderer$Group;canReorder:Z", opcode = Opcodes.GETFIELD))
    private boolean drawless$allowReorder(boolean original) {
        return original || FeatureBatching.reorders();
    }

    @WrapOperation(method = "getOrAddDraw", at = @At(value = "INVOKE", target = "Ljava/util/List;indexOf(Ljava/lang/Object;)I"))
    private int drawless$findCompatible(List<PreparedRenderType> materials, Object requested, Operation<Integer> original) {
        boolean enabled = FeatureBatching.enabled();
        var metrics = RenderMetrics.getInstance();
        boolean profiling = DrawLessConfig.get().enabled && metrics.isEnabled();
        if (!enabled && !profiling) return original.call(materials, requested);
        try (var ignored = metrics.scope("render/featureBatching/search")) {
            int result = enabled ? FeatureBatching.findCompatible(materials, (PreparedRenderType) requested)
                    : original.call(materials, requested);
            if (profiling) {
                metrics.incrementCounter("featureBatching/searches", 1);
                metrics.incrementCounter("featureBatching/reusedDraws", result >= 0 ? 1 : 0);
                metrics.incrementCounter("featureBatching/misses", result < 0 ? 1 : 0);
                boolean promoted = !canReorder && enabled && DrawLessConfig.get().featureBatchingReorder;
                metrics.incrementCounter("featureBatching/promotedSearches", promoted ? 1 : 0);
                metrics.incrementCounter("featureBatching/promotedReuses", promoted && result >= 0 ? 1 : 0);
                if (enabled) metrics.incrementCounter("featureBatching/valueComparisons", result >= 0 ? result + 1 : materials.size());
            }
            return result;
        }
    }
}
