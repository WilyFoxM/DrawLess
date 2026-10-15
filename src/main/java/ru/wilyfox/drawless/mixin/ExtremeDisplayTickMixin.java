package ru.wilyfox.drawless.mixin;

import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import ru.wilyfox.drawless.performance.ExtremeDisplayPerformance;

@Mixin(Display.class)
public class ExtremeDisplayTickMixin {
    // After vanilla removes a dead vehicle, before the client-only rendering/interpolation branch.
    @Inject(method="tick",at=@At(value="FIELD",target="Lnet/minecraft/world/entity/Display;updateStartTick:Z",opcode=180),cancellable=true)
    private void drawless$pauseDisplayAnimation(CallbackInfo ci) {
        if(ExtremeDisplayPerformance.skipTick((Display)(Object)this))ci.cancel();
    }
}
