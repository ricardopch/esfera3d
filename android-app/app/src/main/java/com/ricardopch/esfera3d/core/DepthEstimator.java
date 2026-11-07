package com.ricardopch.esfera3d.core;

import java.util.Arrays;

/**
 * Mapa de profundidad para el "estéreo figurado".
 *
 * 1) Si el barrido tuvo paralaje (móvil girando con el brazo estirado), se mide la
 *    disparidad entre las panorámicas izquierda y derecha (correlación normalizada por
 *    bloques, con comprobación cruzada) y se convierte en distancia.
 * 2) Donde no hay medida fiable (zonas lisas, polos) o si el barrido no tuvo paralaje,
 *    se usa un modelo de escena: suelo a la altura de los ojos, paredes a una distancia
 *    típica y techo si parece un interior.
 *
 * El resultado se guarda como q = DMIN / distancia (0 = infinito, 1 = 30 cm).
 */
public final class DepthEstimator {
    public static final float DMIN = 0.3f;
    public static final int DW = 1024, DH = 512;
    private static final int D_LO = -3, D_HI = 28;
    private static final int RAD = 5;
    private static final double BAND_DEG = 58;

    public static final class Result {
        public byte[] q;
        public int w, h;
        public boolean measured;
        public double wallDistance;
        public boolean indoor;
        public double confidentFraction;
        public double medianDisparityPx;

        public String describe() {
            return (measured ? "paralaje medida" : "modelo de escena")
                    + String.format(java.util.Locale.ROOT, ", paredes ~%.1f m, %s, fiable %.0f%%, disparidad mediana %.2f px",
                    wallDistance, indoor ? "interior" : "exterior", confidentFraction * 100, medianDisparityPx);
        }
    }

    public interface Progress {
        void onProgress(float fraction);
    }

    private DepthEstimator() {}

    public static Result estimate(int[] left, int[] right, int[] mono, int w, int h,
                                  double eyeRadius, double cameraHeight, Progress progress) {
        final int dw = DW, dh = DH;
        float[] gM = gray(mono, w, h, dw, dh);
        Result res = new Result();
        res.w = dw;
        res.h = dh;

        float[] qMeas = new float[dw * dh];
        float[] wMeas = new float[dw * dh];
        int bandY0 = (int) Math.floor((0.5 - BAND_DEG / 180.0) * dh);
        int bandY1 = (int) Math.ceil((0.5 + BAND_DEG / 180.0) * dh) - 1;
        int bandPixels = (bandY1 - bandY0 + 1) * dw;
        int nConf = 0;
        double[] dispList = new double[0];

        if (left != null && right != null) {
            float[] gL = gray(left, w, h, dw, dh);
            float[] gR = gray(right, w, h, dw, dh);
            if (progress != null) progress.onProgress(0.1f);
            Match m = match(gL, gR, dw, dh, bandY0, bandY1, progress);
            dispList = new double[bandPixels];
            for (int y = bandY0; y <= bandY1; y++) {
                double lat = PanoBuffer.latOf(y, dh);
                double cl = Math.cos(lat);
                for (int x = 0; x < dw; x++) {
                    int i = y * dw + x;
                    if (!m.ok[i]) continue;
                    double d = Math.max(0, m.disp[i]);
                    double delta = d * Math.PI / dw;
                    float q = (float) (DMIN * cl * Math.tan(delta) / eyeRadius);
                    qMeas[i] = Math.min(1f, q);
                    wMeas[i] = (float) Math.min(1, Math.max(0.05, (m.score[i] - 0.6) / 0.35));
                    dispList[nConf++] = m.disp[i];
                }
            }
        }
        res.confidentFraction = bandPixels > 0 ? nConf / (double) bandPixels : 0;
        double medDisp = 0;
        if (nConf > 0) {
            double[] dl = Arrays.copyOf(dispList, nConf);
            Arrays.sort(dl);
            medDisp = dl[nConf / 2];
        }
        res.medianDisparityPx = medDisp;
        res.measured = res.confidentFraction >= 0.03 && medDisp >= 0.8;

        // Modelo de escena
        double wall = 12;
        boolean indoor = false;
        if (res.measured) {
            double[] hd = new double[nConf];
            int n = 0;
            for (int y = bandY0; y <= bandY1; y++) {
                double lat = PanoBuffer.latOf(y, dh);
                if (Math.abs(lat) > Math.toRadians(25)) continue;
                double cl = Math.cos(lat);
                for (int x = 0; x < dw; x++) {
                    int i = y * dw + x;
                    if (wMeas[i] <= 0 || qMeas[i] <= 1e-4f) continue;
                    double dist = DMIN / qMeas[i];
                    if (n < hd.length) hd[n++] = dist * cl;
                }
            }
            if (n > 50) {
                Arrays.sort(hd, 0, n);
                wall = Math.max(1.5, Math.min(30, hd[n / 2]));
            } else {
                wall = 6;
            }
            indoor = wall < 7;
        }
        res.wallDistance = wall;
        res.indoor = indoor;
        float[] qPrior = new float[dw * dh];
        for (int y = 0; y < dh; y++) {
            double lat = PanoBuffer.latOf(y, dh);
            float qp = (float) priorQ(lat, wall, cameraHeight, indoor ? 1.2 : 0);
            Arrays.fill(qPrior, y * dw, (y + 1) * dw, qp);
        }

        float[] q;
        if (res.measured) {
            float[] num = new float[dw * dh];
            float[] den = new float[dw * dh];
            for (int i = 0; i < num.length; i++) {
                num[i] = qMeas[i];
                den[i] = wMeas[i];
            }
            fillScalar(num, den, dw, dh);
            float[] density = boxBlur(wMeas, dw, dh, 12);
            q = new float[dw * dh];
            for (int y = 0; y < dh; y++) {
                double lat = Math.abs(Math.toDegrees(PanoBuffer.latOf(y, dh)));
                double band = 1 - PanoPost.smooth((lat - (BAND_DEG - 6)) / 8.0);
                for (int x = 0; x < dw; x++) {
                    int i = y * dw + x;
                    double a = Math.min(1, density[i] * 3) * band;
                    q[i] = (float) (num[i] * a + qPrior[i] * (1 - a));
                }
            }
            if (progress != null) progress.onProgress(0.85f);
            q = crossBilateral(q, gM, dw, dh, 3, 3.0, 14.0);
            q = crossBilateral(q, gM, dw, dh, 3, 3.0, 14.0);
        } else {
            q = qPrior;
        }

        float qMax = DMIN / 0.4f;
        res.q = new byte[dw * dh];
        for (int i = 0; i < q.length; i++) {
            float v = Math.max(0, Math.min(qMax, q[i]));
            res.q[i] = (byte) Math.round(v * 255f);
        }
        if (progress != null) progress.onProgress(1f);
        return res;
    }

