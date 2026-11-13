package com.ricardopch.esfera3d.core;

/**
 * Recorte de un fotograma guardado tal como sale del sensor (sin enderezar). Se le piden
 * coordenadas del fotograma completo enderezado y él deshace el giro y el recorte.
 */
public final class BufferCropSampler implements FrameSampler {
    private final int[] px;
    private final int cw, ch, cropX, cropY, bufW, bufH, rotation, upW, upH;

    /**
     * @param px       píxeles ARGB del recorte (cw × ch), en la orientación del búfer del sensor
     * @param cropX    posición del recorte dentro del búfer (bufW × bufH)
     * @param rotation giro horario (grados) que endereza el búfer
     */
    public BufferCropSampler(int[] px, int cw, int ch, int cropX, int cropY, int bufW, int bufH, int rotation) {
        this.px = px;
        this.cw = cw;
        this.ch = ch;
        this.cropX = cropX;
        this.cropY = cropY;
        this.bufW = bufW;
        this.bufH = bufH;
        this.rotation = ((rotation % 360) + 360) % 360;
        boolean swap = this.rotation == 90 || this.rotation == 270;
        upW = swap ? bufH : bufW;
        upH = swap ? bufW : bufH;
    }

    /** Recorte (en coordenadas del búfer) que contiene las columnas enderezadas [u0, u1). */
    public static int[] cropForColumns(int bufW, int bufH, int rotation, int u0, int u1) {
        rotation = ((rotation % 360) + 360) % 360;
        switch (rotation) {
            case 90:
                return new int[]{0, bufH - u1, bufW, u1 - u0};
            case 270:
                return new int[]{0, u0, bufW, u1 - u0};
            case 180:
                return new int[]{bufW - u1, 0, u1 - u0, bufH};
            default:
                return new int[]{u0, 0, u1 - u0, bufH};
        }
    }

    @Override
    public int width() {
        return upW;
    }

    @Override
    public int height() {
        return upH;
    }

    @Override
    public int sample(float u, float v) {
        float bx, by;
        switch (rotation) {
            case 90:
                bx = v;
                by = bufH - 1 - u;
                break;
            case 180:
                bx = bufW - 1 - u;
                by = bufH - 1 - v;
                break;
            case 270:
                bx = bufW - 1 - v;
                by = u;
                break;
            default:
                bx = u;
                by = v;
        }
        float x = bx - cropX, y = by - cropY;
        if (!(x >= 0 && y >= 0 && x <= cw - 1 && y <= ch - 1)) return 0;
        int x0 = (int) x, y0 = (int) y;
        if (x0 >= cw - 1) x0 = cw - 2;
        if (y0 >= ch - 1) y0 = ch - 2;
        float ax = x - x0, ay = y - y0;
        int i = y0 * cw + x0;
        int c00 = px[i], c10 = px[i + 1], c01 = px[i + cw], c11 = px[i + cw + 1];
        float w00 = (1 - ax) * (1 - ay), w10 = ax * (1 - ay), w01 = (1 - ax) * ay, w11 = ax * ay;
        int r = (int) (((c00 >> 16) & 0xFF) * w00 + ((c10 >> 16) & 0xFF) * w10 + ((c01 >> 16) & 0xFF) * w01 + ((c11 >> 16) & 0xFF) * w11 + 0.5f);
        int g = (int) (((c00 >> 8) & 0xFF) * w00 + ((c10 >> 8) & 0xFF) * w10 + ((c01 >> 8) & 0xFF) * w01 + ((c11 >> 8) & 0xFF) * w11 + 0.5f);
        int b = (int) ((c00 & 0xFF) * w00 + (c10 & 0xFF) * w10 + (c01 & 0xFF) * w01 + (c11 & 0xFF) * w11 + 0.5f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
