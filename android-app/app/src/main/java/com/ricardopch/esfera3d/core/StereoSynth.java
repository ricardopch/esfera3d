package com.ricardopch.esfera3d.core;

/**
 * Genera la vista de un ojo a partir de la panorámica central y el mapa de profundidad
 * (estéreo figurado). Es la misma cuenta que hace el visor en la GPU, aquí en Java para
 * exportar la imagen.
 *
 * Para la mirada (lon, lat), un punto a distancia d visto por el ojo izquierdo (desplazado
 * r hacia la izquierda) aparece, visto desde el centro, en lon − δ con
 * tan δ = r / (d · cos lat). El derecho, en lon + δ.
 */
public final class StereoSynth {
    private StereoSynth() {}

    public static double poleFade(double latRad) {
        double a = Math.abs(Math.toDegrees(latRad));
        return 1 - PanoPost.smooth((a - 60) / 20.0);
    }

    public static int[] synthesize(int[] mono, int w, int h, byte[] q, int qw, int qh,
                                   double eyeRadius, boolean leftEye) {
        int[] out = new int[w * h];
        double sgn = leftEye ? -1 : 1;
        double k = eyeRadius / DepthEstimator.DMIN;
        for (int y = 0; y < h; y++) {
            double lat = PanoBuffer.latOf(y, h);
            double cl = Math.max(0.02, Math.cos(lat));
            double fade = poleFade(lat);
            double fy = (y + 0.5) / h; // 0..1
            for (int x = 0; x < w; x++) {
                double fx = (x + 0.5) / w;
                double qq = sampleQ(q, qw, qh, fx, fy);
                double d = Math.min(0.2, Math.atan(k * fade * qq / cl));
                qq = sampleQ(q, qw, qh, fx + sgn * d / (2 * Math.PI), fy);
                d = Math.min(0.2, Math.atan(k * fade * qq / cl));
                out[y * w + x] = sampleColor(mono, w, h, fx + sgn * d / (2 * Math.PI), fy);
            }
        }
        return out;
    }

    static double sampleQ(byte[] q, int qw, int qh, double fx, double fy) {
        double px = fx * qw - 0.5, py = fy * qh - 0.5;
        int x0 = (int) Math.floor(px), y0 = (int) Math.floor(py);
        double ax = px - x0, ay = py - y0;
        int xa = ((x0 % qw) + qw) % qw, xb = (xa + 1) % qw;
        int ya = Math.max(0, Math.min(qh - 1, y0)), yb = Math.max(0, Math.min(qh - 1, y0 + 1));
        double v00 = q[ya * qw + xa] & 0xFF, v10 = q[ya * qw + xb] & 0xFF;
        double v01 = q[yb * qw + xa] & 0xFF, v11 = q[yb * qw + xb] & 0xFF;
        return ((v00 * (1 - ax) + v10 * ax) * (1 - ay) + (v01 * (1 - ax) + v11 * ax) * ay) / 255.0;
    }

    public static int sampleColor(int[] img, int w, int h, double fx, double fy) {
        double px = fx * w - 0.5, py = fy * h - 0.5;
        int x0 = (int) Math.floor(px), y0 = (int) Math.floor(py);
        float ax = (float) (px - x0), ay = (float) (py - y0);
        int xa = ((x0 % w) + w) % w, xb = (xa + 1) % w;
        int ya = Math.max(0, Math.min(h - 1, y0)), yb = Math.max(0, Math.min(h - 1, y0 + 1));
        int top = PanoPost.lerpColor(img[ya * w + xa], img[ya * w + xb], ax);
        int bot = PanoPost.lerpColor(img[yb * w + xa], img[yb * w + xb], ax);
        return PanoPost.lerpColor(top, bot, ay);
    }

    /** Une dos panorámicas en formato estéreo "arriba/abajo" (izquierda arriba). */
    public static int[] topBottom(int[] left, int[] right, int w, int h) {
        int[] out = new int[w * h * 2];
        System.arraycopy(left, 0, out, 0, w * h);
        System.arraycopy(right, 0, out, w * h, w * h);
        return out;
    }
}
