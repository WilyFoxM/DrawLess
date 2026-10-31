package ru.wilyfox.drawless.performance;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.IdentityHashMap;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/**
 * Vertex-pulling variants of vanilla pipelines: the base fragment shader, defines, blending, depth
 * and cull state are kept; only the vertex shader and the mesh/instance texel buffers change. A
 * null value records a refused base. Pipelines follow the device: clear on resource reload.
 */
final class RetainedPipelines {
  static final BindGroupLayout MESH_LAYOUT =
      BindGroupLayout.builder()
          .withUniform("FHGeometry", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT)
          .withUniform("FHInstances", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_UINT)
          .build();

  private final String prefix;
  private final IdentityHashMap<RenderPipeline, RenderPipeline> derived = new IdentityHashMap<>();

  RetainedPipelines(String prefix) {
    this.prefix = prefix;
  }

  RenderPipeline get(RenderPipeline base) {
    return derived.get(base);
  }

  /** True once the base was compiled or refused. */
  boolean known(RenderPipeline base) {
    return derived.containsKey(base);
  }

  int size() {
    return derived.size();
  }

  RenderPipeline reject(RenderPipeline base) {
    derived.put(base, null);
    return null;
  }

  /**
   * Builds and precompiles before any geometry is withheld: a shader error must leave the vanilla
   * path drawing. Returns null without caching when the device refuses the pipeline.
   */
  RenderPipeline compile(RenderPipeline base, Identifier vertexShader) {
    var snippet =
        new RenderPipeline.Snippet(
            Optional.of(vertexShader),
            Optional.of(base.getFragmentShader()),
            Optional.of(base.getShaderDefines()),
            Optional.of(base.getBindGroupLayouts()),
            base.getColorTargetStates(),
            base.getColorTargetStates().length,
            Optional.ofNullable(base.getDepthStencilState()),
            Optional.of(base.getPolygonMode()),
            Optional.of(base.isCull()),
            new VertexFormat[RenderPass.MAX_VERTEX_BUFFERS],
            Optional.of(base.getPrimitiveTopology()));
    var result =
        RenderPipeline.builder(snippet)
            .withLocation(
                Identifier.fromNamespaceAndPath("drawless", prefix + base.getLocation().getPath()))
            .withBindGroupLayout(MESH_LAYOUT)
            .build();
    if (!RenderSystem.getDevice().precompilePipeline(result).isValid()) return null;
    derived.put(base, result);
    return result;
  }

  void clear() {
    derived.clear();
  }
}
