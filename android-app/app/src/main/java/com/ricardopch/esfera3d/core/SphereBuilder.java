package com.ricardopch.esfera3d.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Cosido de la foto esférica a partir de los fotogramas guardados durante el barrido.
 * Se hace al terminar la captura, con todos los datos delante:
 *
 * 1. Orientación de cada fotograma = giroscopio + corrección rápida medida en la imagen
 *    (temblor que el estabilizador óptico compensa y el giroscopio no ve).
 * 2. Cada vuelta se dibuja por separado. Para cada columna de la panorámica se MEZCLAN los
 *    dos fotogramas vecinos en rumbo (interpolación), en vez de pegar franjas con corte
 *    brusco: no hay «peine» entre franjas. El cierre de la vuelta se funde con su principio.
 * 3. Cada vuelta se alinea con lo ya compuesto comparando la zona común (desplazamiento
 *    horizontal y vertical suave a lo largo de la vuelta) y se funde con un degradado.
 *
 * Estéreo: el ojo izquierdo toma de cada fotograma la franja situada α a la derecha del
 * centro y el derecho la de α a la izquierda (estéreo omnidireccional), con la misma mezcla.
 */
public final class SphereBuilder {
    public interface Progress {
        void onProgress(float fraction);
    }

    public static final class Options {
        public int width = 4096;
        public double alphaRad = Math.toRadians(6);
        public boolean motionCorrection = true;
        public boolean ringAlignment = true;
        public double minStepDeg = 0.3;
        public double pitchTolDeg = 12;
        /** Solo para pruebas: si no es NaN, se dibuja únicamente esa vuelta. */
        public float onlyRing = Float.NaN;
    }

    public static final class Result {
        public PanoBuffer left, right, mono;
        public final StringBuilder log = new StringBuilder();
        /** Media (0..255) de la discrepancia entre fotogramas vecinos al mezclarlos: medida de calidad. */
        public double blendError;
        public int framesUsed;
        /** Rumbo del sensor (rad) que corresponde a la longitud 0 de la panorámica. */
        public double refHeadingRad;
    }

    /** Banda de latitudes de una vuelta: color y peso (0 = vacío) de cada ojo. */
    private static final class Band {
        final int y0, rows, w;
        final int[] color;
        final byte[] weight;

        Band(int y0, int rows, int w) {
            this.y0 = y0;
            this.rows = rows;
            this.w = w;
            color = new int[rows * w];
            weight = new byte[rows * w];
        }
    }

    static float SIGN = Float.parseFloat(System.getProperty("esfera.sign", "-1"));
    static boolean DEBUG = System.getProperty("esfera.debug") != null;
    private final SweepSet set;
    private final SweepSet.Store store;
    private final Options opt;
    private final int W, H;
    private final CameraModel cam;
    private float[][] rot;       // orientaciones en el sistema de la panorámica, ya corregidas
    private double[] pitchDeg, headDeg, rawHead;
    private boolean[] driftApplied;
    private final Result res = new Result();
    private final float[] sinLat, cosLat;
    private double errSum;
    private long errN;

    // Caché de fotogramas decodificados
    private final int[] cacheIdx = {-1, -1, -1};
    private final FrameSampler[] cacheS = new FrameSampler[3];
    private int cacheTick;
    private final int[] cacheUse = new int[3];

    private SphereBuilder(SweepSet set, SweepSet.Store store, Options opt) {
        this.set = set;
        this.store = store;
        this.opt = opt;
        this.W = opt.width;
        this.H = opt.width / 2;
        this.cam = set.cam;
        sinLat = new float[H];
        cosLat = new float[H];
        for (int y = 0; y < H; y++) {
            double lat = PanoBuffer.latOf(y, H);
            sinLat[y] = (float) Math.sin(lat);
            cosLat[y] = (float) Math.cos(lat);
        }
    }

    public static Result build(SweepSet set, SweepSet.Store store, Options opt, Progress progress) throws IOException {
        return new SphereBuilder(set, store, opt).run(progress);
    }

    // ------------------------------------------------------------------ proceso general

    private Result run(Progress progress) throws IOException {
        res.left = new PanoBuffer(W, H);
        res.right = new PanoBuffer(W, H);
        res.mono = new PanoBuffer(W, H);
        // En los PanoBuffer finales, score = 255 − peso (255 = vacío).
        if (set.n < 10) return res;
        prepareOrientations();

        // Vueltas, empezando por la más cercana al horizonte
        List<Float> rings = new ArrayList<>();
        for (int i = 0; i < set.n; i++) if (!rings.contains(set.nominal[i])) rings.add(set.nominal[i]);
        rings.sort((a, b) -> Float.compare(Math.abs(a), Math.abs(b)));
        int done = 0;
        if (opt.ringAlignment) correctHeadingScale(rings);
        if (!Float.isNaN(opt.onlyRing)) rings.removeIf(v -> Math.abs(v - opt.onlyRing) > 1);
        for (float nominal : rings) {
            final float base = done / (float) rings.size(), span = 1f / rings.size();
            Progress sub = progress == null ? null : f -> progress.onProgress(base + span * f);
            processRing(nominal, done == 0, sub);
            done++;
        }
        res.blendError = errN > 0 ? errSum / errN : 0;
        if (progress != null) progress.onProgress(1f);
        return res;
    }

