package com.ricardopch.esfera3d.core;

/**
 * Ajuste fino de la orientación de un fotograma comparándolo con lo ya cosido.
 *
 * Se toman puntos con textura de la panorámica central en la zona que va a escribir el
 * fotograma (±2,5° alrededor de su rumbo), solo si se capturaron hace más de 300° de barrido
 * (otra vuelta, o el principio de esta al cerrarla). Se busca la pequeña corrección de rumbo
 * e inclinación que hace coincidir el fotograma con esos puntos (diferencia absoluta con la
 * media restada, búsqueda de grueso a fino).
 *
 * La zona es simétrica alrededor del rumbo del fotograma, así que el paralaje horizontal
 * (que crece hacia un lado y decrece hacia el otro) no sesga el rumbo estimado.
 */
final class FrameAligner {
    private static final int MAX_SAMPLES = 360;
    private static final double ZONE_DEG = 2.5;
    private static final double MIN_AGO_DEG = 300;

    private final float[] dx = new float[MAX_SAMPLES], dy = new float[MAX_SAMPLES], dz = new float[MAX_SAMPLES];
    private final float[] ref = new float[MAX_SAMPLES];
    private final float[] val = new float[MAX_SAMPLES];
    private final float[] gradTmp = new float[4096];

    /**
     * @return {corrección de rumbo, corrección de inclinación} en radianes, o null si no hay
     * referencia suficiente o el resultado no es fiable.
     */
    double[] align(FrameSampler s, CameraModel cam, float[] r, double phi, SweepStitcher st,
                   double yaw0, double pitch0) {
        int n = collect(cam, r, phi, st);
        if (n < 40) return null;
        // ¿Hay textura en la referencia?
        double m = 0;
        for (int i = 0; i < n; i++) m += ref[i];
        m /= n;
        double var = 0;
        for (int i = 0; i < n; i++) var += (ref[i] - m) * (ref[i] - m);
        if (Math.sqrt(var / n) < 6) return null;

        double bestA = yaw0, bestB = pitch0;
        double bestC = Double.MAX_VALUE;
        double[] coarse = new double[121];
        int k = 0;
        double step = Math.toRadians(0.6);
        for (int i = -5; i <= 5; i++) {
            for (int j = -5; j <= 5; j++) {
                double a = yaw0 + i * step, b = pitch0 + j * step;
                double c = cost(s, cam, r, phi, a, b, n);
                coarse[k++] = c;
                if (c < bestC) {
                    bestC = c;
                    bestA = a;
                    bestB = b;
                }
            }
        }
        if (bestC == Double.MAX_VALUE) return null;
        java.util.Arrays.sort(coarse);
        double median = coarse[60];
        if (!(bestC < 0.6 * median)) return null;
        double[] steps = {Math.toRadians(0.2), Math.toRadians(0.07), Math.toRadians(0.025)};
        for (double sp : steps) {
            double ca = bestA, cb = bestB;
            for (int i = -3; i <= 3; i++) {
                for (int j = -3; j <= 3; j++) {
                    if (i == 0 && j == 0) continue;
                    double a = ca + i * sp, b = cb + j * sp;
                    double c = cost(s, cam, r, phi, a, b, n);
                    if (c < bestC) {
                        bestC = c;
                        bestA = a;
                        bestB = b;
                    }
                }
            }
        }
        double lim = Math.toRadians(6);
        if (Math.abs(bestA) > lim || Math.abs(bestB) > lim) return null;
        return new double[]{bestA, bestB};
    }

