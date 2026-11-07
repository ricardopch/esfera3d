package com.ricardopch.esfera3d.core;

/**
 * Construye tres panorámicas equirectangulares a partir del barrido de la cámara:
 * ojo izquierdo, ojo derecho y una central (2D).
 *
 * Principio (estéreo omnidireccional "por franjas", Peleg y Ben-Ezra): si el móvil gira
 * sobre el cuerpo con el brazo estirado, la cámara recorre un círculo de radio R. Para
 * cada dirección de mirada, el rayo que pasa a la distancia r (media distancia entre ojos)
 * del centro, por su izquierda, es el que vería el ojo izquierdo. Ese rayo lo captura el
 * fotograma cuyo rumbo es (rumbo de la mirada − α), con sin α = r / R: la franja derecha de
 * cada foto forma la imagen del ojo izquierdo y la franja izquierda la del ojo derecho.
 * La panorámica central usa la franja del centro (α = 0).
 *
 * Cada fotograma rellena, en cada panorámica, una cuña de longitudes alrededor de
 * (su rumbo + α del ojo). Para cada píxel se queda el fotograma más adecuado (más cerca
 * del centro de su cuña y del centro vertical de la foto).
 */
public final class SweepStitcher {
    public final int W, H;
    public final PanoBuffer left, right, mono;
    private final double alpha;
    private final float[] sinLat, cosLat, sinLon, cosLon;

    private volatile boolean started;
    private volatile double refHeading;
    private double lastHeading;
    private boolean hasLast;
    private volatile int frames;

    // Último estado (en el sistema ya referido al primer fotograma)
    private volatile double headingDeg, pitchDeg, rollDeg;

    public SweepStitcher(int w, int h, double alphaRad) {
        this.W = w;
        this.H = h;
        this.alpha = alphaRad;
        left = new PanoBuffer(w, h);
        right = new PanoBuffer(w, h);
        mono = new PanoBuffer(w, h);
        sinLat = new float[h];
        cosLat = new float[h];
        for (int y = 0; y < h; y++) {
            double lat = PanoBuffer.latOf(y, h);
            sinLat[y] = (float) Math.sin(lat);
            cosLat[y] = (float) Math.cos(lat);
        }
        sinLon = new float[w];
        cosLon = new float[w];
        for (int x = 0; x < w; x++) {
            double lon = PanoBuffer.lonOf(x, w);
            sinLon[x] = (float) Math.sin(lon);
            cosLon[x] = (float) Math.cos(lon);
        }
    }

    /** Ángulo de la franja para una media distancia entre ojos r y un radio de giro R (metros). */
    public static double alphaFor(double eyeRadius, double armRadius) {
        // Límite de 10°: la franja tiene que quedar dentro de la foto.
        return Math.min(Math.toRadians(10), Math.asin(Math.min(0.5, eyeRadius / Math.max(0.05, armRadius))));
    }

    public double alpha() {
        return alpha;
    }

    public int framesUsed() {
        return frames;
    }

    public double headingDeg() {
        return headingDeg;
    }

    public double pitchDeg() {
        return pitchDeg;
    }

    public double rollDeg() {
        return rollDeg;
    }

    /** Rumbo del primer fotograma en el sistema del sensor (para alinear la guía). */
    public double referenceHeading() {
        return refHeading;
    }

    /** Pasa una orientación del sensor al sistema de la panorámica (rumbo 0 = primer fotograma). */
    public float[] toPanoFrame(float[] rDevToWorld) {
        if (!started) {
            refHeading = Mat3.cameraHeading(rDevToWorld);
            started = true;
        }
        return Mat3.mul(Mat3.rotZ(refHeading), rDevToWorld);
    }

    /**
     * Añade un fotograma.
     *
     * @param s    píxeles del fotograma enderezado
     * @param cam  modelo de cámara de esa imagen enderezada
     * @param rDev orientación del móvil (dispositivo → mundo) en el instante del fotograma
     */
    public synchronized void addFrame(FrameSampler s, CameraModel cam, float[] rDev) {
        float[] r = toPanoFrame(rDev);
        double phi = Mat3.cameraHeading(r);
        headingDeg = Math.toDegrees(phi);
        pitchDeg = Math.toDegrees(Mat3.cameraPitch(r));
        rollDeg = Math.toDegrees(Mat3.cameraRoll(r));

        double hw;
        if (hasLast) {
            double d = Math.abs(Mat3.wrapPi(phi - lastHeading));
            hw = Math.min(Math.toRadians(8), d + Math.toRadians(0.3));
            hw = Math.max(hw, Math.toRadians(0.4));
        } else {
            hw = Math.toRadians(0.8);
        }
        lastHeading = phi;
        hasLast = true;
        frames++;

        double[] lat = latitudeRange(cam, r);
        int y0 = (int) Math.floor((0.5 - lat[1] / Math.PI) * H - 0.5) - 1;
        int y1 = (int) Math.ceil((0.5 - lat[0] / Math.PI) * H - 0.5) + 1;
        if (y0 < 0) y0 = 0;
        if (y1 > H - 1) y1 = H - 1;

        project(s, cam, r, phi + alpha, hw, y0, y1, left);
        project(s, cam, r, phi - alpha, hw, y0, y1, right);
        project(s, cam, r, phi, hw, y0, y1, mono);
    }

