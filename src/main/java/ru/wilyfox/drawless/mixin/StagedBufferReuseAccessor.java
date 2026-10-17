package ru.wilyfox.drawless.mixin;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(StagedVertexBuffer.class)
public interface StagedBufferReuseAccessor {
    @Accessor("lastVertexBuilder") BufferBuilder drawless$activeBuilder();
    @Accessor("currentVertexBuffer") GpuBuffer drawless$uploadedBuffer();
}