    private void prepareOrientations() {
        int n = set.n;
        rot = new float[n][];
        pitchDeg = new double[n];
        headDeg = new double[n];
        driftApplied = new boolean[n];
        rawHead = new double[n];
        // Rumbo 0 = primer fotograma que apunta cerca de su vuelta
        int first = 0;
        for (int i = 0; i < n; i++) {
            if (Math.abs(Math.toDegrees(Mat3.cameraPitch(set.rot[i])) - set.nominal[i]) < 8) {
                first = i;
                break;
            }
        }
        double ref = Mat3.cameraHeading(set.rot[first]);
        res.refHeadingRad = ref;
        float[] rz = Mat3.rotZ(ref);
        // Parte rápida de la diferencia imagen − giroscopio: se resta su media en ± 0,5 s
        float[][] hf = new float[n][3];
        if (opt.motionCorrection) {
            int a = 0, b = 0;
            double[] sum = new double[3];
            for (int k = 0; k < n; k++) {
                long t = set.timeNs[k];
                while (b < n && set.timeNs[b] <= t + 500_000_000L) {
                    for (int c = 0; c < 3; c++) sum[c] += set.acc[b][c];
                    b++;
                }
                while (set.timeNs[a] < t - 500_000_000L) {
                    for (int c = 0; c < 3; c++) sum[c] -= set.acc[a][c];
                    a++;
                }
                int cnt = b - a;
                for (int c = 0; c < 3; c++) hf[k][c] = (float) (set.acc[k][c] - sum[c] / cnt);
            }
        }
        double rms = 0;
        for (int k = 0; k < n; k++) {
            float[] r = Mat3.mul(rz, set.rot[k]);
            if (opt.motionCorrection) {
                float sg = SIGN; r = Mat3.mul(r, rotVec(sg * hf[k][0], sg * hf[k][1], sg * hf[k][2]));
                rms += hf[k][0] * hf[k][0] + hf[k][1] * hf[k][1];
            }
            rot[k] = r;
            pitchDeg[k] = Math.toDegrees(Mat3.cameraPitch(r));
            headDeg[k] = Math.toDegrees(Mat3.cameraHeading(r));
            rawHead[k] = headDeg[k];
        }
        res.log.append(String.format(Locale.ROOT, "fotogramas %d; corrección por imagen (rms) %.3f°\n", n,
                Math.toDegrees(Math.sqrt(rms / Math.max(1, n)))));
    }

    /** Matriz de un giro pequeño dado por su vector (rad). */
    static float[] rotVec(double x, double y, double z) {
        double th = Math.sqrt(x * x + y * y + z * z);
        if (th < 1e-9) return Mat3.identity();
        double kx = x / th, ky = y / th, kz = z / th, c = Math.cos(th), s = Math.sin(th), t = 1 - c;
        return new float[]{
                (float) (t * kx * kx + c), (float) (t * kx * ky - s * kz), (float) (t * kx * kz + s * ky),
                (float) (t * kx * ky + s * kz), (float) (t * ky * ky + c), (float) (t * ky * kz - s * kx),
                (float) (t * kx * kz - s * ky), (float) (t * ky * kz + s * kx), (float) (t * kz * kz + c)};
    }

    // ------------------------------------------------------------------ una vuelta

    /** Fotogramas elegidos de una vuelta, ordenados por su posición M (grados girados desde el primero). */
    private static final class Plan {
        List<double[]> main = new ArrayList<>(), layerB = new ArrayList<>(); // {M, índice}
        double medPitch, u0, travel;
        int s, y0, y1, candidates, filled;
    }

    private Plan plan(float nominal) {
        int n = set.n;
        // Inclinación típica real de la vuelta (mediana), que puede no coincidir con la nominal
        double[] tmp = new double[n];
        int nt = 0;
        for (int k = 0; k < n; k++)
            if (set.nominal[k] == nominal && Math.abs(pitchDeg[k] - nominal) <= opt.pitchTolDeg) tmp[nt++] = pitchDeg[k];
        if (nt < 5) return null;
        Arrays.sort(tmp, 0, nt);
        Plan p = new Plan();
        p.medPitch = tmp[nt / 2];
        // Candidatos: cerca de esa inclinación y sin movimiento brusco
        int[] cand = new int[n];
        int nc = 0;
        for (int k = 0; k < n; k++) {
            if (set.nominal[k] != nominal) continue;
            if (Math.abs(pitchDeg[k] - p.medPitch) > 7) continue;
            // Velocidad en una ventana de ~0,2 s: fuera los fotogramas tomados mientras se inclina el móvil
            int a = k, b = k;
            while (a > 0 && set.timeNs[k] - set.timeNs[a] < 100_000_000L) a--;
            while (b < n - 1 && set.timeNs[b] - set.timeNs[k] < 100_000_000L) b++;
            double dt = (set.timeNs[b] - set.timeNs[a]) * 1e-9;
            if (b > a && dt < 0.6) {
                if (angleBetween(rot[a], rot[b]) / dt > 45 || Math.abs(pitchDeg[b] - pitchDeg[a]) / dt > 8) continue;
            }
            cand[nc++] = k;
        }
        if (nc < 5) return null;
        p.candidates = nc;
        // Rumbo desenrollado y sentido de giro
        double[] u = new double[nc];
        u[0] = headDeg[cand[0]];
        for (int i = 1; i < nc; i++) u[i] = u[i - 1] + SweepGuide.wrap180(headDeg[cand[i]] - headDeg[cand[i - 1]]);
        p.s = u[nc - 1] >= u[0] ? 1 : -1;
        p.u0 = u[0];
        p.travel = p.s * (u[nc - 1] - u[0]);
        // Cadena monótona: avance mínimo entre fotogramas
        List<double[]> extra = new ArrayList<>();
        double last = -1e9;
        for (int i = 0; i < nc; i++) {
            double t = p.s * (u[i] - p.u0);
            if (t >= last + opt.minStepDeg) {
                if (t < 360) p.main.add(new double[]{t, cand[i]});
                else if (t < 720) extra.add(new double[]{t - 360, cand[i]});
                last = t;
            }
        }
        // Huecos de la primera pasada que cubre la segunda
        for (double[] e : extra) {
            if (nearest(p.main, e[0]) > 1.0) {
                p.main.add(e);
                p.filled++;
            } else {
                p.layerB.add(e);
            }
        }
        p.main.sort((a, b) -> Double.compare(a[0], b[0]));
        double half = cam.vfovDeg() / 2 + 8;
        p.y0 = Math.max(0, (int) Math.floor((0.5 - Math.toRadians(p.medPitch + half) / Math.PI) * H));
        p.y1 = Math.min(H - 1, (int) Math.ceil((0.5 - Math.toRadians(p.medPitch - half) / Math.PI) * H));
        return p;
    }