    /** q del modelo de escena para una latitud dada. ceiling = altura del techo sobre los ojos (0 = sin techo). */
    public static double priorQ(double lat, double wall, double camHeight, double ceiling) {
        double cl = Math.max(1e-3, Math.cos(lat));
        double d = wall / cl;
        double sl = Math.sin(lat);
        if (sl < -1e-3) d = Math.min(d, camHeight / -sl);
        if (ceiling > 0 && sl > 1e-3) d = Math.min(d, ceiling / sl);
        return DMIN / d;
    }

    // ---------------------------------------------------------------- correspondencia

    static final class Match {
        float[] disp;
        float[] score;
        boolean[] ok;
    }

    static Match match(float[] gL, float[] gR, int w, int h, int y0, int y1, Progress progress) {
        int nd = D_HI - D_LO + 1;
        int bandH = y1 - y0 + 1;
        int n = (2 * RAD + 1) * (2 * RAD + 1);
        // Sumas por ventana de L, L², R, R² (filas de la banda ± RAD)
        float[] sL = boxSum(gL, w, h, RAD, y0, y1, false);
        float[] sLL = boxSum(gL, w, h, RAD, y0, y1, true);
        float[] sR = boxSum(gR, w, h, RAD, y0, y1, false);
        float[] sRR = boxSum(gR, w, h, RAD, y0, y1, true);
        byte[][] vol = new byte[nd][];
        float[] prod = new float[w * h];
        for (int k = 0; k < nd; k++) {
            int d = D_LO + k;
            int ya = Math.max(0, y0 - RAD), yb = Math.min(h - 1, y1 + RAD);
            for (int y = ya; y <= yb; y++) {
                int row = y * w;
                for (int x = 0; x < w; x++) {
                    int xr = x - d;
                    xr = ((xr % w) + w) % w;
                    prod[row + x] = gL[row + x] * gR[row + xr];
                }
            }
            float[] sP = boxSum(prod, w, h, RAD, y0, y1, false);
            byte[] v = new byte[bandH * w];
            for (int y = y0; y <= y1; y++) {
                int row = (y - y0) * w;
                for (int x = 0; x < w; x++) {
                    int xr = x - d;
                    xr = ((xr % w) + w) % w;
                    int i = row + x, j = row + xr;
                    double varL = sLL[i] - (double) sL[i] * sL[i] / n;
                    double varR = sRR[j] - (double) sR[j] * sR[j] / n;
                    double cov = sP[i] - (double) sL[i] * sR[j] / n;
                    double z = (varL > 1e-3 && varR > 1e-3) ? cov / Math.sqrt(varL * varR) : 0;
                    int b = (int) Math.round((z + 1) * 127.5);
                    v[i] = (byte) Math.max(0, Math.min(255, b));
                }
            }
            vol[k] = v;
            if (progress != null) progress.onProgress(0.1f + 0.6f * (k + 1) / nd);
        }
        // Mejor disparidad desde la izquierda
        int[] bestL = new int[bandH * w];
        for (int i = 0; i < bestL.length; i++) {
            int bk = 0, bv = -1;
            for (int k = 0; k < nd; k++) {
                int s = vol[k][i] & 0xFF;
                if (s > bv) {
                    bv = s;
                    bk = k;
                }
            }
            bestL[i] = bk;
        }
        // Mejor disparidad desde la derecha (para la comprobación cruzada)
        int[] bestR = new int[bandH * w];
        for (int y = 0; y < bandH; y++) {
            int row = y * w;
            for (int xr = 0; xr < w; xr++) {
                int bk = 0, bv = -1;
                for (int k = 0; k < nd; k++) {
                    int x = xr + D_LO + k;
                    x = ((x % w) + w) % w;
                    int s = vol[k][row + x] & 0xFF;
                    if (s > bv) {
                        bv = s;
                        bk = k;
                    }
                }
                bestR[row + xr] = bk;
            }
        }
        Match m = new Match();
        m.disp = new float[w * h];
        m.score = new float[w * h];
        m.ok = new boolean[w * h];
        for (int y = y0; y <= y1; y++) {
            int brow = (y - y0) * w;
            for (int x = 0; x < w; x++) {
                int bi = brow + x;
                int k = bestL[bi];
                float best = (vol[k][bi] & 0xFF) / 127.5f - 1;
                int second = -1;
                for (int kk = 0; kk < nd; kk++) {
                    if (Math.abs(kk - k) <= 1) continue;
                    second = Math.max(second, vol[kk][bi] & 0xFF);
                }
                float sec = second < 0 ? -1 : second / 127.5f - 1;
                double varL = sLL[bi] - (double) sL[bi] * sL[bi] / n;
                double std = Math.sqrt(Math.max(0, varL / n));
                int d = D_LO + k;
                int xr = ((x - d) % w + w) % w;
                int dBack = D_LO + bestR[brow + xr];
                boolean consistent = Math.abs(dBack - d) <= 1;
                // Ajuste subpíxel con una parábola
                float sub = d;
                if (k > 0 && k < nd - 1) {
                    float c0 = vol[k - 1][bi] & 0xFF, c1 = vol[k][bi] & 0xFF, c2 = vol[k + 1][bi] & 0xFF;
                    float den = c0 - 2 * c1 + c2;
                    if (den < -1e-3f) sub = d + 0.5f * (c0 - c2) / den;
                }
                int i = y * w + x;
                m.disp[i] = sub;
                m.score[i] = best;
                m.ok[i] = consistent && best > 0.75f && best - sec > 0.02f && std > 3.0
                        && k > 0 && k < nd - 1;
            }
        }
        return m;
    }

