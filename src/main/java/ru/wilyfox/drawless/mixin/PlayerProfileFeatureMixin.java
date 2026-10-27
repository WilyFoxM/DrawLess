package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.NameTagFeatureRenderer;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import ru.wilyfox.drawless.diagnostics.PlayerProfile;

@Mixin(RenderTypeFeatureRenderer.class)
public class PlayerProfileFeatureMixin {
  @Shadow @Final private List<?> groups;

  @WrapMethod(method = "prepareGroup")
  private void drawless$prepare(
      FeatureFrameContext context, List<?> submits, boolean outline, Operation<Void> original) {
    long token =
        PlayerProfile.stages()
            ? PlayerProfile.begin("features/" + getClass().getSimpleName() + "/prepare")
            : 0;
    try {
      original.call(context, submits, outline);
      // A text lookup cache can bypass Group.getVertexBuilder for later actors.
      if (PlayerProfile.stages()
          && (Object) this instanceof NameTagFeatureRenderer
          && !groups.isEmpty()) PlayerProfile.nameTagGroup(groups.getLast(), submits);
    } finally {
      PlayerProfile.end(token);
    }
  }

  @WrapMethod(method = "executeGroup")
  private void drawless$execute(
      FeatureFrameContext context,
      int index,
      List<?> submits,
      boolean outline,
      Operation<Void> original) {
    int previous =
        PlayerProfile.execute(index >= 0 && index < groups.size() ? groups.get(index) : null);
    long token =
        PlayerProfile.stages()
            ? PlayerProfile.begin(
                "features/"
                    + getClass().getSimpleName()
                    + "/execute/"
                    + PlayerProfile.executionKind())
            : 0;
    try {
      original.call(context, index, submits, outline);
    } finally {
      PlayerProfile.end(token);
      PlayerProfile.restoreExecute(previous);
    }
  }

  @WrapOperation(
      method = "executeGroup",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/client/renderer/rendertype/PreparedRenderType;drawFromBuffer(Lnet/minecraft/client/renderer/StagedVertexBuffer$ExecuteInfo;)V"))
  private void drawless$draw(
      PreparedRenderType type, StagedVertexBuffer.ExecuteInfo info, Operation<Void> original) {
    String kind = PlayerProfile.stages() ? PlayerProfile.executionKind() : null;
    long token = kind == null ? 0 : PlayerProfile.begin("draw_cpu/" + kind);
    if (kind != null) {
      PlayerProfile.count("draw_submissions/" + kind, 1);
      PlayerProfile.count("feature_draw_submissions/" + getClass().getSimpleName() + "/" + kind, 1);
    }
    try {
      original.call(type, info);
    } finally {
      PlayerProfile.end(token);
    }
  }
}