    /**
     * El giroscopio suele medir de más o de menos una pequeña fracción de cada giro (error de
     * escala): tras una vuelta completa el rumbo no vuelve exactamente a su sitio, y el error se
     * acumula de una vuelta a la siguiente. Se mide en los cierres de las vueltas y se corrige
     * el rumbo de todos los fotogramas en proporción a lo girado.
     */
    private void correctHeadingScale(List<Float> rings) throws IOException {
        int n = set.n;
        double total = 0, turned = 0;
        // Se repite un par de veces: tras cada corrección los fotogramas del cierre casan mejor
        // y la medida se afina.
        for (int pass = 0; pass < 3; pass++) {
            double[] ks = new double[rings.size()];
            int nk = 0;
            double horizon = Double.NaN;
            for (float nominal : rings) {
                Plan p = plan(nominal);
                if (p == null) continue;
                double[] d = closureDrift(p.main, p.layerB, p.y0, p.y1);
                if (d == null) continue;
                ks[nk++] = d[0] * p.s / 360.0;
                if (Math.abs(nominal) < 10) horizon = d[0] * p.s / 360.0;
            }
            if (nk == 0) break;
            Arrays.sort(ks, 0, nk);
            // El cierre de la vuelta del horizonte es el más fiable (más textura); si no, la mediana
            double kappa = Double.isNaN(horizon) ? ks[nk / 2] : horizon;
            if (Math.abs(total + kappa) > 0.06) break;
            total += kappa;
            double u = 0;
            for (int k = 0; k < n; k++) {
                if (k > 0) u += SweepGuide.wrap180(rawHead[k] - rawHead[k - 1]);
                rot[k] = Mat3.mul(Mat3.rotZ(-Math.toRadians(kappa * u)), rot[k]);
                headDeg[k] = Math.toDegrees(Mat3.cameraHeading(rot[k]));
            }
            turned = u;
            if (Math.abs(kappa) * 360 < 0.3) break;
        }
        if (total == 0) {
            res.log.append("sin cierres medibles: no se corrige la escala del giroscopio\n");
            return;
        }
        res.log.append(String.format(Locale.ROOT, "escala del giroscopio corregida: %.2f%% (%.1f° por vuelta, %.1f° en todo el barrido)\n",
                total * 100, total * 360, total * turned));
    }

    private void processRing(float nominal, boolean isFirst, Progress progress) throws IOException {
        Plan p = plan(nominal);
        if (p == null) {
            res.log.append(String.format(Locale.ROOT, "vuelta %.0f°: sin fotogramas válidos\n", nominal));
            return;
        }
        List<double[]> main = p.main, layerB = p.layerB;
        final int s = p.s, y0 = p.y0, y1 = p.y1, nc = p.candidates, filled = p.filled;
        final double u0 = p.u0, medPitch = p.medPitch, travel = p.travel;
        // Lo que quede por cerrar en esta vuelta (sobre todo inclinación) se reparte de forma lineal
        if (opt.ringAlignment) {
            double[] drift = closureDrift(main, layerB, y0, y1);
            if (drift != null) {
                applyDrift(main, drift, s);
                applyDrift(layerB, drift, s);
                res.log.append(String.format(Locale.ROOT, "  cierre %.0f°: resto repartido %.2f° de rumbo, %.2f° de inclinación\n",
                        nominal, drift[0], drift[1]));
            }
        }
        // Solape para fundir el cierre con el principio: tramo continuo desde 0, hasta 20°
        double omega = 0;
        List<double[]> closing = new ArrayList<>();
        if (!layerB.isEmpty() && layerB.get(0)[0] < 3) {
            double prev = 0;
            for (double[] e : layerB) {
                if (e[0] - prev > 3 || e[0] > 20) break;
                closing.add(e);
                prev = e[0];
                omega = e[0];
            }
        }
        if (omega < 2) closing.clear();
        res.log.append(String.format(Locale.ROOT,
                "vuelta %.0f° (real %.1f°): candidatos %d, usados %d (+%d de relleno), giro %.0f° %s, cierre %.1f°\n",
                nominal, medPitch, nc, main.size(), filled, travel, s > 0 ? "→" : "←", omega));
        res.framesUsed += main.size();

        Band[] bands = {new Band(y0, y1 - y0 + 1, W), new Band(y0, y1 - y0 + 1, W), new Band(y0, y1 - y0 + 1, W)};
        double alphaDeg = Math.toDegrees(opt.alphaRad);
        double[] beta = {0, alphaDeg, -alphaDeg}; // central, ojo izquierdo, ojo derecho

        int total = main.size() + closing.size();
        int count = 0;
        // Pasada principal (circular si la vuelta está completa)
        for (int i = 0; i < main.size(); i++) {
            double[] a = main.get(i);
            double[] b = i + 1 < main.size() ? main.get(i + 1) : new double[]{main.get(0)[0] + 360, main.get(0)[1]};
            renderInterval(bands, beta, a, b, u0, s, -1);
            if (progress != null && (++count & 15) == 0) progress.onProgress(0.9f * count / total);
        }
        // Cierre fundido sobre el principio
        if (!closing.isEmpty()) {
            double[] lastMain = main.get(main.size() - 1);
            double[] prev = {lastMain[0] - 360, lastMain[1]};
            for (double[] e : closing) {
                renderInterval(bands, beta, prev, e, u0, s, omega);
                prev = e;
                if (progress != null && (++count & 15) == 0) progress.onProgress(0.9f * count / total);
            }
        }
        // Alineado con lo ya compuesto y fundido
        float[] dx = new float[W], dy = new float[W];
        if (!isFirst && opt.ringAlignment) alignBand(bands[0], res.mono, dx, dy, nominal);
        composite(bands[0], res.mono, dx, dy);
        composite(bands[1], res.left, dx, dy);
        composite(bands[2], res.right, dx, dy);
        if (progress != null) progress.onProgress(1f);
    }

    /** Imagen en gris a resolución reducida con máscara de validez. */
    private static final class GrayMap {
        final int w, h;
        final float[] g;
        final boolean[] v;

        GrayMap(int w, int h) {
            this.w = w;
            this.h = h;
            g = new float[w * h];
            v = new boolean[w * h];
        }
    }

    static int HP = Integer.getInteger("esfera.hp", 12), BLUR = Integer.getInteger("esfera.blur", 2);

    /** Media local (solo píxeles válidos), para suavizar antes de comparar. */
    private static void blur(GrayMap m, int rad) {
        if (rad <= 0) return;
        float[] orig = m.g.clone();
        highPass(m, rad);
        for (int i = 0; i < orig.length; i++) m.g[i] = orig[i] - m.g[i];
    }