    /** Suma en ventana cuadrada (horizontal circular) para las filas y0..y1; resultado indexado (y - y0) * w + x. */
    static float[] boxSum(float[] src, int w, int h, int rad, int y0, int y1, boolean square) {
        int bandH = y1 - y0 + 1;
        int ya = Math.max(0, y0 - rad), yb = Math.min(h - 1, y1 + rad);
        float[] horiz = new float[(yb - ya + 1) * w];
        for (int y = ya; y <= yb; y++) {
            int row = y * w;
            double acc = 0;
            for (int k = -rad; k <= rad; k++) {
                float v = src[row + ((k % w) + w) % w];
                acc += square ? v * v : v;
            }
            int orow = (y - ya) * w;
            for (int x = 0; x < w; x++) {
                horiz[orow + x] = (float) acc;
                float vin = src[row + (x + rad + 1) % w];
                float vout = src[row + ((x - rad) % w + w) % w];
                acc += square ? (vin * vin - vout * vout) : (vin - vout);
            }
        }
        float[] out = new float[bandH * w];
        for (int x = 0; x < w; x++) {
            double acc = 0;
            for (int k = -rad; k <= rad; k++) {
                int yy = Math.max(ya, Math.min(yb, y0 + k));
                acc += horiz[(yy - ya) * w + x];
            }
            for (int y = y0; y <= y1; y++) {
                out[(y - y0) * w + x] = (float) acc;
                int yin = Math.max(ya, Math.min(yb, y + rad + 1));
                int yout = Math.max(ya, Math.min(yb, y - rad));
                acc += horiz[(yin - ya) * w + x] - horiz[(yout - ya) * w + x];
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- utilidades

    static float[] gray(int[] src, int w, int h, int tw, int th) {
        float[] out = new float[tw * th];
        int bx = Math.max(1, w / tw), by = Math.max(1, h / th);
        for (int ty = 0; ty < th; ty++) {
            for (int tx = 0; tx < tw; tx++) {
                int sx0 = tx * w / tw, sy0 = ty * h / th;
                double acc = 0;
                int n = 0;
                for (int y = sy0; y < Math.min(h, sy0 + by); y++) {
                    for (int x = sx0; x < Math.min(w, sx0 + bx); x++) {
                        int c = src[y * w + x];
                        acc += 0.299 * ((c >> 16) & 0xFF) + 0.587 * ((c >> 8) & 0xFF) + 0.114 * (c & 0xFF);
                        n++;
                    }
                }
                out[ty * tw + tx] = (float) (acc / Math.max(1, n));
            }
        }
        return out;
    }

    /** Relleno ponderado piramidal de un mapa escalar: v = suma ponderada, w = pesos. Deja el resultado en v. */
    static void fillScalar(float[] v, float[] w, int lw, int lh) {
        float[] r = v.clone(), g = new float[v.length], b = new float[v.length], wt = w.clone();
        // Normalizar: v contiene valores, w pesos (0..1)
        for (int i = 0; i < wt.length; i++) {
            if (wt[i] <= 0) {
                r[i] = 0;
                wt[i] = 0;
            }
        }
        PanoPost.pullPush(r, g, b, wt, lw, lh);
        System.arraycopy(r, 0, v, 0, v.length);
    }

    static float[] boxBlur(float[] src, int w, int h, int rad) {
        float[] tmp = new float[w * h], out = new float[w * h];
        int n = 2 * rad + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            double acc = 0;
            for (int k = -rad; k <= rad; k++) acc += src[row + ((k % w) + w) % w];
            for (int x = 0; x < w; x++) {
                tmp[row + x] = (float) (acc / n);
                acc += src[row + (x + rad + 1) % w] - src[row + ((x - rad) % w + w) % w];
            }
        }
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                double acc = 0;
                int c = 0;
                for (int k = -rad; k <= rad; k++) {
                    int yy = y + k;
                    if (yy < 0 || yy >= h) continue;
                    acc += tmp[yy * w + x];
                    c++;
                }
                out[y * w + x] = (float) (acc / c);
            }
        }
        return out;
    }

