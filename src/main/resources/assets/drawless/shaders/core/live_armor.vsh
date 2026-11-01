#version 330

#if defined(PER_FACE_LIGHTING) || !defined(NO_CARDINAL_LIGHTING)
#moj_import <minecraft:light.glsl>
#endif
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

// Outputs mirror core/entity.vsh; positions come from retained part meshes and live instances.
// Glint/decal passes use an EQUAL depth test, so every retained armor shader computes
// gl_Position with the same invariant expression.
invariant gl_Position;

uniform samplerBuffer FHGeometry;
uniform usamplerBuffer FHInstances;

#ifndef NO_OVERLAY
uniform sampler2D Sampler1;
#endif

#ifndef EMISSIVE
uniform sampler2D Sampler2;
#endif

out float sphericalVertexDistance;
out float cylindricalVertexDistance;

#ifdef PER_FACE_LIGHTING
out vec4 vertexPerFaceColorBack;
out vec4 vertexPerFaceColorFront;
#else
out vec4 vertexColor;
#endif

#ifndef EMISSIVE
out vec4 lightMapColor;
#endif

#ifndef NO_OVERLAY
out vec4 overlayColor;
#endif

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
    vec4 color = vec4(float((properties.x >> 16u) & 255u), float((properties.x >> 8u) & 255u),
                      float(properties.x & 255u), float(properties.x >> 24u)) / 255.0;

    gl_Position = ProjMat * ModelViewMat * vec4(position, 1.0);

    sphericalVertexDistance = fog_spherical_distance(position);
    cylindricalVertexDistance = fog_cylindrical_distance(position);

#ifdef PER_FACE_LIGHTING
    vec2 light = minecraft_compute_light(Light0_Direction, Light1_Direction, normal);
    vertexPerFaceColorBack = minecraft_mix_light_separate(-light, color);
    vertexPerFaceColorFront = minecraft_mix_light_separate(light, color);
#elif defined(NO_CARDINAL_LIGHTING)
    vertexColor = color;
#else
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, normal, color);
#endif

#ifndef EMISSIVE
    lightMapColor = sample_lightmap(Sampler2, ivec2(properties.y & 65535u, properties.y >> 16u));
#endif

#ifndef NO_OVERLAY
    overlayColor = texelFetch(Sampler1, ivec2(properties.z & 65535u, properties.z >> 16u), 0);
#endif

    // Player bodies share one mesh per part and move it to their skin's atlas slot; armor sends
    // a zero offset, which leaves its UVs bit-identical.
    texCoord0 = vec2(local.w, direction.w)
        + vec2(float(properties.w & 65535u), float(properties.w >> 16u)) / 1024.0;

#ifdef APPLY_TEXTURE_MATRIX
    texCoord0 = (TextureMat * vec4(texCoord0, 0.0, 1.0)).xy;
#endif
}