    /** Quita la iluminación suave (resta la media local) para comparar solo la textura. */
    private static void highPass(GrayMap m, int rad) {
        int w = m.w, h = m.h;
        double[] sum = new double[(w + 1) * (h + 1)];
        int[] cnt = new int[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            double rs = 0;
            int rc = 0;
            for (int x = 0; x < w; x++) {
                if (m.v[y * w + x]) {
                    rs += m.g[y * w + x];
                    rc++;
                }
                sum[(y + 1) * (w + 1) + x + 1] = sum[y * (w + 1) + x + 1] + rs;
                cnt[(y + 1) * (w + 1) + x + 1] = cnt[y * (w + 1) + x + 1] + rc;
            }
        }
        for (int y = 0; y < h; y++) {
            int ya = Math.max(0, y - rad), yb = Math.min(h, y + rad + 1);
            for (int x = 0; x < w; x++) {
                if (!m.v[y * w + x]) continue;
                int xa = Math.max(0, x - rad), xb = Math.min(w, x + rad + 1);
                double sm = sum[yb * (w + 1) + xb] - sum[ya * (w + 1) + xb] - sum[yb * (w + 1) + xa] + sum[ya * (w + 1) + xa];
                int c = cnt[yb * (w + 1) + xb] - cnt[ya * (w + 1) + xb] - cnt[yb * (w + 1) + xa] + cnt[ya * (w + 1) + xa];
                m.g[y * w + x] -= (float) (sm / Math.max(1, c));
            }
        }
    }

    /**
     * Busca el desplazamiento (ox, oy) que mejor hace coincidir a(x, y) con b(x + ox, y + oy)
     * (correlación normalizada sobre los píxeles válidos de ambos).
     *
     * @param wrap si la x es circular
     * @return {ox, oy, correlación} con ajuste subpíxel, o null si no hay coincidencia clara
     */
    private static double[] searchShift(GrayMap a, GrayMap b, int xa, int xb, int ya, int yb, int step,
                                        int cx0, int cy0, int rangeX, int rangeY, boolean wrap, double minNcc) {
        int sw = 2 * rangeX + 1, sh = 2 * rangeY + 1;
        double[] score = new double[sw * sh];
        Arrays.fill(score, -2);
        double best = -2;
        int bx = 0, by = 0;
        int area = ((xb - xa) / step + 1) * ((yb - ya) / step + 1);
        for (int oy = cy0 - rangeY; oy <= cy0 + rangeY; oy++) {
            for (int ox = cx0 - rangeX; ox <= cx0 + rangeX; ox++) {
                double sA = 0, sB = 0, sAA = 0, sBB = 0, sAB = 0;
                int cnt = 0;
                for (int y = ya; y <= yb; y += step) {
                    int y2 = y + oy;
                    if (y2 < 0 || y2 >= b.h) continue;
                    for (int x = xa; x <= xb; x += step) {
                        int x1 = x, x2 = x + ox;
                        if (wrap) {
                            x1 = ((x1 % a.w) + a.w) % a.w;
                            x2 = ((x2 % b.w) + b.w) % b.w;
                        } else if (x1 < 0 || x1 >= a.w || x2 < 0 || x2 >= b.w) continue;
                        int ia = y * a.w + x1, ib = y2 * b.w + x2;
                        if (!a.v[ia] || !b.v[ib]) continue;
                        float A = a.g[ia], B = b.g[ib];
                        sA += A;
                        sB += B;
                        sAA += A * A;
                        sBB += B * B;
                        sAB += A * B;
                        cnt++;
                    }
                }
                if (cnt < area * 0.3 || cnt < 40) continue;
                double va = sAA - sA * sA / cnt, vb = sBB - sB * sB / cnt;
                if (va < cnt * 2.0 || vb < cnt * 2.0) continue; // sin textura
                double ncc = (sAB - sA * sB / cnt) / Math.sqrt(va * vb);
                score[(oy - cy0 + rangeY) * sw + ox - cx0 + rangeX] = ncc;
                if (ncc > best) {
                    best = ncc;
                    bx = ox;
                    by = oy;
                }
            }
        }
        if (best < minNcc) return null;
        if (Math.abs(bx - cx0) == rangeX || Math.abs(by - cy0) == rangeY) return null;
        int k = (by - cy0 + rangeY) * sw + bx - cx0 + rangeX;
        double c0 = score[k], cl = score[k - 1], cr = score[k + 1], ct = score[k - sw], cb = score[k + sw];
        double subx = 0, suby = 0;
        if (cl > -2 && cr > -2 && (cl - 2 * c0 + cr) < -1e-6) subx = 0.5 * (cl - cr) / (cl - 2 * c0 + cr);
        if (ct > -2 && cb > -2 && (ct - 2 * c0 + cb) < -1e-6) suby = 0.5 * (ct - cb) / (ct - 2 * c0 + cb);
        return new double[]{bx + subx, by + suby, best};
    }

    /** Dibuja un solo fotograma, en gris y a resolución reducida, sobre un trozo de panorámica. */
    private GrayMap patch(int idx, double lonCenterDeg, double halfWidthDeg, int y0, int y1, double degPerPx) throws IOException {
        FrameSampler fs = frame(idx);
        float[] r = rot[idx];
        int w = (int) Math.round(2 * halfWidthDeg / degPerPx), h = (int) Math.round((y1 - y0 + 1) * (180.0 / H) / degPerPx);
        GrayMap m = new GrayMap(w, h);
        if (fs == null) return m;
        double latTop = Math.toDegrees(PanoBuffer.latOf(y0, H));
        float margin = 3;
        for (int x = 0; x < w; x++) {
            double lon = Math.toRadians(lonCenterDeg - halfWidthDeg + (x + 0.5) * degPerPx);
            double sl = Math.sin(lon), cl = Math.cos(lon);
            for (int y = 0; y < h; y++) {
                double lat = Math.toRadians(latTop - (y + 0.5) * degPerPx);
                double cs = Math.cos(lat), sn = Math.sin(lat);
                double dx = cs * sl, dy = cs * cl, dz = sn;
                double X = r[0] * dx + r[3] * dy + r[6] * dz, Y = r[1] * dx + r[4] * dy + r[7] * dz, Z = r[2] * dx + r[5] * dy + r[8] * dz;
                if (Z > -0.05) continue;
                float u = (float) (cam.cx + cam.fx * X / -Z), v = (float) (cam.cy - cam.fy * Y / -Z);
                if (u < margin || v < margin || u > cam.width - 1 - margin || v > cam.height - 1 - margin) continue;
                int c = fs.sample(u, v);
                if (c == 0) continue;
                m.g[y * w + x] = FrameAligner.gray(c);
                m.v[y * w + x] = true;
            }
        }
        return m;
    }

