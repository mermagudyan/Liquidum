#version 150

uniform sampler2D Sampler0;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform GlassConfig {
    vec4 a; // MousePos.xy (uv), Bounds.xy (physical px)
    vec4 b; // Size.xy (physical px), Radius (px), ShapeType
    vec4 c; // Feather (px), Time, AnimationProgress, Opacity
    vec4 d; // RefractionStrength, FresnelPower, ChromaticAberration, SaturationBoost
    vec4 e; // edgeHighlight, specularStrength, innerReflection, distortionStrength
};

in vec2 texCoord;
out vec4 fragColor;

float sdShape(vec2 p, vec2 halfSize, float radius, float shapeType) {
    if (shapeType < 0.5) {
        return max(p.x - halfSize.x, p.y - halfSize.y);
    } else if (shapeType < 1.5) {
        vec2 q = abs(p) - (halfSize - vec2(radius));
        return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
    } else if (shapeType < 2.5) {
        return length(p / max(halfSize, vec2(1.0))) - 1.0;
    } else {
        return length(p) - min(halfSize.x, halfSize.y);
    }
}

void main() {
    vec2 fragPx = texCoord * OutSize;
    vec2 bounds = a.zw;
    vec2 size = b.xy;
    float radius = b.z;
    float shapeType = b.w;
    float feather = c.x;
    float opacity = c.w;
    float refr = d.x;
    float fresnelP = d.y;
    float chroma = d.z;
    float sat = d.w;
    float edgeHi = e.x;
    float spec = e.y;
    float innerRefl = e.z;

    vec2 center = bounds + size * 0.5;
    float dist = sdShape(fragPx - center, size * 0.5, radius, shapeType);
    float mask = 1.0 - smoothstep(-feather, feather, dist);
    if (mask <= 0.001) discard;

    vec2 dir = fragPx - center;
    float rad = max(length(size) * 0.5, 1.0);
    float edge = smoothstep(0.0, rad, length(dir));
    vec2 uvDir = dir / max(OutSize, vec2(1.0));
    vec2 refrOff = uvDir * refr * (0.2 + edge);
    vec2 sampleUv = texCoord + refrOff;

    vec2 ca = uvDir * chroma;
    float r = texture(Sampler0, sampleUv + ca).r;
    float g = texture(Sampler0, sampleUv).g;
    float bch = texture(Sampler0, sampleUv - ca).b;
    vec3 col = vec3(r, g, bch);

    float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));
    col = mix(vec3(luma), col, 1.0 + sat);

    // Rim / edge highlight from the SDF gradient (strong near the edge).
    float rim = smoothstep(0.0, 1.0, 1.0 - abs(2.0 * mask - 1.0)) * edgeHi * 0.25;
    col += rim;
    col += innerRefl * 0.05 * (1.0 - mask);

    // Specular shimmer that follows the cursor.
    vec2 mPx = a.xy * OutSize;
    float md = length(fragPx - mPx);
    col += smoothstep(rad * 0.5, 0.0, md) * spec * 0.15;

    col *= mix(0.85, 1.0, c.z);
    fragColor = vec4(1.0, 0.1, 0.7, mask * 0.85);

}
