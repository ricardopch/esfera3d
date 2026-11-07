// Visor de panorámicas equirectangulares por trazado de rayos.
// Cada píxel de la pantalla se convierte en una dirección de mirada (teniendo en cuenta,
// en modo gafas, la distorsión de la lente) y se busca su color en la panorámica.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform sampler2D uTex;     // panorámica del ojo (o la central en modo figurado)
uniform sampler2D uTex2;    // segunda panorámica (ojo derecho), solo para el anaglifo
uniform sampler2D uDepth;   // mapa q = 0.3 m / distancia (modo figurado)
uniform float uMode;        // 0 = imagen tal cual, 1 = estéreo figurado
uniform float uAnaglyph;    // 1 = anaglifo rojo/cian en una sola imagen
uniform float uEyeSign;     // -1 ojo izquierdo, +1 ojo derecho
uniform float uEyeK;        // (media distancia entre ojos / 0.3 m) * intensidad
uniform mat3 uRot;          // cámara virtual → mundo
uniform vec2 uCenter;       // centro óptico en píxeles de ventana
uniform vec2 uPxToTan;      // píxel → tangente del ángulo
uniform vec2 uK;            // coeficientes de distorsión de la lente (k1, k2)
uniform float uMaxTan;      // límite del campo visible de la lente

const float PI = 3.14159265;

// Desplazamiento horizontal del estéreo figurado para un ojo (sgn = -1 izquierdo, +1 derecho).
vec2 figurado(vec2 uv, float lat, float sgn) {
    float cl = max(cos(lat), 0.02);
    float fade = 1.0 - smoothstep(60.0, 80.0, abs(degrees(lat)));
    float q = texture2D(uDepth, uv).r;
    float dl = min(0.2, atan(uEyeK * fade * q / cl));
    q = texture2D(uDepth, uv + vec2(sgn * dl / (2.0 * PI), 0.0)).r;
    dl = min(0.2, atan(uEyeK * fade * q / cl));
    return uv + vec2(sgn * dl / (2.0 * PI), 0.0);
}

void main() {
    vec2 t = (gl_FragCoord.xy - uCenter) * uPxToTan;
    float r2 = dot(t, t);
    t *= 1.0 + uK.x * r2 + uK.y * r2 * r2;
    if (dot(t, t) > uMaxTan * uMaxTan) {
        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    vec3 d = normalize(uRot * vec3(t, -1.0));
    float lon = atan(d.x, d.y);
    float lat = asin(clamp(d.z, -1.0, 1.0));
    vec2 uv = vec2(lon / (2.0 * PI) + 0.5, 0.5 - lat / PI);
    if (uAnaglyph > 0.5) {
        vec2 uvL = uv;
        vec2 uvR = uv;
        if (uMode > 0.5) {
            uvL = figurado(uv, lat, -1.0);
            uvR = figurado(uv, lat, 1.0);
        }
        vec3 cl = texture2D(uTex, uvL).rgb;
        vec3 cr = texture2D(uTex2, uvR).rgb;
        // Anaglifo de medio color: rojo = luminancia del ojo izquierdo, verde y azul del derecho
        gl_FragColor = vec4(dot(cl, vec3(0.299, 0.587, 0.114)), cr.g, cr.b, 1.0);
        return;
    }
    if (uMode > 0.5) {
        uv = figurado(uv, lat, uEyeSign);
    }
    gl_FragColor = texture2D(uTex, uv);
}
