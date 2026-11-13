package com.ricardopch.esfera3d.core;

/**
 * Lleva la cuenta, fotograma a fotograma, de cuánto se aparta la imagen de lo que dice el
 * giroscopio (temblor compensado por el estabilizador óptico, desfases…).
 *
 * a ← e + λ · G · a, donde e es el giro residual medido por {@link MotionEstimator} entre el
 * fotograma anterior y el actual, G el giro del giroscopio entre ambos y λ un olvido
 * exponencial (≈ 1 s): así se conserva el temblor rápido y se olvida la deriva lenta, que
 * incluye el paralaje (no es un error de orientación).
 */
public final class MotionTracker {
    private static final double TAU = 1.0;
    private final MotionEstimator est;
    private byte[] prev;
    private float[] prevRot;
    private long prevT;
    private final float[] a = new float[3];
    private final float[] e = new float[3];
    private int measured, total;

    // Calibración a partir del propio barrido (solo en la vuelta del horizonte)
    private double kGG, kGC;          // focal: Σ giro², Σ giro · trapecio
    private int kN;
    private static final int BINS = 72;
    private final double[] pGG = new double[BINS], pGM = new double[BINS]; // paralaje por rumbo

    public MotionTracker(int w, int h, float f) {
        est = new MotionEstimator(w, h, f);
    }

    /**
     * @param gray miniatura en gris del fotograma (se guarda la referencia: no reutilizar el array)
     * @param rot  orientación dispositivo → mundo del fotograma
     * @return diferencia acumulada actual (no modificar)
     */
    public float[] update(byte[] gray, float[] rot, long tNs) {
        return update(gray, rot, tNs, false);
    }

    /**
     * @param calibrate true mientras se hace la vuelta del horizonte con el móvil estable: se
     *                  acumulan los datos para calcular la focal real y el paralaje por rumbo
     */
    public float[] update(byte[] gray, float[] rot, long tNs, boolean calibrate) {
        total++;
        if (prev != null) {
            double dt = (tNs - prevT) * 1e-9;
            if (dt <= 0 || dt > 0.25) {
                a[0] = a[1] = a[2] = 0;
            } else {
                float[] g = Mat3.mul(Mat3.transpose(rot), prevRot);
                float lam = (float) Math.exp(-dt / TAU);
                float x = lam * (g[0] * a[0] + g[1] * a[1] + g[2] * a[2]);
                float y = lam * (g[3] * a[0] + g[4] * a[1] + g[5] * a[2]);
                float z = lam * (g[6] * a[0] + g[7] * a[1] + g[8] * a[2]);
                int q = est.estimate(prev, gray, g, e);
                if (q > 0) {
                    measured++;
                    x += e[0];
                    y += e[1];
                    z += e[2];
                    double gy = 0.5 * (g[2] - g[6]); // giro de rumbo según el giroscopio (rad)
                    if (calibrate && q >= 20 && Math.abs(gy) > Math.toRadians(0.1) && Math.abs(gy) < Math.toRadians(2.5)) {
                        if (est.keystoneOk()) {
                            kGG += gy * gy;
                            kGC += gy * est.keystone();
                            kN++;
                        }
                        int bin = (int) Math.floor((Math.toDegrees(Mat3.cameraHeading(rot)) + 180) / 360.0 * BINS);
                        bin = Math.max(0, Math.min(BINS - 1, bin));
                        pGG[bin] += gy * gy;
                        pGM[bin] += gy * (gy + e[1]);
                    }
                }
                a[0] = x;
                a[1] = y;
                a[2] = z;
            }
        }
        prev = gray;
        prevRot = rot.clone();
        prevT = tNs;
        return a;
    }

    /**
     * Focal real dividida por la supuesta (1 = sin corrección). Sale de comparar el giro del
     * giroscopio con la deformación en trapecio de la imagen, que no depende del paralaje.
     */
    public double focalFactor(float assumedF) {
        if (kN < 120 || kGC <= 0) return 1;
        double k = (kGG / kGC) / assumedF;
        return (k > 0.8 && k < 1.4) ? k : 1;
    }

    public int focalSamples() {
        return kN;
    }

    /**
     * Para cada rumbo del sensor (72 tramos de 5°, desde −180°): radio de giro del móvil dividido
     * por la distancia típica a la escena (R/d). Sale de cuánto más se mueve la imagen que lo que
     * gira el móvil. NaN donde no hay datos.
     */
    public float[] parallaxRatio(double focalFactor) {
        float[] out = new float[BINS];
        for (int i = 0; i < BINS; i++) {
            if (pGG[i] < 1e-5) {
                out[i] = Float.NaN;
                continue;
            }
            out[i] = (float) Math.max(0, Math.min(0.45, (pGM[i] / pGG[i]) / focalFactor - 1));
        }
        return out;
    }

    public int measured() {
        return measured;
    }

    public int total() {
        return total;
    }
}
