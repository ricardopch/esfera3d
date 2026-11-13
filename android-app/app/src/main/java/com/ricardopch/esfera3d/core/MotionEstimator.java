package com.ricardopch.esfera3d.core;

/**
 * Mide con la imagen el giro real entre dos fotogramas y devuelve en qué se diferencia del
 * que dice el giroscopio.
 *
 * Hace falta porque la imagen no sigue exactamente al giroscopio: el estabilizador óptico de
 * la cámara compensa parte del temblor de la mano, y hay pequeños desfases de tiempo. Si se
 * cose solo con el giroscopio, cada franja queda un poco más arriba o más abajo que la vecina
 * (efecto «peine»).
 *
 * Método: se eligen bloques con textura repartidos por el fotograma anterior, se busca cada
 * uno en el actual alrededor de donde lo predice el giroscopio (suma de diferencias al
 * cuadrado, con ajuste subpíxel) y se ajusta por mínimos cuadrados robustos el pequeño giro
 * residual (3 ángulos) que explica los desplazamientos.
 */
public final class MotionEstimator {
    private final int w, h;
    private final float f, cx, cy;
    private static final int HALF = 6;      // bloque de 13 × 13
    private static final int RAD = 5;       // búsqueda ± 5 px
    private static final int CELL = 36;
    private final int margin = HALF + RAD + 3;

    private final float[] gx, gy;
    private final int maxBlocks;
    private final float[] bu, bv, du, dv, wt, ox0, oy0;
    private double keystone;
    private boolean keystoneOk;

    /**
     * @param w  ancho de las miniaturas en gris
     * @param h  alto
     * @param f  distancia focal en píxeles de la miniatura
     */
    public MotionEstimator(int w, int h, float f) {
        this.w = w;
        this.h = h;
        this.f = f;
        this.cx = (w - 1) * 0.5f;
        this.cy = (h - 1) * 0.5f;
        gx = new float[w * h];
        gy = new float[w * h];
        maxBlocks = ((w / CELL) + 1) * ((h / CELL) + 1);
        bu = new float[maxBlocks];
        bv = new float[maxBlocks];
        du = new float[maxBlocks];
        dv = new float[maxBlocks];
        wt = new float[maxBlocks];
        ox0 = new float[maxBlocks];
        oy0 = new float[maxBlocks];
    }

    /**
     * Tras estimate(): coeficiente «trapecio» del último par, = (giro real de rumbo) / (focal real).
     * Es el término x·y del desplazamiento vertical: no depende del paralaje ni del
     * estabilizador óptico, así que comparado con el giro del giroscopio da la focal real.
     */
    public double keystone() {
        return keystone;
    }

    public boolean keystoneOk() {
        return keystoneOk;
    }

    public int width() {
        return w;
    }

    public int height() {
        return h;
    }

