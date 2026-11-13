package com.ricardopch.esfera3d.core;

/**
 * Fotograma (o recorte de fotograma) ya decodificado en un array ARGB. Las coordenadas que
 * se piden son las del fotograma completo enderezado; el recorte empieza en (offU, offV).
 */
public final class ArrayFrameSampler implements FrameSampler {
    private final int[] px;
    private final int w, h, offU, offV, fullW, fullH;

    public ArrayFrameSampler(int[] px, int w, int h, int offU, int offV, int fullW, int fullH) {
        this.px = px;
        this.w = w;
        this.h = h;
        this.offU = offU;
        this.offV = offV;
        this.fullW = fullW;
        this.fullH = fullH;
    }

    @Override
    public int width() {
        return fullW;
    }

    @Override
    public int height() {
        return fullH;
    }

    @Override
    public int sample(float u, float v) {
        float x = u - offU, y = v - offV;
        if (!(x >= 0 && y >= 0 && x <= w - 1 && y <= h - 1)) return 0;
        int x0 = (int) x, y0 = (int) y;
        if (x0 >= w - 1) x0 = w - 2;
        if (y0 >= h - 1) y0 = h - 2;
        float ax = x - x0, ay = y - y0;
        int i = y0 * w + x0;
        int c00 = px[i], c10 = px[i + 1], c01 = px[i + w], c11 = px[i + w + 1];
        float w00 = (1 - ax) * (1 - ay), w10 = ax * (1 - ay), w01 = (1 - ax) * ay, w11 = ax * ay;
        int r = (int) (((c00 >> 16) & 0xFF) * w00 + ((c10 >> 16) & 0xFF) * w10 + ((c01 >> 16) & 0xFF) * w01 + ((c11 >> 16) & 0xFF) * w11 + 0.5f);
        int g = (int) (((c00 >> 8) & 0xFF) * w00 + ((c10 >> 8) & 0xFF) * w10 + ((c01 >> 8) & 0xFF) * w01 + ((c11 >> 8) & 0xFF) * w11 + 0.5f);
        int b = (int) ((c00 & 0xFF) * w00 + (c10 & 0xFF) * w10 + (c01 & 0xFF) * w01 + (c11 & 0xFF) * w11 + 0.5f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