    /**
     * Compara fotogramas del principio de la vuelta con los que apuntan al mismo sitio al final.
     *
     * @return {corrección de rumbo, corrección de inclinación} en grados para el final de la vuelta, o null
     */
    private double[] closureDrift(List<double[]> main, List<double[]> second, int y0, int y1) throws IOException {
        if (main.size() < 20) return null;
        final double degPerPx = 360.0 / 1024;
        double[] dh = new double[5], dp = new double[5];
        int got = 0;
        for (int t = 0; t < 5; t++) {
            double[] a = main.get(Math.min(main.size() - 1, t * 4));
            // El fotograma más tardío que apunta cerca del mismo rumbo
            double[] b = null;
            double bestD = 12;
            for (double[] e : second) {
                double d = Math.abs(e[0] - a[0]);
                if (d < bestD) {
                    bestD = d;
                    b = new double[]{e[0] + 360, e[1]};
                }
            }
            if (b == null) {
                double[] e = main.get(main.size() - 1 - t);
                if (360 - e[0] + a[0] < 14 && e[0] > 300) b = e;
            }
            if (b == null) continue;
            int ia = (int) a[1], ib = (int) b[1];
            double hc = headDeg[ia] + SweepGuide.wrap180(headDeg[ib] - headDeg[ia]) / 2;
            GrayMap ga = patch(ia, hc, 26, y0, y1, degPerPx), gb = patch(ib, hc, 26, y0, y1, degPerPx);
            highPass(ga, 5);
            highPass(gb, 5);
            int range = (int) Math.round(7 / degPerPx);
            double[] sft = searchShift(ga, gb, 0, ga.w - 1, 0, ga.h - 1, 2, 0, 0, range, range, false, 0.6);
            if (DEBUG) {
                double[] dbg = searchShift(ga, gb, 0, ga.w - 1, 0, ga.h - 1, 2, 0, 0, range * 2, range * 2, false, -1);
                System.out.printf("   cierre par %d-%d (M %.1f / %.1f, incl %.1f / %.1f): %s | amplio %s%n", ia, ib, a[0], b[0], pitchDeg[ia], pitchDeg[ib],
                        sft == null ? "nada" : String.format("%.2f %.2f ncc %.2f", sft[0] * degPerPx, sft[1] * degPerPx, sft[2]),
                        dbg == null ? "nada" : String.format("%.2f %.2f ncc %.2f", dbg[0] * degPerPx, dbg[1] * degPerPx, dbg[2]));
            }
            if (sft == null) continue;
            // a(x, y) ≈ b(x + ox, y + oy): el contenido de b está desplazado +ox en longitud y +oy hacia abajo
            double frac = b[0] / 360.0;
            dh[got] = -sft[0] * degPerPx / frac;
            dp[got] = sft[1] * degPerPx / frac;
            got++;
        }
        if (got < 2) return null;
        double[] h2 = Arrays.copyOf(dh, got), p2 = Arrays.copyOf(dp, got);
        Arrays.sort(h2);
        Arrays.sort(p2);
        return new double[]{h2[got / 2], p2[got / 2]};
    }

    /** Reparte la deriva a lo largo de la vuelta: corrige orientaciones y posiciones M. */
    private void applyDrift(List<double[]> list, double[] drift, int s) {
        for (double[] c : list) {
            int k = (int) c[1];
            double f = c[0] / 360.0;
            if (driftApplied[k]) continue;
            driftApplied[k] = true;
            float[] q = SweepStitcher.correction(Math.toRadians(headDeg[k]), Math.toRadians(drift[0] * f), Math.toRadians(drift[1] * f));
            rot[k] = Mat3.mul(q, rot[k]);
            headDeg[k] = Math.toDegrees(Mat3.cameraHeading(rot[k]));
            pitchDeg[k] = Math.toDegrees(Mat3.cameraPitch(rot[k]));
        }
        for (double[] c : list) c[0] *= 1 + s * drift[0] / 360.0;
    }

    private static double nearest(List<double[]> list, double m) {
        double best = 1e9;
        for (double[] c : list) {
            double d = Math.abs(c[0] - m);
            d = Math.min(d, 360 - d);
            if (d < best) best = d;
        }
        return best;
    }