    /**
     * @param prev gris del fotograma anterior (w × h, valores 0..255)
     * @param cur  gris del fotograma actual
     * @param g    giro previsto por el giroscopio, por filas: rayo_actual = g · rayo_anterior
     *             (es decir Rᵀ_actual · R_anterior, en ejes de cámara: x derecha, y arriba, z atrás)
     * @param e    salida: giro residual (rad) en ejes de la cámara actual; giro real ≈ (I + [e]×) · g
     * @return número de bloques fiables usados (0 = sin medida)
     */
    public int estimate(byte[] prev, byte[] cur, float[] g, float[] e) {
        e[0] = e[1] = e[2] = 0;
        keystoneOk = false;
        // Gradientes del fotograma anterior
        for (int y = 1; y < h - 1; y++) {
            int row = y * w;
            for (int x = 1; x < w - 1; x++) {
                int i = row + x;
                gx[i] = ((prev[i + 1] & 0xFF) - (prev[i - 1] & 0xFF)) * 0.5f;
                gy[i] = ((prev[i + w] & 0xFF) - (prev[i - w] & 0xFF)) * 0.5f;
            }
        }
        int n = 0;
        for (int cyy = margin; cyy + CELL <= h - margin + CELL / 2 && n < maxBlocks; cyy += CELL) {
            for (int cxx = margin; cxx + CELL <= w - margin + CELL / 2 && n < maxBlocks; cxx += CELL) {
                // Mejor esquina de la celda (menor autovalor del tensor de estructura)
                float best = 0;
                int bx = -1, by = -1;
                for (int y = cyy; y < Math.min(cyy + CELL, h - margin); y += 4) {
                    for (int x = cxx; x < Math.min(cxx + CELL, w - margin); x += 4) {
                        float sxx = 0, syy = 0, sxy = 0;
                        for (int dy = -HALF; dy <= HALF; dy += 2) {
                            int row = (y + dy) * w + x;
                            for (int dx = -HALF; dx <= HALF; dx += 2) {
                                float a = gx[row + dx], b = gy[row + dx];
                                sxx += a * a;
                                syy += b * b;
                                sxy += a * b;
                            }
                        }
                        float tr = sxx + syy, det = sxx * syy - sxy * sxy;
                        float lam = 0.5f * (tr - (float) Math.sqrt(Math.max(0, tr * tr - 4 * det)));
                        if (lam > best) {
                            best = lam;
                            bx = x;
                            by = y;
                        }
                    }
                }
                if (bx < 0 || best < 49 * 6f) continue; // sin textura suficiente
                // Posición prevista en el fotograma actual
                float a = (bx - cx) / f, b = -(by - cy) / f;
                float X = g[0] * a + g[1] * b - g[2];
                float Y = g[3] * a + g[4] * b - g[5];
                float Z = g[6] * a + g[7] * b - g[8];
                if (Z > -0.2f) continue;
                float pu = cx + f * X / -Z, pv = cy - f * Y / -Z;
                int iu = Math.round(pu), iv = Math.round(pv);
                if (iu < margin || iv < margin || iu >= w - margin || iv >= h - margin) continue;
                // Búsqueda
                long bestS = Long.MAX_VALUE;
                int ox = 0, oy = 0;
                long[] ssd = new long[(2 * RAD + 1) * (2 * RAD + 1)];
                for (int sy = -RAD; sy <= RAD; sy++) {
                    for (int sx = -RAD; sx <= RAD; sx++) {
                        long s = 0;
                        for (int dy = -HALF; dy <= HALF; dy++) {
                            int rp = (by + dy) * w + bx, rc = (iv + sy + dy) * w + iu + sx;
                            for (int dx = -HALF; dx <= HALF; dx++) {
                                int d = (prev[rp + dx] & 0xFF) - (cur[rc + dx] & 0xFF);
                                s += d * d;
                            }
                        }
                        ssd[(sy + RAD) * (2 * RAD + 1) + sx + RAD] = s;
                        if (s < bestS) {
                            bestS = s;
                            ox = sx;
                            oy = sy;
                        }
                    }
                }
                if (Math.abs(ox) == RAD || Math.abs(oy) == RAD) continue;
                int k = (oy + RAD) * (2 * RAD + 1) + ox + RAD;
                float s0 = ssd[k], sl = ssd[k - 1], sr = ssd[k + 1], st = ssd[k - (2 * RAD + 1)], sb = ssd[k + (2 * RAD + 1)];
                float denx = sl - 2 * s0 + sr, deny = st - 2 * s0 + sb;
                if (denx <= 1 || deny <= 1) continue;
                float subx = 0.5f * (sl - sr) / denx, suby = 0.5f * (st - sb) / deny;
                ox0[n] = bx;
                oy0[n] = by;
                bu[n] = pu;
                bv[n] = pv;
                du[n] = iu + ox + subx - pu;
                dv[n] = iv + oy + suby - pv;
                wt[n] = 1;
                n++;
            }
        }
        if (n < 8) return 0;
        // Ajuste robusto del giro residual: du = f(a·b·wx − (1+a²)·wy − b·wz), dv = −f((1+b²)·wx − a·b·wy + a·wz)
        double[] sol = new double[3];
        int good = n;
        for (int it = 0; it < 6; it++) {
            double[][] ata = new double[3][3];
            double[] atb = new double[3];
            for (int i = 0; i < n; i++) {
                double a = (bu[i] - cx) / f, b = -(bv[i] - cy) / f;
                double[] j1 = {f * a * b, -f * (1 + a * a), -f * b};
                double[] j2 = {-f * (1 + b * b), f * a * b, -f * a};
                double wgt = wt[i];
                for (int r = 0; r < 3; r++) {
                    for (int c = 0; c < 3; c++) ata[r][c] += wgt * (j1[r] * j1[c] + j2[r] * j2[c]);
                    atb[r] += wgt * (j1[r] * du[i] + j2[r] * dv[i]);
                }
            }
            if (!solve3(ata, atb, sol)) return 0;
            // Residuos y nuevos pesos (Cauchy)
            double[] res = new double[n];
            for (int i = 0; i < n; i++) {
                double a = (bu[i] - cx) / f, b = -(bv[i] - cy) / f;
                double pu = f * (a * b * sol[0] - (1 + a * a) * sol[1] - b * sol[2]);
                double pv = -f * ((1 + b * b) * sol[0] - a * b * sol[1] + a * sol[2]);
                res[i] = Math.hypot(du[i] - pu, dv[i] - pv);
            }
            double[] sorted = res.clone();
            java.util.Arrays.sort(sorted);
            double s = Math.max(0.08, 1.4826 * sorted[n / 2]);
            good = 0;
            for (int i = 0; i < n; i++) {
                double r = res[i] / (2.5 * s);
                wt[i] = (float) (1.0 / (1.0 + r * r));
                if (res[i] < 3 * s) good++;
            }
        }
        if (good < 8) return 0;
        fitKeystone(n);
        e[0] = (float) sol[0];
        e[1] = (float) sol[1];
        e[2] = (float) sol[2];
        return good;
    }

