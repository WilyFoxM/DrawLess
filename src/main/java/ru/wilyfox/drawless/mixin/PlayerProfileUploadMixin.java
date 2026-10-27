package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import ru.wilyfox.drawless.diagnostics.PlayerProfile;

@Mixin(StagedVertexBuffer.class)
public class PlayerProfileUploadMixin {
  @WrapMethod(method = "upload")
  private void drawless$upload(Operation<Void> original) {
    long token = PlayerProfile.stages() ? PlayerProfile.begin("shared_staged_upload") : 0;
    try {
      original.call();
    } finally {
      PlayerProfile.end(token);
    }
  }
}
