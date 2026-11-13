package com.ricardopch.esfera3d.core;

/** Retoques finales de las panorámicas: rellenar huecos, suavizar polos, miniaturas. */
public final class PanoPost {
    private PanoPost() {}

    /**
     * Rellena los píxeles vacíos: primero los huecos estrechos de cada fila (interpolando
     * entre los vecinos) y después las zonas grandes (cénit, nadir) con una versión
     * difuminada de lo que las rodea.
     */
    public static void fillHoles(PanoBuffer b) {
        fillRowGaps(b, Math.max(8, b.w / 128));
        fillPullPush(b);
    }

    static void fillRowGaps(PanoBuffer b, int maxGap) {
        final int w = b.w;
        for (int y = 0; y < b.h; y++) {
            int row = y * w;
            int start = -1;
            for (int x = 0; x < w; x++) {
                if (!b.isEmpty(row + x)) {
                    start = x;
                    break;
                }
            }
            if (start < 0) continue;
            int x = start;
            int walked = 0;
            while (walked < w) {
                int nx = (x + 1) % w;
                walked++;
                if (!b.isEmpty(row + nx)) {
                    x = nx;
                    continue;
                }
                // Hueco que empieza en nx: buscar su final
                int len = 0;
                int e = nx;
                while (b.isEmpty(row + e) && len < w) {
                    e = (e + 1) % w;
                    len++;
                }
                if (len <= maxGap) {
                    int ca = b.color[row + x], cb = b.color[row + e];
                    for (int k = 1; k <= len; k++) {
                        float t = k / (float) (len + 1);
                        int idx = row + (x + k) % w;
                        b.color[idx] = lerpColor(ca, cb, t);
                        b.score[idx] = (byte) 254;
                    }
                }
                walked += len;
                x = e;
            }
        }
    }

    static void fillPullPush(PanoBuffer b) {
        final int f = 4;
        final int lw = Math.max(2, b.w / f), lh = Math.max(1, b.h / f);
        boolean any = false;
        for (int i = 0; i < b.score.length; i++) {
            if (b.isEmpty(i)) {
                any = true;
                break;
            }
        }
        if (!any) return;

        float[] r = new float[lw * lh], g = new float[lw * lh], bl = new float[lw * lh], wt = new float[lw * lh];
        for (int y = 0; y < b.h; y++) {
            int ly = Math.min(lh - 1, y / f);
            for (int x = 0; x < b.w; x++) {
                int i = y * b.w + x;
                if (b.isEmpty(i)) continue;
                int li = ly * lw + Math.min(lw - 1, x / f);
                int c = b.color[i];
                r[li] += (c >> 16) & 0xFF;
                g[li] += (c >> 8) & 0xFF;
                bl[li] += c & 0xFF;
                wt[li] += 1;
            }
        }
        for (int i = 0; i < wt.length; i++) {
            if (wt[i] > 0) {
                r[i] /= wt[i];
                g[i] /= wt[i];
                bl[i] /= wt[i];
                wt[i] = Math.min(1f, wt[i] / (f * f * 0.5f));
            }
        }
        pullPush(r, g, bl, wt, lw, lh);
        // Volcar a resolución completa en los píxeles vacíos (interpolación bilineal)
        for (int y = 0; y < b.h; y++) {
            float fy = (y + 0.5f) / f - 0.5f;
            int y0 = (int) Math.floor(fy);
            float ay = fy - y0;
            int ya = clamp(y0, 0, lh - 1), yb = clamp(y0 + 1, 0, lh - 1);
            for (int x = 0; x < b.w; x++) {
                int i = y * b.w + x;
                if (!b.isEmpty(i)) continue;
                float fx = (x + 0.5f) / f - 0.5f;
                int x0 = (int) Math.floor(fx);
                float ax = fx - x0;
                int xa = ((x0 % lw) + lw) % lw, xb = (xa + 1) % lw;
                int i00 = ya * lw + xa, i10 = ya * lw + xb, i01 = yb * lw + xa, i11 = yb * lw + xb;
                float rr = bil(r, i00, i10, i01, i11, ax, ay);
                float gg = bil(g, i00, i10, i01, i11, ax, ay);
                float bb = bil(bl, i00, i10, i01, i11, ax, ay);
                b.color[i] = rgb(rr, gg, bb);
                b.score[i] = (byte) 254;
            }
        }
    }