    /** Ajusta dv_total = c0 + c1·y² + c2·x·y + c3·x (x, y desde el centro; y hacia arriba) y guarda c2. */
    private void fitKeystone(int n) {
        double[][] m = new double[4][5];
        double sw = 0;
        for (int i = 0; i < n; i++) {
            if (wt[i] < 0.5f) continue;
            double x = ox0[i] - cx, y = -(oy0[i] - cy);
            double d = (bv[i] + dv[i]) - oy0[i];
            double[] b = {1, y * y * 1e-4, x * y * 1e-4, x * 1e-2};
            for (int r = 0; r < 4; r++) {
                for (int c = 0; c < 4; c++) m[r][c] += b[r] * b[c];
                m[r][4] += b[r] * d;
            }
            sw += 1;
        }
        if (sw < 20) return;
        for (int c = 0; c < 4; c++) {
            int p = c;
            for (int r = c + 1; r < 4; r++) if (Math.abs(m[r][c]) > Math.abs(m[p][c])) p = r;
            double[] t = m[c];
            m[c] = m[p];
            m[p] = t;
            if (Math.abs(m[c][c]) < 1e-9) return;
            for (int r = 0; r < 4; r++) {
                if (r == c) continue;
                double k = m[r][c] / m[c][c];
                for (int cc = c; cc < 5; cc++) m[r][cc] -= k * m[c][cc];
            }
        }
        keystone = m[2][4] / m[2][2] * 1e-4;
        keystoneOk = true;
    }

    private static boolean solve3(double[][] a, double[] b, double[] x) {
        double[][] m = new double[3][4];
        for (int i = 0; i < 3; i++) {
            System.arraycopy(a[i], 0, m[i], 0, 3);
            m[i][3] = b[i];
            m[i][i] += 1e-6;
        }
        for (int c = 0; c < 3; c++) {
            int p = c;
            for (int r = c + 1; r < 3; r++) if (Math.abs(m[r][c]) > Math.abs(m[p][c])) p = r;
            double[] t = m[c];
            m[c] = m[p];
            m[p] = t;
            if (Math.abs(m[c][c]) < 1e-9) return false;
            for (int r = 0; r < 3; r++) {
                if (r == c) continue;
                double k = m[r][c] / m[c][c];
                for (int cc = c; cc < 4; cc++) m[r][cc] -= k * m[c][cc];
            }
        }
        for (int i = 0; i < 3; i++) x[i] = m[i][3] / m[i][i];
        return true;
    }
}
