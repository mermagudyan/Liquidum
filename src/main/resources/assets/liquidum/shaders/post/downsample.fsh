#version 330
uniform sampler2D InSampler;
in vec2 texCoord;
out vec4 fragColor;
void main() {
    vec2 texel = 1.5 / textureSize(InSampler, 0);
    vec3 c = texture(InSampler, texCoord).rgb * 4.0;
    c += texture(InSampler, texCoord + vec2(texel.x, 0.0)).rgb * 2.0;
    c += texture(InSampler, texCoord - vec2(texel.x, 0.0)).rgb * 2.0;
    c += texture(InSampler, texCoord + vec2(0.0, texel.y)).rgb * 2.0;
    c += texture(InSampler, texCoord - vec2(0.0, texel.y)).rgb * 2.0;
    c += texture(InSampler, texCoord + texel).rgb;
    c += texture(InSampler, texCoord - texel).rgb;
    c += texture(InSampler, texCoord + vec2(-texel.x, texel.y)).rgb;
    c += texture(InSampler, texCoord + vec2(texel.x, -texel.y)).rgb;
    fragColor = vec4(c / 16.0, 1.0);
}
