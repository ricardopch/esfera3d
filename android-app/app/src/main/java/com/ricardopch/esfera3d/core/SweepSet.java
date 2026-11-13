package com.ricardopch.esfera3d.core;

import java.io.IOException;

/** Fotogramas guardados durante el barrido, con su orientación, listos para coserse después. */
public final class SweepSet {
    /** Entrega los píxeles del fotograma i (en coordenadas del fotograma completo enderezado). */
    public interface Store {
        FrameSampler open(int index) throws IOException;
    }

    public CameraModel cam;
    public int n;
    public long[] timeNs = new long[256];
    /** Orientación del móvil (dispositivo → mundo) en el instante del fotograma, según el sensor. */
    public float[][] rot = new float[256][];
    /** Inclinación nominal (grados) de la vuelta a la que pertenece el fotograma. */
    public float[] nominal = new float[256];
    /**
     * Diferencia acumulada (rad, ejes de cámara) entre el giro visto en la imagen y el del
     * giroscopio, con olvido exponencial (ver {@link MotionTracker}).
     */
    public float[][] acc = new float[256][];

    public synchronized int add(long t, float[] r, float nominalPitch, float[] accum) {
        if (n == timeNs.length) {
            int m = n * 2;
            timeNs = java.util.Arrays.copyOf(timeNs, m);
            rot = java.util.Arrays.copyOf(rot, m);
            nominal = java.util.Arrays.copyOf(nominal, m);
            acc = java.util.Arrays.copyOf(acc, m);
        }
        timeNs[n] = t;
        rot[n] = r.clone();
        nominal[n] = nominalPitch;
        acc[n] = accum == null ? new float[3] : accum.clone();
        return n++;
    }
}