    /** Relleno piramidal: en cada nivel, lo que falta se toma del nivel más grueso. */
    static void pullPush(float[] r, float[] g, float[] b, float[] w, int lw, int lh) {
        if (lw <= 2 || lh <= 1) {
            float sr = 0, sg = 0, sb = 0, sw = 0;
            for (int i = 0; i < w.length; i++) {
                sr += r[i] * w[i];
                sg += g[i] * w[i];
                sb += b[i] * w[i];
                sw += w[i];
            }
            if (sw <= 0) {
                sr = sg = sb = 40;
                sw = 1;
            }
            for (int i = 0; i < w.length; i++) {
                float a = w[i];
                r[i] = r[i] * a + (sr / sw) * (1 - a);
                g[i] = g[i] * a + (sg / sw) * (1 - a);
                b[i] = b[i] * a + (sb / sw) * (1 - a);
                w[i] = 1;
            }
            return;
        }
        int cw = lw / 2, ch = Math.max(1, lh / 2);
        float[] cr = new float[cw * ch], cg = new float[cw * ch], cb = new float[cw * ch], cwt = new float[cw * ch];
        for (int y = 0; y < lh; y++) {
            int cy = Math.min(ch - 1, y / 2);
            for (int x = 0; x < lw; x++) {
                int ci = cy * cw + Math.min(cw - 1, x / 2);
                int i = y * lw + x;
                float a = w[i];
                cr[ci] += r[i] * a;
                cg[ci] += g[i] * a;
                cb[ci] += b[i] * a;
                cwt[ci] += a;
            }
        }
        for (int i = 0; i < cwt.length; i++) {
            if (cwt[i] > 0) {
                cr[i] /= cwt[i];
                cg[i] /= cwt[i];
                cb[i] /= cwt[i];
                cwt[i] = Math.min(1f, cwt[i]);
            }
        }
        pullPush(cr, cg, cb, cwt, cw, ch);
        for (int y = 0; y < lh; y++) {
            float fy = (y + 0.5f) / 2 - 0.5f;
            int y0 = (int) Math.floor(fy);
            float ay = fy - y0;
            int ya = clamp(y0, 0, ch - 1), yb = clamp(y0 + 1, 0, ch - 1);
            for (int x = 0; x < lw; x++) {
                int i = y * lw + x;
                float a = w[i];
                if (a >= 1) continue;
                float fx = (x + 0.5f) / 2 - 0.5f;
                int x0 = (int) Math.floor(fx);
                float ax = fx - x0;
                int xa = ((x0 % cw) + cw) % cw, xb = (xa + 1) % cw;
                int i00 = ya * cw + xa, i10 = ya * cw + xb, i01 = yb * cw + xa, i11 = yb * cw + xb;
                r[i] = r[i] * a + bil(cr, i00, i10, i01, i11, ax, ay) * (1 - a);
                g[i] = g[i] * a + bil(cg, i00, i10, i01, i11, ax, ay) * (1 - a);
                b[i] = b[i] * a + bil(cb, i00, i10, i01, i11, ax, ay) * (1 - a);
                w[i] = 1;
            }
        }
    }

