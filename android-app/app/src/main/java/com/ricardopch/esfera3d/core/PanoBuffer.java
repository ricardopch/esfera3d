package com.ricardopch.esfera3d.core;

import java.util.Arrays;

/**
 * Panorámica equirectangular en construcción: color ARGB por píxel y una "nota" de
 * calidad (0 = mejor; 255 = vacío). Ancho = 360°, alto = 180°, fila 0 = cénit.
 */
public final class PanoBuffer {
    public static final int EMPTY = 255;
    public final int w, h;
    public final int[] color;
    public final byte[] score;

    public PanoBuffer(int w, int h) {
        this.w = w;
        this.h = h;
        this.color = new int[w * h];
        this.score = new byte[w * h];
        Arrays.fill(score, (byte) EMPTY);
    }

    public boolean isEmpty(int i) {
        return (score[i] & 0xFF) == EMPTY;
    }

    public void clear() {
        Arrays.fill(score, (byte) EMPTY);
        Arrays.fill(color, 0);
    }

    /** Fracción de píxeles ya rellenos. */
    public double coverage() {
        int n = 0;
        for (byte s : score) if ((s & 0xFF) != EMPTY) n++;
        return n / (double) score.length;
    }

    /** Longitud (radianes) del centro de la columna x. */
    public static double lonOf(int x, int w) {
        return ((x + 0.5) / w - 0.5) * 2 * Math.PI;
    }

    /** Latitud (radianes) del centro de la fila y. */
    public static double latOf(int y, int h) {
        return (0.5 - (y + 0.5) / h) * Math.PI;
    }
}