    /** Reúne puntos de referencia con textura; devuelve cuántos. */
    private int collect(CameraModel cam, float[] r, double phi, SweepStitcher st) {
        PanoBuffer mono = st.mono;
        int W = mono.w, H = mono.h;
        double[] lat = SweepStitcher.latitudeRange(cam, r);
        // Solo la parte central de la foto en vertical (evita bordes)
        double half = Math.atan(cam.height * 0.5 / cam.fy) * 0.85;
        double pitch = Mat3.cameraPitch(r);
        double lo = Math.max(lat[0], pitch - half), hi = Math.min(lat[1], pitch + half);
        if (hi <= lo) return 0;
        int y0 = Math.max(2, (int) Math.floor((0.5 - hi / Math.PI) * H));
        int y1 = Math.min(H - 3, (int) Math.ceil((0.5 - lo / Math.PI) * H));
        double zone = Math.toRadians(ZONE_DEG);
        int xa = (int) Math.floor(((phi - zone) / (2 * Math.PI) + 0.5) * W);
        int xb = (int) Math.ceil(((phi + zone) / (2 * Math.PI) + 0.5) * W);
        int stepY = Math.max(1, (y1 - y0) / 90);
        int stepX = Math.max(1, (xb - xa) / 24);
        int n = 0;
        float thr = 0;
        for (int pass = 0; pass < 2; pass++) {
            n = 0;
            for (int y = y0; y <= y1; y += stepY) {
                for (int x = xa; x <= xb; x += stepX) {
                    int xi = ((x % W) + W) % W;
                    int idx = y * W + xi;
                    if (!st.isReference(idx, MIN_AGO_DEG)) continue;
                    int xl = ((xi - 2) % W + W) % W, xr = (xi + 2) % W;
                    if (!st.isReference(y * W + xl, MIN_AGO_DEG) || !st.isReference(y * W + xr, MIN_AGO_DEG)
                            || !st.isReference((y - 2) * W + xi, MIN_AGO_DEG) || !st.isReference((y + 2) * W + xi, MIN_AGO_DEG))
                        continue;
                    float gx = gray(mono.color[y * W + xr]) - gray(mono.color[y * W + xl]);
                    float gy = gray(mono.color[(y + 2) * W + xi]) - gray(mono.color[(y - 2) * W + xi]);
                    float g = Math.abs(gx) + Math.abs(gy);
                    if (pass == 0) {
                        if (n < gradTmp.length) gradTmp[n] = g;
                        n++;
                        continue;
                    }
                    if (g < thr || n >= MAX_SAMPLES) continue;
                    float cl = st.cosLat(y);
                    dx[n] = cl * st.sinLon(xi);
                    dy[n] = cl * st.cosLon(xi);
                    dz[n] = st.sinLat(y);
                    ref[n] = gray(mono.color[idx]);
                    n++;
                }
            }
            if (pass == 0) {
                if (n < 40) return 0;
                // Umbral: quedarse con los puntos de más textura (aprox. los MAX_SAMPLES mejores)
                int m = Math.min(n, gradTmp.length);
                float[] g = java.util.Arrays.copyOf(gradTmp, m);
                java.util.Arrays.sort(g);
                double keep = Math.min(1.0, MAX_SAMPLES / (double) n);
                thr = g[(int) Math.max(0, Math.min(m - 1, Math.floor(m * (1 - keep * 0.95))))];
                thr = Math.max(thr, 8f);
            }
        }
        return n;
    }

    private double cost(FrameSampler s, CameraModel cam, float[] r, double phi, double a, double b, int n) {
        float[] q = SweepStitcher.correction(phi, a, b);
        float[] rc = Mat3.mul(q, r);
        final float r0 = rc[0], r1 = rc[1], r2 = rc[2], r3 = rc[3], r4 = rc[4], r5 = rc[5], r6 = rc[6], r7 = rc[7], r8 = rc[8];
        int valid = 0;
        double sumRef = 0, sumVal = 0;
        for (int i = 0; i < n; i++) {
            float x = dx[i], y = dy[i], z = dz[i];
            float ux = r0 * x + r3 * y + r6 * z;
            float uy = r1 * x + r4 * y + r7 * z;
            float uz = r2 * x + r5 * y + r8 * z;
            val[i] = -1;
            if (uz > -0.05f) continue;
            float inv = -1f / uz;
            float u = cam.cx + cam.fx * ux * inv, v = cam.cy - cam.fy * uy * inv;
            int c = s.sample(u, v);
            if (c == 0) continue;
            float g = gray(c);
            val[i] = g;
            sumRef += ref[i];
            sumVal += g;
            valid++;
        }
        if (valid < n * 0.6) return Double.MAX_VALUE;
        double off = (sumVal - sumRef) / valid;
        double acc = 0;
        for (int i = 0; i < n; i++) {
            if (val[i] < 0) continue;
            acc += Math.abs(val[i] - off - ref[i]);
        }
        return acc / valid;
    }

    static float gray(int c) {
        return 0.299f * ((c >> 16) & 0xFF) + 0.587f * ((c >> 8) & 0xFF) + 0.114f * (c & 0xFF);
    }
}
