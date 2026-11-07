package com.ricardopch.esfera3d.core;

/**
 * Matrices de rotación 3x3 guardadas por filas en un float[9].
 *
 * Convenciones usadas en toda la app:
 *  - Coordenadas del dispositivo (las de Android): X a la derecha, Y hacia arriba
 *    (borde superior del móvil en vertical), Z saliendo de la pantalla hacia el usuario.
 *  - Coordenadas del mundo: X e Y horizontales, Z hacia el cielo.
 *  - Una matriz R "dispositivo → mundo" cumple mundo = R · dispositivo
 *    (es lo que devuelve SensorManager.getRotationMatrixFromVector).
 *  - Rumbo (longitud) de una dirección (x, y, z) = atan2(x, y), creciente en sentido horario.
 */
public final class Mat3 {
    private Mat3() {}

    public static float[] identity() {
        return new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
    }

    public static float[] mul(float[] a, float[] b) {
        float[] r = new float[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
            }
        }
        return r;
    }

    public static float[] transpose(float[] a) {
        return new float[]{a[0], a[3], a[6], a[1], a[4], a[7], a[2], a[5], a[8]};
    }

    /** Rotación antihoraria (vista desde +Z) de t radianes: resta t al rumbo de cualquier dirección. */
    public static float[] rotZ(double t) {
        float c = (float) Math.cos(t), s = (float) Math.sin(t);
        return new float[]{c, -s, 0, s, c, 0, 0, 0, 1};
    }

    public static float[] rotX(double t) {
        float c = (float) Math.cos(t), s = (float) Math.sin(t);
        return new float[]{1, 0, 0, 0, c, -s, 0, s, c};
    }

    /** Aplica m a (x, y, z) y deja el resultado en out[0..2]. */
    public static void apply(float[] m, double x, double y, double z, double[] out) {
        out[0] = m[0] * x + m[1] * y + m[2] * z;
        out[1] = m[3] * x + m[4] * y + m[5] * z;
        out[2] = m[6] * x + m[7] * y + m[8] * z;
    }

    /**
     * Misma fórmula que SensorManager.getRotationMatrixFromVector, a partir de un
     * cuaternión unitario (w, x, y, z). Resultado: matriz dispositivo → mundo.
     */
    public static void fromQuaternion(float w, float x, float y, float z, float[] r) {
        float sqQ1 = 2 * x * x, sqQ2 = 2 * y * y, sqQ3 = 2 * z * z;
        float q1q2 = 2 * x * y, q3q0 = 2 * z * w;
        float q1q3 = 2 * x * z, q2q0 = 2 * y * w;
        float q2q3 = 2 * y * z, q1q0 = 2 * x * w;
        r[0] = 1 - sqQ2 - sqQ3;
        r[1] = q1q2 - q3q0;
        r[2] = q1q3 + q2q0;
        r[3] = q1q2 + q3q0;
        r[4] = 1 - sqQ1 - sqQ3;
        r[5] = q2q3 - q1q0;
        r[6] = q1q3 - q2q0;
        r[7] = q2q3 + q1q0;
        r[8] = 1 - sqQ1 - sqQ2;
    }

    /** Rumbo (radianes, -π..π) hacia el que mira la cámara trasera (eje -Z del dispositivo). */
    public static double cameraHeading(float[] r) {
        double fx = -r[2], fy = -r[5], fz = -r[8];
        double horiz = Math.hypot(fx, fy);
        if (horiz > 0.2) return Math.atan2(fx, fy);
        // Apuntando casi al cénit o al nadir: se usa el borde superior del móvil.
        double tx = r[1], ty = r[4];
        if (fz > 0) return Math.atan2(-tx, -ty);
        return Math.atan2(tx, ty);
    }

    /** Inclinación (radianes): positivo si la cámara mira hacia arriba. */
    public static double cameraPitch(float[] r) {
        double fz = -r[8];
        return Math.asin(Math.max(-1, Math.min(1, fz)));
    }

    /** Giro lateral (radianes) del móvil respecto a la vertical: 0 si está derecho. */
    public static double cameraRoll(float[] r) {
        // Componente vertical del eje X del dispositivo (borde derecho).
        double xz = r[6];
        return Math.asin(Math.max(-1, Math.min(1, xz)));
    }

    public static double wrapPi(double a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }
}