    /** Suavizado que respeta los bordes de la imagen guía. */
    static float[] crossBilateral(float[] v, float[] guide, int w, int h, int rad, double sigS, double sigR) {
        float[] out = new float[w * h];
        double[] ws = new double[(2 * rad + 1) * (2 * rad + 1)];
        for (int dy = -rad; dy <= rad; dy++)
            for (int dx = -rad; dx <= rad; dx++)
                ws[(dy + rad) * (2 * rad + 1) + dx + rad] = Math.exp(-(dx * dx + dy * dy) / (2 * sigS * sigS));
        double inv2r = 1.0 / (2 * sigR * sigR);
        // Tabla de pesos por diferencia de gris (evita calcular exp() en cada vecino)
        double[] rangeW = new double[512];
        for (int i = 0; i < rangeW.length; i++) rangeW[i] = Math.exp(-(double) i * i * inv2r);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float g0 = guide[i];
                double acc = 0, wsum = 0;
                for (int dy = -rad; dy <= rad; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) continue;
                    for (int dx = -rad; dx <= rad; dx++) {
                        int xx = ((x + dx) % w + w) % w;
                        int j = yy * w + xx;
                        int dg = (int) Math.abs(guide[j] - g0);
                        double wt = ws[(dy + rad) * (2 * rad + 1) + dx + rad] * rangeW[Math.min(511, dg)];
                        acc += v[j] * wt;
                        wsum += wt;
                    }
                }
                out[i] = (float) (acc / wsum);
            }
        }
        return out;
    }
}
