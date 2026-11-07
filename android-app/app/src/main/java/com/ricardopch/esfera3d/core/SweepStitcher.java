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
 * (su rumbo + α del ojo). Dentro de una vuelta gana el fotograma más centrado en la cuña;
 * entre vueltas (horizonte / arriba / abajo) gana la vuelta cuya inclinación nominal está
 * más cerca de la latitud del píxel, así la costura entre vueltas es una línea recta.
 *
 * Alineado por imagen: cuando la zona que va a escribir un fotograma ya tiene contenido de
 * una vuelta anterior (o del principio de la misma vuelta, al cerrarla), se compara con él
 * y se corrige la orientación del sensor (rumbo e inclinación). Eso absorbe errores del
 * campo de visión, del giroscopio y de sincronización, y evita "peines" en las costuras.
 * Solo se compara con contenido de hace más de 300° de barrido, para no encadenar errores.
 */
public final class SweepStitcher {
    public final int W, H;
    public final PanoBuffer left, right, mono;
    private final double alpha;
    private final float[] sinLat, cosLat, sinLon, cosLon;
    /** Barrido acumulado (grados) cuando se escribió cada píxel de la central; -1 = nada. */
    private final short[] stamp;

    private volatile boolean started;
    private volatile double refHeading;
    private double lastHeading;
    private boolean hasLast;
    private volatile int frames;
    private double sweepDeg;

    // Límites de latitud (grados) para no dejar bordes dentados en lo no cubierto
    private double latMinDeg = -90, latMaxDeg = 90;
    private double stitchPitchTol = 10;
    private double ringPitchSum, ringTravelDeg;
    private int ringPitchN;

    // Alineado por imagen
    private boolean alignEnabled = true;
    private double corrYaw, corrPitch; // radianes
    private int alignedFrames, ringAligned;
    private double lastNominal = Double.NaN;
    private final FrameAligner aligner = new FrameAligner();

    // Último estado (en el sistema ya referido al primer fotograma, sin corrección)
    private volatile double headingDeg, pitchDeg, rollDeg;

