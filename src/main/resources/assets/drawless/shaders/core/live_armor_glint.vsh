#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// Outputs mirror core/glint.vsh. The position expression matches live_armor.vsh exactly: the
// glint pass tests EQUAL depth against retained base armor.
invariant gl_Position;

uniform samplerBuffer FHGeometry;
uniform usamplerBuffer FHInstances;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
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
    vec3 position = (transform * vec4(local.xyz, 1.0)).xyz;

    gl_Position = ProjMat * ModelViewMat * vec4(position, 1.0);

    sphericalVertexDistance = fog_spherical_distance(position);
    cylindricalVertexDistance = fog_cylindrical_distance(position);
    texCoord0 = (TextureMat * vec4(local.w, direction.w, 0.0, 1.0)).xy;
}