    private void project(FrameSampler s, CameraModel cam, float[] r, double center, double hw,
                         int y0, int y1, PanoBuffer buf) {
        final double twoPi = 2 * Math.PI;
        int xa = (int) Math.floor(((center - hw) / twoPi + 0.5) * W - 0.5) - 1;
        int xb = (int) Math.ceil(((center + hw) / twoPi + 0.5) * W - 0.5) + 1;
        final float r0 = r[0], r1 = r[1], r2 = r[2], r3 = r[3], r4 = r[4], r5 = r[5], r6 = r[6], r7 = r[7], r8 = r[8];
        final float fx = cam.fx, fy = cam.fy, ccx = cam.cx, ccy = cam.cy;
        final float umax = cam.width - 2, vmax = cam.height - 2;
        final double halfH = cam.height * 0.5;
        final double invHw = 1.0 / hw;
        for (int y = y0; y <= y1; y++) {
            final float sl = sinLat[y], cl = cosLat[y];
            final int row = y * W;
            for (int x = xa; x <= xb; x++) {
                int xi = x % W;
                if (xi < 0) xi += W;
                double dl = Mat3.wrapPi(PanoBuffer.lonOf(xi, W) - center);
                if (dl > hw || dl < -hw) continue;
                float dx = cl * sinLon[xi], dy = cl * cosLon[xi], dz = sl;
                // Coordenadas de cámara: Rᵀ · d
                float ux = r0 * dx + r3 * dy + r6 * dz;
                float uy = r1 * dx + r4 * dy + r7 * dz;
                float uz = r2 * dx + r5 * dy + r8 * dz;
                if (uz > -0.05f) continue;
                float inv = -1f / uz;
                float u = ccx + fx * ux * inv;
                float v = ccy - fy * uy * inv;
                if (!(u >= 1 && v >= 1 && u <= umax && v <= vmax)) continue;
                double vt = Math.abs(v - ccy) / halfH;
                double ht = Math.abs(dl) * invHw;
                int sc = (int) ((0.35 * ht + vt) * (254.0 / 1.35));
                if (sc > 254) sc = 254;
                int idx = row + xi;
                if (sc < (buf.score[idx] & 0xFF)) {
                    int col = s.sample(u, v);
                    if (col != 0) {
                        buf.color[idx] = col;
                        buf.score[idx] = (byte) sc;
                    }
                }
            }
        }
    }

    /** Latitudes mínima y máxima (radianes) que cubre el fotograma. */
    static double[] latitudeRange(CameraModel cam, float[] r) {
        double lo = Math.PI / 2, hi = -Math.PI / 2;
        double[] w = new double[3];
        int n = 16;
        for (int i = 0; i <= n; i++) {
            double t = i / (double) n;
            double[][] pts = {
                    {t * (cam.width - 1), 0}, {t * (cam.width - 1), cam.height - 1},
                    {0, t * (cam.height - 1)}, {cam.width - 1, t * (cam.height - 1)}};
            for (double[] p : pts) {
                double ux = (p[0] - cam.cx) / cam.fx;
                double uy = -(p[1] - cam.cy) / cam.fy;
                Mat3.apply(r, ux, uy, -1, w);
                double len = Math.sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2]);
                double la = Math.asin(w[2] / len);
                lo = Math.min(lo, la);
                hi = Math.max(hi, la);
            }
        }
        // ¿Se ve el cénit o el nadir dentro de la foto?
        for (int sgn = -1; sgn <= 1; sgn += 2) {
            double ux = r[6] * sgn, uy = r[7] * sgn, uz = r[8] * sgn; // Rᵀ·(0,0,sgn)
            if (uz < -0.05) {
                double u = cam.cx + cam.fx * ux / -uz;
                double v = cam.cy - cam.fy * uy / -uz;
                if (u >= 0 && v >= 0 && u <= cam.width - 1 && v <= cam.height - 1) {
                    if (sgn > 0) hi = Math.PI / 2;
                    else lo = -Math.PI / 2;
                }
            }
        }
        return new double[]{lo - 0.01, hi + 0.01};
    }
}
