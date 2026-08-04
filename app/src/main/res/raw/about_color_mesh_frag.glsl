uniform vec2 uResolution;
uniform float uAnimTime;
uniform vec4 uBound;
uniform vec3 uPoints[4];
uniform vec4 uColors[4];
uniform float uAlphaMulti;
uniform float uNoiseScale;
uniform float uPointOffset;
uniform float uPointRadiusMulti;
uniform float uSaturateOffset;
uniform float uLightOffset;
uniform float uAlphaOffset;

vec3 rgb2hsv(vec3 color) {
    vec4 constants = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 mixedBg = mix(vec4(color.bg, constants.wz), vec4(color.gb, constants.xy), step(color.b, color.g));
    vec4 mixedRgb = mix(vec4(mixedBg.xyw, color.r), vec4(color.r, mixedBg.yzx), step(mixedBg.x, color.r));
    float delta = mixedRgb.x - min(mixedRgb.w, mixedRgb.y);
    float epsilon = 1.0e-10;
    return vec3(
        abs(mixedRgb.z + (mixedRgb.w - mixedRgb.y) / (6.0 * delta + epsilon)),
        delta / (mixedRgb.x + epsilon),
        mixedRgb.x
    );
}

vec3 hsv2rgb(vec3 color) {
    vec4 constants = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 range = abs(fract(color.xxx + constants.xyz) * 6.0 - constants.www);
    return color.z * mix(constants.xxx, clamp(range - constants.xxx, 0.0, 1.0), color.y);
}

float hash(vec2 point) {
    vec3 value = fract(vec3(point.xyx) * 0.13);
    value += dot(value, value.yzx + 3.333);
    return fract((value.x + value.y) * value.z);
}

float perlin(vec2 point) {
    vec2 cell = floor(point);
    vec2 local = fract(point);
    float topLeft = hash(cell);
    float topRight = hash(cell + vec2(1.0, 0.0));
    float bottomLeft = hash(cell + vec2(0.0, 1.0));
    float bottomRight = hash(cell + vec2(1.0, 1.0));
    vec2 smoothLocal = local * local * (3.0 - 2.0 * local);
    return mix(topLeft, topRight, smoothLocal.x)
        + (bottomLeft - topLeft) * smoothLocal.y * (1.0 - smoothLocal.x)
        + (bottomRight - topRight) * smoothLocal.x * smoothLocal.y;
}

float gradientNoise(vec2 point) {
    return fract(52.9829189 * fract(dot(point, vec2(0.06711056, 0.00583715))));
}

vec4 main(vec2 fragCoord) {
    vec2 screenUv = fragCoord / uResolution;
    vec2 effectUv = vec2(screenUv.x, 1.0 - screenUv.y);
    effectUv = (effectUv - uBound.xy) / uBound.zw;

    vec4 color = vec4(0.0);
    float noiseValue = perlin(screenUv * uNoiseScale - vec2(uAnimTime));

    for (int index = 0; index < 4; index++) {
        vec4 pointColor = uColors[index];
        pointColor.rgb *= pointColor.a;
        vec2 point = uPoints[index].xy;
        float radius = uPoints[index].z * uPointRadiusMulti;

        point.x += sin(uAnimTime + point.y) * uPointOffset;
        point.y += cos(uAnimTime + point.x) * uPointOffset;

        float distanceToPoint = distance(effectUv, point);
        float coverage = smoothstep(radius, 0.0, distanceToPoint);
        color.rgb = mix(color.rgb, pointColor.rgb, coverage);
        color.a = mix(color.a, pointColor.a, coverage);
    }

    float oppositeNoise = smoothstep(0.0, 1.0, noiseValue);
    color.rgb /= max(color.a, 1.0e-5);
    vec3 hsv = rgb2hsv(color.rgb);
    hsv.y = mix(hsv.y, 0.0, oppositeNoise * uSaturateOffset);
    color.rgb = hsv2rgb(hsv);
    color.rgb += oppositeNoise * uLightOffset;
    color.a = clamp(color.a * uAlphaMulti, 0.0, 1.0);

    color += (10.0 / 255.0) * gradientNoise(fragCoord) - (5.0 / 255.0);
    return vec4(color.rgb * color.a, color.a);
}
