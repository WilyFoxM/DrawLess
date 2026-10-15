package ru.wilyfox.drawless.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import org.spongepowered.asm.mixin.Mixin;
import ru.wilyfox.drawless.config.DrawLessConfig;
import ru.wilyfox.drawless.diagnostics.RenderMetrics;
import ru.wilyfox.drawless.performance.*;

@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {
    @WrapMethod(method="tick")
    private void drawless$tick(Operation<Void> original) {
        long start=ParticleWorkProbe.begin(); var config=DrawLessConfig.get();
        ParticleCollisionCache.beginTick(config.enabled && config.particleEmptyCollision,Minecraft.getInstance().level);
        try (var ignored=RenderMetrics.getInstance().scope("render/particles/tick")) { original.call(); }
        finally { ParticleCollisionCache.endTick(); ParticleWorkProbe.tickEnd(start); }
    }
    @WrapMethod(method="extract")
    private void drawless$extract(ParticlesRenderState state, Frustum frustum, Camera camera, float tick, Operation<Void> original) {
        long start=ParticleWorkProbe.frameBegin(); var config=DrawLessConfig.get();
        ParticleLightCache.beginFrame(config.enabled && config.particleLightCache);
        try (var ignored=RenderMetrics.getInstance().scope("render/particles/frame")) { original.call(state,frustum,camera,tick); }
        finally { ParticleLightCache.endFrame(); ParticleWorkProbe.frameEnd(start); }
    }
}
