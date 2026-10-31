#version 330

#moj_import <minecraft:light.glsl>
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

// Retained item glint tests EQUAL depth against these positions; live_armor_glint.vsh computes
// gl_Position with the same invariant expression.
invariant gl_Position;

uniform samplerBuffer FHGeometry;
uniform usamplerBuffer FHInstances;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;

void main() {
    uint reference = uint(gl_VertexID);
    int vertex = int(reference & 1048575u) * 2;
    int instance = int(reference >> 20u) * 8;
    vec4 local = texelFetch(FHGeometry, vertex);
    vec4 direction = texelFetch(FHGeometry, vertex + 1);
    mat4 transform = mat4(
        uintBitsToFloat(texelFetch(FHInstances, instance)),
        uintBitsToFloat(texelFetch(FHInstances, instance + 1)),
        uintBitsToFloat(texelFetch(FHInstances, instance + 2)),
        uintBitsToFloat(texelFetch(FHInstances, instance + 3)));
    mat3 normalTransform = mat3(
        uintBitsToFloat(texelFetch(FHInstances, instance + 4)).xyz,
        uintBitsToFloat(texelFetch(FHInstances, instance + 5)).xyz,
        uintBitsToFloat(texelFetch(FHInstances, instance + 6)).xyz);
    uvec4 properties = texelFetch(FHInstances, instance + 7);
    vec3 position = (transform * vec4(local.xyz, 1.0)).xyz;
    vec3 normal = normalTransform * direction.xyz;
    vec4 tint = vec4(float((properties.x >> 16u) & 255u), float((properties.x >> 8u) & 255u),
                     float(properties.x & 255u), float(properties.x >> 24u)) / 255.0;
    ivec2 light = ivec2(properties.y & 65535u, properties.y >> 16u);
    ivec2 overlay = ivec2(properties.z & 65535u, properties.z >> 16u);

    gl_Position = ProjMat * ModelViewMat * vec4(position, 1.0);
    sphericalVertexDistance = fog_spherical_distance(position);
    cylindricalVertexDistance = fog_cylindrical_distance(position);
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, normal, tint);
    lightMapColor = sample_lightmap(Sampler2, light);
    overlayColor = texelFetch(Sampler1, overlay, 0);
    texCoord0 = vec2(local.w, direction.w);
}