    /**
     * Cerca de los polos el estéreo omnidireccional no funciona bien (al mirar arriba o
     * abajo los ojos no saben "hacia dónde" están separados): se funde con la imagen central.
     */
    public static void poleBlend(int[] left, int[] right, int[] mono, int w, int h,
                                 double startDeg, double endDeg) {
        for (int y = 0; y < h; y++) {
            double lat = Math.abs(Math.toDegrees(PanoBuffer.latOf(y, h)));
            if (lat <= startDeg) continue;
            float t = (float) smooth((lat - startDeg) / (endDeg - startDeg));
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int i = row + x;
                left[i] = lerpColor(left[i], mono[i], t);
                right[i] = lerpColor(right[i], mono[i], t);
            }
        }
    }

    /**
     * Corrige la perspectiva vertical. Al girar con el brazo estirado, cada franja se ve
     * desde la posición del móvil (R metros por delante de los ojos), así que los objetos
     * cercanos salen estirados en vertical. Conocida la distancia horizontal d de cada
     * punto, el punto que desde los ojos está a la elevación λc aparece en la franja a la
     * elevación λp con tan λp = tan λc · d / (d − R).
     *
     * Modifica las imágenes y el mapa q in situ.
     */
    public static void correctVerticalParallax(int[][] images, int w, int h, byte[] q, int qw, int qh,
                                               double armRadius) {
        if (armRadius <= 0.01) return;
        // Para la deformación basta la profundidad "a grandes rasgos": se suaviza para que
        // los errores locales del mapa no ondulen la imagen.
        byte[] qSmooth = smoothBytes(q, qw, qh, Math.max(3, qw / 64));
        // Para cada fila de salida y cada columna del mapa q, fila de origen (en píxeles de la imagen)
        float[] srcRow = new float[qw * h];
        for (int y = 0; y < h; y++) {
            double latC = PanoBuffer.latOf(y, h);
            double t = Math.tan(latC);
            for (int qx = 0; qx < qw; qx++) {
                double latS = latC;
                for (int it = 0; it < 3; it++) {
                    double qq = sampleQRow(qSmooth, qw, qh, qx, latS);
                    double dh = qq < 1e-4 ? 1e4 : DepthEstimator.DMIN * Math.cos(latS) / qq;
                    dh = Math.max(dh, armRadius + 0.25);
                    double latP = Math.atan(t * dh / (dh - armRadius));
                    latS = latP;
                }
                // Lejos del horizonte la corrección es muy sensible a errores de profundidad:
                // se atenúa suavemente (la deformación sigue siendo monótona, sin pliegues).
                double wgt = 1 - smooth((Math.abs(Math.toDegrees(latC)) - 30) / 25.0);
                latS = latC + (latS - latC) * wgt;
                srcRow[y * qw + qx] = (float) ((0.5 - latS / Math.PI) * h - 0.5);
            }
        }
        for (int[] img : images) {
            if (img == null) continue;
            int[] src = img.clone();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    float fq = (x + 0.5f) * qw / w - 0.5f;
                    int q0 = (int) Math.floor(fq);
                    float aq = fq - q0;
                    int qa = ((q0 % qw) + qw) % qw, qb = (qa + 1) % qw;
                    float sy = srcRow[y * qw + qa] * (1 - aq) + srcRow[y * qw + qb] * aq;
                    int y0 = (int) Math.floor(sy);
                    float ay = sy - y0;
                    int ya = clamp(y0, 0, h - 1), yb = clamp(y0 + 1, 0, h - 1);
                    img[y * w + x] = lerpColor(src[ya * w + x], src[yb * w + x], ay);
                }
            }
        }
        // El propio mapa q se deforma igual
        byte[] qs = q.clone();
        for (int qy = 0; qy < qh; qy++) {
            int y = Math.min(h - 1, (int) ((qy + 0.5) * h / qh));
            for (int qx = 0; qx < qw; qx++) {
                float sy = srcRow[y * qw + qx];
                float syq = (sy + 0.5f) * qh / h - 0.5f;
                int y0 = (int) Math.floor(syq);
                float ay = syq - y0;
                int ya = clamp(y0, 0, qh - 1), yb = clamp(y0 + 1, 0, qh - 1);
                q[qy * qw + qx] = (byte) Math.round((qs[ya * qw + qx] & 0xFF) * (1 - ay) + (qs[yb * qw + qx] & 0xFF) * ay);
            }
        }
    }

    static byte[] smoothBytes(byte[] q, int w, int h, int rad) {
        float[] f = new float[q.length];
        for (int i = 0; i < q.length; i++) f[i] = q[i] & 0xFF;
        f = DepthEstimator.boxBlur(f, w, h, rad);
        f = DepthEstimator.boxBlur(f, w, h, rad);
        byte[] out = new byte[q.length];
        for (int i = 0; i < q.length; i++) out[i] = (byte) Math.max(0, Math.min(255, Math.round(f[i])));
        return out;
    }

    private static double sampleQRow(byte[] q, int qw, int qh, int qx, double lat) {
        double py = (0.5 - lat / Math.PI) * qh - 0.5;
        int y0 = (int) Math.floor(py);
        double a = py - y0;
        int ya = clamp(y0, 0, qh - 1), yb = clamp(y0 + 1, 0, qh - 1);
        return ((q[ya * qw + qx] & 0xFF) * (1 - a) + (q[yb * qw + qx] & 0xFF) * a) / 255.0;
    }

    /**
     * Corrige la perspectiva vertical con una distancia por columna (la de las paredes y
     * muebles en esa dirección, muy suavizada). Al girar con el brazo estirado, cada franja se
     * ve desde R metros por delante de los ojos y lo cercano sale estirado en vertical; un punto
     * que desde los ojos está a la elevación λc aparece a λp con tan λp = tan λc · d / (d − R).
     * Lejos del horizonte la corrección se atenúa. Modifica las imágenes y el mapa q in situ.
     */
    public static void correctVerticalByColumns(int[][] images, int w, int h, float[] colDist,
                                                byte[] q, int qw, int qh, double armRadius) {
        if (colDist == null || armRadius <= 0.01) return;
        int cw = colDist.length;
        // Fila de origen para cada (fila de salida, columna del mapa de distancias)
        float[] srcRow = new float[cw * h];
        for (int cx = 0; cx < cw; cx++) {
            double d = Math.max(armRadius + 0.35, colDist[cx]);
            double k = d / (d - armRadius);
            for (int y = 0; y < h; y++) {
                double latC = PanoBuffer.latOf(y, h);
                double latP = Math.atan(Math.tan(latC) * k);
                double wgt = 1 - smooth((Math.abs(Math.toDegrees(latC)) - 35) / 30.0);
                double latS = latC + (latP - latC) * wgt;
                srcRow[y * cw + cx] = (float) ((0.5 - latS / Math.PI) * h - 0.5);
            }
        }
        for (int[] img : images) {
            if (img == null) continue;
            int[] col = new int[h];
            for (int x = 0; x < w; x++) {
                float fc = (x + 0.5f) * cw / w - 0.5f;
                int c0 = (int) Math.floor(fc);
                float ac = fc - c0;
                int ca = ((c0 % cw) + cw) % cw, cb = (ca + 1) % cw;
                for (int y = 0; y < h; y++) col[y] = img[y * w + x];
                for (int y = 0; y < h; y++) {
                    float sy = srcRow[y * cw + ca] * (1 - ac) + srcRow[y * cw + cb] * ac;
                    int y0 = (int) Math.floor(sy);
                    float ay = sy - y0;
                    int ya = clamp(y0, 0, h - 1), yb = clamp(y0 + 1, 0, h - 1);
                    img[y * w + x] = lerpColor(col[ya], col[yb], ay);
                }
            }
        }
        if (q != null) {
            int[] col = new int[qh];
            for (int qx = 0; qx < qw; qx++) {
                int cx = Math.min(cw - 1, qx * cw / qw);
                for (int y = 0; y < qh; y++) col[y] = q[y * qw + qx] & 0xFF;
                for (int y = 0; y < qh; y++) {
                    int yy = Math.min(h - 1, (int) ((y + 0.5) * h / qh));
                    float sy = (srcRow[yy * cw + cx] + 0.5f) * qh / h - 0.5f;
                    int y0 = (int) Math.floor(sy);
                    float ay = sy - y0;
                    int ya = clamp(y0, 0, qh - 1), yb = clamp(y0 + 1, 0, qh - 1);
                    q[y * qw + qx] = (byte) Math.round(col[ya] * (1 - ay) + col[yb] * ay);
                }
            }
        }
    }

    /**
     * Ajuste automático de niveles: estira el contraste entre los percentiles 0,5 y 99,5 y
     * aplica una curva para que el tono medio quede en torno al 42 %. La misma curva para
     * todas las imágenes (los dos ojos deben verse iguales). Devuelve la curva aplicada.
     */
    public static int[] autoLevels(int[] ref, int[]... images) {
        int[] hist = new int[256];
        int step = Math.max(1, ref.length / 400_000);
        int n = 0;
        for (int i = 0; i < ref.length; i += step) {
            int c = ref[i];
            int l = (int) (0.299 * ((c >> 16) & 0xFF) + 0.587 * ((c >> 8) & 0xFF) + 0.114 * (c & 0xFF));
            hist[l]++;
            n++;
        }
        int lo = percentile(hist, n, 0.005), hi = percentile(hist, n, 0.995), med = percentile(hist, n, 0.5);
        lo = Math.min(lo, 16);
        hi = Math.max(hi, 200);
        // No estirar más de 2,5 veces
        if (hi - lo < 255 / 2.5) {
            int c = (lo + hi) / 2;
            lo = Math.max(0, c - (int) (255 / 5.0));
            hi = Math.min(255, lo + (int) (255 / 2.5));
        }
        double m = Math.max(0.02, Math.min(0.98, (med - lo) / (double) (hi - lo)));
        double gamma = Math.log(0.42) / Math.log(m);
        // Solo se aclara (si la toma quedó oscura); nunca se oscurece una imagen bien expuesta
        gamma = Math.max(0.5, Math.min(1.0, gamma));
        int[] lut = new int[256];
        for (int v = 0; v < 256; v++) {
            double t = Math.max(0, Math.min(1, (v - lo) / (double) (hi - lo)));
            lut[v] = (int) Math.round(255 * Math.pow(t, gamma));
        }
        for (int[] img : images) {
            if (img == null) continue;
            for (int i = 0; i < img.length; i++) {
                int c = img[i];
                img[i] = 0xFF000000 | (lut[(c >> 16) & 0xFF] << 16) | (lut[(c >> 8) & 0xFF] << 8) | lut[c & 0xFF];
            }
        }
        return lut;
    }

    private static int percentile(int[] hist, int n, double p) {
        long target = Math.round(n * p), acc = 0;
        for (int v = 0; v < 256; v++) {
            acc += hist[v];
            if (acc >= target) return v;
        }
        return 255;
    }

    /**
     * Pasa la relación R/d medida por rumbo del sensor a «distancia por columna» de la
     * panorámica (para {@link #correctVerticalByColumns}), rellenando huecos y suavizando.
     *
     * @param ratio         R/d por tramos de rumbo del sensor desde −180° (NaN = sin dato)
     * @param refHeadingRad rumbo del sensor que corresponde a la longitud 0 de la panorámica
     * @return distancias (m) para cw columnas repartidas en los 360°, o null si no hay datos
     */
    public static float[] ratioToColumnDistance(float[] ratio, double refHeadingRad, double armRadius, int cw) {
        int n = ratio.length, good = 0;
        for (float v : ratio) if (!Float.isNaN(v)) good++;
        if (good < n / 3) return null;
        // Un único valor para toda la vuelta (la mediana): una corrección distinta en cada
        // dirección ondularía las líneas rectas cuando hay objetos cercanos y lejanos juntos.
        float[] v = new float[good];
        int k = 0;
        for (float r : ratio) if (!Float.isNaN(r)) v[k++] = r;
        java.util.Arrays.sort(v);
        float rho = Math.min(0.3f, v[good / 2]);
        if (rho < 0.02f) return null;
        float[] out = new float[cw];
        java.util.Arrays.fill(out, (float) (armRadius / rho));
        return out;
    }

    /** Pasos finales comunes: huecos, profundidad, perspectiva vertical, niveles y polos. */
    public static DepthEstimator.Result finish(PanoBuffer left, PanoBuffer right, PanoBuffer mono, float[] columnDistance,
                                               double armRadius, double eyeRadius, double cameraHeight,
                                               DepthEstimator.Progress progress) {
        int w = mono.w, h = mono.h;
        fillHoles(left);
        fillHoles(right);
        fillHoles(mono);
        DepthEstimator.Result depth = DepthEstimator.estimate(left.color, right.color, mono.color, w, h, eyeRadius, cameraHeight, progress);
        if (columnDistance != null) {
            correctVerticalByColumns(new int[][]{left.color, right.color, mono.color}, w, h, columnDistance,
                    depth.q, depth.w, depth.h, armRadius);
        }
        autoLevels(mono.color, left.color, right.color, mono.color);
        poleBlend(left.color, right.color, mono.color, w, h, 60, 75);
        return depth;
    }

    /** Reduce una panorámica a tw × th promediando bloques. */
    public static int[] downsample(int[] src, int w, int h, int tw, int th) {
        int[] out = new int[tw * th];
        for (int ty = 0; ty < th; ty++) {
            int sy0 = ty * h / th, sy1 = Math.max(sy0 + 1, (ty + 1) * h / th);
            for (int tx = 0; tx < tw; tx++) {
                int sx0 = tx * w / tw, sx1 = Math.max(sx0 + 1, (tx + 1) * w / tw);
                int stepY = Math.max(1, (sy1 - sy0) / 4), stepX = Math.max(1, (sx1 - sx0) / 4);
                int rr = 0, gg = 0, bb = 0, n = 0;
                for (int y = sy0; y < sy1; y += stepY) {
                    for (int x = sx0; x < sx1; x += stepX) {
                        int c = src[y * w + x];
                        rr += (c >> 16) & 0xFF;
                        gg += (c >> 8) & 0xFF;
                        bb += c & 0xFF;
                        n++;
                    }
                }
                out[ty * tw + tx] = 0xFF000000 | ((rr / n) << 16) | ((gg / n) << 8) | (bb / n);
            }
        }
        return out;
    }

    /** Vista previa rápida de una panorámica en construcción (gris oscuro donde aún no hay nada). */
    public static void preview(PanoBuffer b, int[] out, int tw, int th) {
        for (int ty = 0; ty < th; ty++) {
            int sy = Math.min(b.h - 1, (int) ((ty + 0.5) * b.h / th));
            for (int tx = 0; tx < tw; tx++) {
                int sx = Math.min(b.w - 1, (int) ((tx + 0.5) * b.w / tw));
                int i = sy * b.w + sx;
                out[ty * tw + tx] = b.isEmpty(i) ? 0xFF202428 : b.color[i];
            }
        }
    }

    static double smooth(double t) {
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        return t * t * (3 - 2 * t);
    }

    public static int lerpColor(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | ((int) (ar + (br - ar) * t + 0.5f) << 16)
                | ((int) (ag + (bg - ag) * t + 0.5f) << 8) | (int) (ab + (bb - ab) * t + 0.5f);
    }

    static float bil(float[] a, int i00, int i10, int i01, int i11, float ax, float ay) {
        return (a[i00] * (1 - ax) + a[i10] * ax) * (1 - ay) + (a[i01] * (1 - ax) + a[i11] * ax) * ay;
    }

    static int rgb(float r, float g, float b) {
        int ri = clamp((int) (r + 0.5f), 0, 255), gi = clamp((int) (g + 0.5f), 0, 255), bi = clamp((int) (b + 0.5f), 0, 255);
        return 0xFF000000 | (ri << 16) | (gi << 8) | bi;
    }

    static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