    public SweepStitcher(int w, int h, double alphaRad) {
        this.W = w;
        this.H = h;
        this.alpha = alphaRad;
        left = new PanoBuffer(w, h);
        right = new PanoBuffer(w, h);
        mono = new PanoBuffer(w, h);
        stamp = new short[w * h];
        java.util.Arrays.fill(stamp, (short) -1);
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

    /** Solo se escriben latitudes dentro de [min, max] (bordes rectos donde acaba lo capturado). */
    public void setLatitudeLimits(double minDeg, double maxDeg) {
        latMinDeg = minDeg;
        latMaxDeg = maxDeg;
    }

    public void setAlignment(boolean on) {
        alignEnabled = on;
    }

    public double alpha() {
        return alpha;
    }

    public int framesUsed() {
        return frames;
    }

    public int alignedFrames() {
        return alignedFrames;
    }

    public double correctionYawDeg() {
        return Math.toDegrees(corrYaw);
    }

    public double correctionPitchDeg() {
        return Math.toDegrees(corrPitch);
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

    /** Versión simple: una sola orientación para todo el fotograma y sin vuelta nominal. */
    public void addFrame(FrameSampler s, CameraModel cam, float[] rDev) {
        addFrame(s, cam, rDev, rDev, rDev, Double.NaN);
    }

    /**
     * Añade un fotograma.
     *
     * @param s            píxeles del fotograma enderezado
     * @param cam          modelo de cámara de esa imagen enderezada
     * @param rCenter      orientación (dispositivo → mundo) en el instante de la franja central
     * @param rLeftStrip   orientación en el instante de la franja del ojo izquierdo (lado derecho de la foto)
     * @param rRightStrip  orientación en el instante de la franja del ojo derecho (lado izquierdo de la foto)
     * @param nominalPitch inclinación nominal (grados) de la vuelta en curso, o NaN si no se usa
     */
    public synchronized void addFrame(FrameSampler s, CameraModel cam, float[] rCenter, float[] rLeftStrip,
                                      float[] rRightStrip, double nominalPitch) {
        float[] r = toPanoFrame(rCenter);
        float[] rl = toPanoFrame(rLeftStrip);
        float[] rr = toPanoFrame(rRightStrip);
        double phiRaw = Mat3.cameraHeading(r);
        headingDeg = Math.toDegrees(phiRaw);
        pitchDeg = Math.toDegrees(Mat3.cameraPitch(r));
        rollDeg = Math.toDegrees(Mat3.cameraRoll(r));

        double dphi = hasLast ? Math.abs(Mat3.wrapPi(phiRaw - lastHeading)) : 0;
        if (dphi > Math.toRadians(20)) dphi = 0; // salto raro del sensor
        lastHeading = phiRaw;
        hasLast = true;
        sweepDeg += Math.toDegrees(dphi);

        boolean useNominal = !Double.isNaN(nominalPitch);
        // Cada vuelta tiene su propio error (p. ej. un campo de visión mal estimado inclina
        // hacia un lado la vuelta de arriba y hacia el otro la de abajo): se empieza de cero.
        if (useNominal && nominalPitch != lastNominal) {
            corrYaw = 0;
            corrPitch = 0;
            ringAligned = 0;
            lastNominal = nominalPitch;
            ringPitchSum = 0;
            ringPitchN = 0;
            ringTravelDeg = 0;
        }
        // Fotogramas de transición entre vueltas (el usuario inclinando el móvil): no se usan.
        if (useNominal && Math.abs(pitchDeg - nominalPitch) > stitchPitchTol) return;
        // Cierre de la vuelta: lo que ya escribió el principio de esta vuelta no se pisa
        // (así no se mezclan columnas de principio y final, que pueden diferir un poco).
        boolean fillOnly = ringTravelDeg > 362;
        ringTravelDeg += Math.toDegrees(dphi);
        // Costura entre vueltas: a mitad de camino entre las inclinaciones *reales* medias de
        // cada vuelta, que es donde las dos tienen más margen de imagen.
        double seamPitch = Double.NaN;
        if (useNominal) {
            ringPitchSum += pitchDeg;
            ringPitchN++;
            seamPitch = (nominalPitch * 10 + ringPitchSum) / (10 + ringPitchN);
        }

        // Alineado por imagen con lo capturado hace más de 300° de barrido
        if (alignEnabled) {
            double[] c = aligner.align(s, cam, r, phiRaw, this, corrYaw, corrPitch);
            if (c != null) {
                // La corrección cambia despacio (error del campo de visión, deriva del giroscopio):
                // media de las primeras estimaciones y luego un filtro lento que descarta las raras.
                boolean outlier = ringAligned >= 5
                        && (Math.abs(c[0] - corrYaw) > Math.toRadians(0.8) || Math.abs(c[1] - corrPitch) > Math.toRadians(0.8));
                if (!outlier) {
                    double gain = Math.max(0.08, 1.0 / (ringAligned + 1));
                    corrYaw += gain * (c[0] - corrYaw);
                    corrPitch += gain * (c[1] - corrPitch);
                    ringAligned++;
                    alignedFrames++;
                }
            }
            if (corrYaw != 0 || corrPitch != 0) {
                float[] q = correction(phiRaw, corrYaw, corrPitch);
                r = Mat3.mul(q, r);
                rl = Mat3.mul(q, rl);
                rr = Mat3.mul(q, rr);
            }
        }
        frames++;

        double phi = Mat3.cameraHeading(r);
        double hw = frames > 1 ? Math.min(Math.toRadians(8), dphi + Math.toRadians(0.3)) : Math.toRadians(0.8);
        hw = Math.max(hw, Math.toRadians(0.4));

        double[] lat = latitudeRange(cam, r);
        double lo = Math.max(lat[0], Math.toRadians(latMinDeg)), hi = Math.min(lat[1], Math.toRadians(latMaxDeg));
        if (hi <= lo) return;
        int y0 = (int) Math.floor((0.5 - hi / Math.PI) * H - 0.5) - 1;
        int y1 = (int) Math.ceil((0.5 - lo / Math.PI) * H - 0.5) + 1;
        if (y0 < 0) y0 = 0;
        if (y1 > H - 1) y1 = H - 1;
        int yLimTop = (int) Math.ceil((0.5 - Math.toRadians(latMaxDeg) / Math.PI) * H - 0.5);
        int yLimBot = (int) Math.floor((0.5 - Math.toRadians(latMinDeg) / Math.PI) * H - 0.5);
        y0 = Math.max(y0, yLimTop);
        y1 = Math.min(y1, yLimBot);

        double nominal = useNominal ? Math.toRadians(seamPitch) : Double.NaN;
        short st = (short) Math.min(32000, Math.round(sweepDeg));
        project(s, cam, rl, Mat3.cameraHeading(rl) + alpha, hw, y0, y1, left, nominal, null, st, fillOnly);
        project(s, cam, rr, Mat3.cameraHeading(rr) - alpha, hw, y0, y1, right, nominal, null, st, fillOnly);
        project(s, cam, r, phi, hw, y0, y1, mono, nominal, stamp, st, fillOnly);
    }

    /** Pequeña rotación de corrección: rumbo (alrededor de la vertical) e inclinación (eje horizontal lateral). */
    static float[] correction(double heading, double yaw, double pitch) {
        // Eje horizontal perpendicular al rumbo (a la derecha de la cámara)
        double hx = Math.cos(heading), hy = -Math.sin(heading);
        double c = Math.cos(pitch), s = Math.sin(pitch), t = 1 - c;
        float[] rh = new float[]{
                (float) (t * hx * hx + c), (float) (t * hx * hy), (float) (s * hy),
                (float) (t * hx * hy), (float) (t * hy * hy + c), (float) (-s * hx),
                (float) (-s * hy), (float) (s * hx), (float) c};
        // rotZ(-yaw): suma yaw al rumbo
        return Mat3.mul(Mat3.rotZ(-yaw), rh);
    }

    private void project(FrameSampler s, CameraModel cam, float[] r, double center, double hw,
                         int y0, int y1, PanoBuffer buf, double nominal, short[] stampOut, short st,
                         boolean fillOnly) {
        final double twoPi = 2 * Math.PI;
        int xa = (int) Math.floor(((center - hw) / twoPi + 0.5) * W - 0.5) - 1;
        int xb = (int) Math.ceil(((center + hw) / twoPi + 0.5) * W - 0.5) + 1;
        final float r0 = r[0], r1 = r[1], r2 = r[2], r3 = r[3], r4 = r[4], r5 = r[5], r6 = r[6], r7 = r[7], r8 = r[8];
        final float fx = cam.fx, fy = cam.fy, ccx = cam.cx, ccy = cam.cy;
        final float umax = cam.width - 2, vmax = cam.height - 2;
        final double halfH = cam.height * 0.5;
        final double halfVfov = Math.atan(halfH / cam.fy);
        final double invHw = 1.0 / hw;
        final boolean nom = !Double.isNaN(nominal);
        for (int y = y0; y <= y1; y++) {
            final float sl = sinLat[y], cl = cosLat[y];
            final int row = y * W;
            final double rowLat = Math.asin(sl);
            final double vtNominal = nom ? Math.abs(rowLat - nominal) / halfVfov : 0;
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
                double vt = nom ? vtNominal : Math.abs(v - ccy) / halfH;
                double ht = Math.abs(dl) * invHw;
                int sc = (int) ((0.35 * ht + vt) * (254.0 / 1.35));
                if (sc > 253) sc = 253;
                int idx = row + xi;
                int old = buf.score[idx] & 0xFF;
                if (fillOnly && old != PanoBuffer.EMPTY) continue;
                if (sc < old) {
                    int col = s.sample(u, v);
                    if (col != 0) {
                        buf.color[idx] = col;
                        buf.score[idx] = (byte) sc;
                        if (stampOut != null) stampOut[idx] = st;
                    }
                }
            }
        }
    }

    /** ¿El píxel de la central tiene contenido de hace al menos minAgoDeg grados de barrido? */
    boolean isReference(int idx, double minAgoDeg) {
        short st = stamp[idx];
        return st >= 0 && sweepDeg - st >= minAgoDeg && !mono.isEmpty(idx);
    }

    double sweepDeg() {
        return sweepDeg;
    }

    float sinLat(int y) {
        return sinLat[y];
    }

    float cosLat(int y) {
        return cosLat[y];
    }

    float sinLon(int x) {
        return sinLon[x];
    }

    float cosLon(int x) {
        return cosLon[x];
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