    private static double angleBetween(float[] a, float[] b) {
        // traza de aᵀ·b
        double tr = 0;
        for (int i = 0; i < 9; i++) tr += a[i] * b[i];
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (tr - 1) / 2))));
    }

    private FrameSampler frame(int idx) throws IOException {
        cacheTick++;
        for (int i = 0; i < 3; i++) {
            if (cacheIdx[i] == idx) {
                cacheUse[i] = cacheTick;
                return cacheS[i];
            }
        }
        int slot = 0;
        for (int i = 1; i < 3; i++) if (cacheUse[i] < cacheUse[slot]) slot = i;
        cacheS[slot] = null;
        cacheS[slot] = store.open(idx);
        cacheIdx[slot] = idx;
        cacheUse[slot] = cacheTick;
        return cacheS[slot];
    }

    /**
     * Dibuja las columnas situadas entre los rumbos de dos fotogramas vecinos, mezclándolos.
     *
     * @param a,b   {M, índice}: posición en la vuelta (grados girados) y fotograma
     * @param omega si ≥ 0, capa de cierre: se funde sobre lo existente con peso 1 − M/omega
     */
    private void renderInterval(Band[] bands, double[] beta, double[] a, double[] b, double u0, int s, double omega)
            throws IOException {
        int ia = (int) a[1], ib = (int) b[1];
        double ma = a[0], mb = b[0];
        double gap = mb - ma;
        if (gap <= 0) return;
        FrameSampler sa = frame(ia), sb = frame(ib);
        if (sa == null || sb == null) return;
        float[] ra = rot[ia], rb = rot[ib];
        boolean wide = gap > 6;
        double[] la = SweepStitcher.latitudeRange(cam, ra), lb = SweepStitcher.latitudeRange(cam, rb);
        double latHi = Math.max(la[1], lb[1]), latLo = Math.min(la[0], lb[0]);
        final float fx = cam.fx, fy = cam.fy, ccx = cam.cx, ccy = cam.cy;
        final float halfH = cam.height * 0.5f, ramp = 0.22f * halfH;
        final float uMax = cam.width - 1.001f, vMax = cam.height - 1.001f;
        for (int e = 0; e < bands.length; e++) {
            Band band = bands[e];
            int yTop = Math.max(band.y0, (int) Math.floor((0.5 - latHi / Math.PI) * H - 0.5));
            int yBot = Math.min(band.y0 + band.rows - 1, (int) Math.ceil((0.5 - latLo / Math.PI) * H - 0.5));
            double lonA = u0 + s * ma + beta[e], lonB = u0 + s * mb + beta[e];
            double lo = Math.min(lonA, lonB), hi = Math.max(lonA, lonB);
            int xs = (int) Math.ceil((lo / 360 + 0.5) * W - 0.5), xe = (int) Math.ceil((hi / 360 + 0.5) * W - 0.5) - 1;
            for (int x = xs; x <= xe; x++) {
                double lon = ((x + 0.5) / W - 0.5) * 360;
                double t = (lon - lonA) / (lonB - lonA); // 0 en a, 1 en b
                float wa = (float) (1 - t), wb = (float) t;
                if (wide) {
                    // Hueco de cobertura (hasta 40°): cada fotograma cubre su mitad con sus columnas
                    // laterales y se funden en el tercio central. Más allá de 20° de cada uno, nada.
                    if (gap > 40 && t * gap > 20 && (1 - t) * gap > 20) continue;
                    float m = (float) Math.max(0, Math.min(1, (t - 0.35) / 0.3));
                    if (gap > 40) m = t * gap <= 20 ? 0 : 1;
                    wa = 1 - m;
                    wb = m;
                }
                float closeW = 1;
                if (omega >= 0) {
                    double m = ma + t * gap;
                    closeW = (float) Math.max(0, Math.min(1, 1 - m / omega));
                    if (closeW <= 0) continue;
                }
                int xi = ((x % W) + W) % W;
                double lr = Math.toRadians(lon);
                float sl = (float) Math.sin(lr), cl = (float) Math.cos(lr);
                // Rᵀ·(sinλ, cosλ, 0) y Rᵀ·(0, 0, 1) de cada fotograma
                float ax = ra[0] * sl + ra[3] * cl, ay = ra[1] * sl + ra[4] * cl, az = ra[2] * sl + ra[5] * cl;
                float bx = rb[0] * sl + rb[3] * cl, by = rb[1] * sl + rb[4] * cl, bz = rb[2] * sl + rb[5] * cl;
                for (int y = yTop; y <= yBot; y++) {
                    float sn = sinLat[y], cs = cosLat[y];
                    int ca = 0, cb = 0;
                    float ea = 0, eb = 0;
                    if (wa > 0) {
                        float X = cs * ax + sn * ra[6], Y = cs * ay + sn * ra[7], Z = cs * az + sn * ra[8];
                        if (Z < -0.05f) {
                            float inv = -1f / Z, uu = ccx + fx * X * inv, vv = ccy - fy * Y * inv;
                            if (uu >= 0 && vv >= 0 && uu <= uMax && vv <= vMax) {
                                ca = sa.sample(uu, vv);
                                if (ca != 0) ea = Math.min(1f, (halfH - Math.abs(vv - ccy)) / ramp);
                            }
                        }
                    }
                    if (wb > 0) {
                        float X = cs * bx + sn * rb[6], Y = cs * by + sn * rb[7], Z = cs * bz + sn * rb[8];
                        if (Z < -0.05f) {
                            float inv = -1f / Z, uu = ccx + fx * X * inv, vv = ccy - fy * Y * inv;
                            if (uu >= 0 && vv >= 0 && uu <= uMax && vv <= vMax) {
                                cb = sb.sample(uu, vv);
                                if (cb != 0) eb = Math.min(1f, (halfH - Math.abs(vv - ccy)) / ramp);
                            }
                        }
                    }
                    float pa = wa * ea, pb = wb * eb;
                    float sum = pa + pb;
                    if (sum <= 0f) continue;
                    int col;
                    if (pa > 0 && pb > 0) {
                        col = PanoPost.lerpColor(ca, cb, pb / sum);
                        if (e == 0 && (y & 3) == 0) {
                            errSum += Math.abs(((ca >> 8) & 0xFF) - ((cb >> 8) & 0xFF));
                            errN++;
                        }
                    } else {
                        col = pa > 0 ? ca : cb;
                    }
                    float wgt = wa * ea + wb * eb;
                    int idx = (y - band.y0) * W + xi;
                    int wByte = Math.max(1, Math.min(255, Math.round(wgt * 255)));
                    if (omega >= 0) {
                        int old = band.weight[idx] & 0xFF;
                        if (old != 0) {
                            col = PanoPost.lerpColor(band.color[idx], col, closeW);
                            wByte = Math.max(1, Math.round(old + (wByte - old) * closeW));
                        }
                    }
                    band.color[idx] = col;
                    band.weight[idx] = (byte) wByte;
                }
            }
        }
    }

    // ------------------------------------------------------------------ alineado entre vueltas

    /**
     * Compara la banda con lo ya compuesto en la zona común y calcula, para cada columna, el
     * desplazamiento (en píxeles) con el que hay que leer la banda: final(x, y) ← banda(x + dx, y + dy).
     */
    private void alignBand(Band band, PanoBuffer fin, float[] dxOut, float[] dyOut, float nominal) {
        final int r = Math.max(1, W / 1024);
        final int wr = W / r, hr = band.rows / r;
        if (hr < 8) return;
        GrayMap gb = new GrayMap(wr, hr), gf = new GrayMap(wr, hr);
        int[] rowBoth = new int[hr];
        for (int yy = 0; yy < hr; yy++) {
            for (int xx = 0; xx < wr; xx++) {
                float sb = 0, sf = 0;
                int nb = 0, nf = 0;
                for (int dy = 0; dy < r; dy++) {
                    int by = yy * r + dy;
                    int fyRow = (band.y0 + by) * W;
                    for (int dx = 0; dx < r; dx++) {
                        int x = xx * r + dx;
                        if ((band.weight[by * W + x] & 0xFF) >= 20) {
                            sb += FrameAligner.gray(band.color[by * W + x]);
                            nb++;
                        }
                        if (255 - (fin.score[fyRow + x] & 0xFF) >= 20) {
                            sf += FrameAligner.gray(fin.color[fyRow + x]);
                            nf++;
                        }
                    }
                }
                int i = yy * wr + xx;
                if (nb == r * r) {
                    gb.g[i] = sb / nb;
                    gb.v[i] = true;
                }
                if (nf == r * r) {
                    gf.g[i] = sf / nf;
                    gf.v[i] = true;
                }
                if (gb.v[i] && gf.v[i]) rowBoth[yy]++;
            }
        }
        int ra = -1, rb = -1;
        for (int yy = 0; yy < hr; yy++) {
            if (rowBoth[yy] >= wr * 0.3) {
                if (ra < 0) ra = yy;
                rb = yy;
            }
        }
        if (ra < 0 || rb - ra < 5) {
            res.log.append(String.format(Locale.ROOT, "  alineado %.0f°: sin zona común suficiente\n", nominal));
            return;
        }
        blur(gb, BLUR);
        blur(gf, BLUR);
        highPass(gb, HP);
        highPass(gf, HP);
        final double degPerPx = 360.0 / wr;
        // Mapa de correlación de cada zona para todos los desplazamientos de ± 6°, y camino
        // suave alrededor de la vuelta que maximiza la correlación total (programación dinámica).
        // Así una zona con poca textura o con una coincidencia falsa no descoloca el resultado.
        final int rg = (int) Math.round(6.0 / degPerPx), rgx = (int) Math.round(12.0 / degPerPx);
        final int sw = 2 * rgx + 1, sh = 2 * rg + 1, ns = sw * sh;
        final int winHalf = (int) Math.round(7.0 / degPerPx);
        final int nWin = 60;
        float[][] score = new float[nWin][];
        for (int wi = 0; wi < nWin; wi++) {
            int cxw = (int) ((wi + 0.5) * wr / nWin);
            score[wi] = nccMap(gf, gb, cxw - winHalf, cxw + winHalf, ra, rb, rgx, rg);
        }
        // Preferencia suave por los desplazamientos pequeños: con dibujos repetidos (baldas,
        // baldosas) hay varias coincidencias posibles y la buena suele ser la más cercana.
        final float prior = Float.parseFloat(System.getProperty("esfera.prior", "0.012"));
        float[][] biased = new float[nWin][ns];
        for (int wi = 0; wi < nWin; wi++)
            for (int oy = 0; oy < sh; oy++)
                for (int ox = 0; ox < sw; ox++)
                    biased[wi][oy * sw + ox] = score[wi][oy * sw + ox] - prior * (Math.abs(ox - rgx) + Math.abs(oy - rg));
        final int span = 3;
        final float penalty = 0.06f;
        int L = nWin * 2; // dos vueltas para que el camino cierre sobre sí mismo
        float[][] best = new float[L][ns];
        short[][] from = new short[L][ns];
        System.arraycopy(biased[0], 0, best[0], 0, ns);
        for (int t = 1; t < L; t++) {
            float[] sc = biased[t % nWin], prev = best[t - 1], cur = best[t];
            for (int oy = 0; oy < sh; oy++) {
                for (int ox = 0; ox < sw; ox++) {
                    float bv = -1e9f;
                    int bi = 0;
                    for (int py = Math.max(0, oy - span); py <= Math.min(sh - 1, oy + span); py++) {
                        for (int px = Math.max(0, ox - span); px <= Math.min(sw - 1, ox + span); px++) {
                            float v = prev[py * sw + px] - penalty * (Math.abs(px - ox) + Math.abs(py - oy));
                            if (v > bv) {
                                bv = v;
                                bi = py * sw + px;
                            }
                        }
                    }
                    cur[oy * sw + ox] = bv + sc[oy * sw + ox];
                    from[t][oy * sw + ox] = (short) bi;
                }
            }
        }
        int state = 0;
        for (int i = 1; i < ns; i++) if (best[L - 1][i] > best[L - 1][state]) state = i;
        int[] path = new int[L];
        for (int t = L - 1; t >= 0; t--) {
            path[t] = state;
            if (t > 0) state = from[t][state];
        }
        float[] sx = new float[nWin], sy = new float[nWin];
        boolean[] ok = new boolean[nWin];
        int nOk = 0;
        double nccSum = 0;
        for (int wi = 0; wi < nWin; wi++) {
            int st = path[wi + nWin / 2 < nWin ? wi + nWin : wi]; // tramo central de las dos vueltas
            int ox = st % sw, oy = st / sw;
            float c0 = score[wi][st];
            nccSum += Math.max(0, c0);
            sx[wi] = (ox - rgx) * r;
            sy[wi] = (oy - rg) * r;
            if (c0 < 0.4f) continue;
            // Ajuste subpíxel
            if (ox > 0 && ox < sw - 1 && oy > 0 && oy < sh - 1) {
                float cl = score[wi][st - 1], cr = score[wi][st + 1], ct = score[wi][st - sw], cb = score[wi][st + sw];
                if (cl > -0.5f && cr > -0.5f && (cl - 2 * c0 + cr) < -1e-4f) sx[wi] += 0.5f * (cl - cr) / (cl - 2 * c0 + cr) * r;
                if (ct > -0.5f && cb > -0.5f && (ct - 2 * c0 + cb) < -1e-4f) sy[wi] += 0.5f * (ct - cb) / (ct - 2 * c0 + cb) * r;
            }
            ok[wi] = true;
            nOk++;
        }
        if (nOk < 6) {
            res.log.append(String.format(Locale.ROOT, "  alineado %.0f°: solo %d zonas con coincidencia clara; no se corrige\n", nominal, nOk));
            return;
        }
        sx = smoothCircular(sx, ok);
        sy = smoothCircular(sy, ok);
        double mx = 0, my = 0, ax = 0, ay = 0;
        for (int x = 0; x < W; x++) {
            double p = (x + 0.5) * nWin / W - 0.5;
            int i0 = (int) Math.floor(p);
            float a = (float) (p - i0);
            int ia = ((i0 % nWin) + nWin) % nWin, ib = (ia + 1) % nWin;
            dxOut[x] = sx[ia] * (1 - a) + sx[ib] * a;
            dyOut[x] = sy[ia] * (1 - a) + sy[ib] * a;
            mx += dxOut[x];
            my += dyOut[x];
            ax = Math.max(ax, Math.abs(dxOut[x]));
            ay = Math.max(ay, Math.abs(dyOut[x]));
        }
        double k = 360.0 / W;
        res.log.append(String.format(Locale.ROOT,
                "  alineado %.0f°: %d/%d zonas (correlación media %.2f); desplazamiento medio %.2f° horiz., %.2f° vert. (máx. %.2f°, %.2f°)\n",
                nominal, nOk, nWin, nccSum / nWin, mx / W * k, my / W * k, ax * k, ay * k));
        if (DEBUG) for (int wi = 0; wi < nWin; wi += 2)
            System.out.printf("     zona %2d (lon %4.0f): %.2f %.2f %s%n", wi, (wi + 0.5) * 360.0 / nWin - 180, sx[wi] * k, sy[wi] * k, ok[wi] ? "" : "(interpolada)");
    }

    /** Correlación normalizada de a(x, y) con b(x + ox, y + oy) para todos los desplazamientos de ± rg. */
    private static float[] nccMap(GrayMap a, GrayMap b, int xa, int xb, int ya, int yb, int rgx, int rg) {
        int sw = 2 * rgx + 1;
        float[] out = new float[sw * (2 * rg + 1)];
        int area = (xb - xa + 1) * (yb - ya + 1);
        for (int oy = -rg; oy <= rg; oy++) {
            for (int ox = -rgx; ox <= rgx; ox++) {
                double sA = 0, sB = 0, sAA = 0, sBB = 0, sAB = 0;
                int cnt = 0;
                for (int y = ya; y <= yb; y++) {
                    int y2 = y + oy;
                    if (y2 < 0 || y2 >= b.h) continue;
                    for (int x = xa; x <= xb; x++) {
                        int x1 = ((x % a.w) + a.w) % a.w, x2 = (((x + ox) % b.w) + b.w) % b.w;
                        int ia = y * a.w + x1, ib = y2 * b.w + x2;
                        if (!a.v[ia] || !b.v[ib]) continue;
                        float A = a.g[ia], B = b.g[ib];
                        sA += A;
                        sB += B;
                        sAA += A * A;
                        sBB += B * B;
                        sAB += A * B;
                        cnt++;
                    }
                }
                float v = -0.3f;
                if (cnt >= area * 0.5) {
                    double va = sAA - sA * sA / cnt, vb = sBB - sB * sB / cnt;
                    if (va >= cnt * 2.0 && vb >= cnt * 2.0) v = (float) ((sAB - sA * sB / cnt) / Math.sqrt(va * vb));
                    else v = 0f;
                }
                out[(oy + rg) * sw + ox + rgx] = v;
            }
        }
        return out;
    }

    /** Rellena las zonas sin medida, quita valores sueltos (mediana) y suaviza, todo circular. */
    private static float[] smoothCircular(float[] v, boolean[] ok) {
        int n = v.length;
        float[] f = new float[n];
        for (int i = 0; i < n; i++) {
            if (ok[i]) {
                f[i] = v[i];
                continue;
            }
            int a = 1, b = 1;
            while (a < n && !ok[((i - a) % n + n) % n]) a++;
            while (b < n && !ok[(i + b) % n]) b++;
            float va = v[((i - a) % n + n) % n], vb = v[(i + b) % n];
            f[i] = va + (vb - va) * a / (float) (a + b);
        }
        float[] m = new float[n];
        float[] tmp = new float[5];
        for (int i = 0; i < n; i++) {
            for (int k = -2; k <= 2; k++) tmp[k + 2] = f[((i + k) % n + n) % n];
            Arrays.sort(tmp);
            m[i] = tmp[2];
        }
        for (int it = 0; it < 2; it++) {
            float[] g = new float[n];
            for (int i = 0; i < n; i++) g[i] = (m[((i - 1) % n + n) % n] + 2 * m[i] + m[(i + 1) % n]) / 4f;
            m = g;
        }
        return m;
    }

    // ------------------------------------------------------------------ fundido de una vuelta en el resultado

    private void composite(Band band, PanoBuffer fin, float[] dx, float[] dy) {
        for (int x = 0; x < W; x++) {
            float sxp = x + dx[x];
            int x0 = (int) Math.floor(sxp);
            float axf = sxp - x0;
            int xa = ((x0 % W) + W) % W, xb = (xa + 1) % W;
            float sdy = dy[x];
            for (int y = band.y0; y < band.y0 + band.rows; y++) {
                float syp = y - band.y0 + sdy;
                int yy0 = (int) Math.floor(syp);
                if (yy0 < 0 || yy0 + 1 >= band.rows) continue;
                float ayf = syp - yy0;
                int i00 = yy0 * W + xa, i10 = yy0 * W + xb, i01 = i00 + W, i11 = i10 + W;
                int w00 = band.weight[i00] & 0xFF, w10 = band.weight[i10] & 0xFF, w01 = band.weight[i01] & 0xFF, w11 = band.weight[i11] & 0xFF;
                if (w00 == 0 || w10 == 0 || w01 == 0 || w11 == 0) continue;
                float wb = (w00 * (1 - axf) + w10 * axf) * (1 - ayf) + (w01 * (1 - axf) + w11 * axf) * ayf;
                int top = PanoPost.lerpColor(band.color[i00], band.color[i10], axf);
                int bot = PanoPost.lerpColor(band.color[i01], band.color[i11], axf);
                int cb = PanoPost.lerpColor(top, bot, ayf);
                int fi = y * W + x;
                int wf = 255 - (fin.score[fi] & 0xFF);
                if (wf <= 0) {
                    fin.color[fi] = cb;
                    fin.score[fi] = (byte) (255 - Math.max(1, Math.round(wb)));
                } else {
                    // Fundido con pesos al cubo: transición más corta, menos imagen doble
                    float pb = wb * wb * wb, pf = (float) wf * wf * wf;
                    fin.color[fi] = PanoPost.lerpColor(fin.color[fi], cb, pb / (pb + pf));
                    fin.score[fi] = (byte) (255 - Math.min(255, Math.max(wf, Math.round(wb))));
                }
            }
        }
    }
}
