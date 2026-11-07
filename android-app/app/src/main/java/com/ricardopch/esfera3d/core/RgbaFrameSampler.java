package com.ricardopch.esfera3d.core;

import java.nio.ByteBuffer;

/**
 * Lee un fotograma RGBA_8888 tal como lo entrega el sensor (normalmente apaisado) y lo
 * presenta enderezado: rotationDegrees es el giro horario que hay que aplicar al búfer
 * para verlo derecho (el valor de ImageInfo.getRotationDegrees() de CameraX).
 */
public final class RgbaFrameSampler implements FrameSampler {
    private ByteBuffer buf;
    private int bufW, bufH, rowStride, rotation, upW, upH;

    public void set(ByteBuffer buffer, int bufferWidth, int bufferHeight, int rowStride, int rotationDegrees) {
        this.buf = buffer;
        this.bufW = bufferWidth;
        this.bufH = bufferHeight;
        this.rowStride = rowStride;
        this.rotation = ((rotationDegrees % 360) + 360) % 360;
        if (rotation == 90 || rotation == 270) {
            upW = bufferHeight;
            upH = bufferWidth;
        } else {
            upW = bufferWidth;
            upH = bufferHeight;
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
        if (!(u >= 0 && v >= 0 && u <= upW - 1 && v <= upH - 1)) return 0;
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
        int x0 = (int) bx, y0 = (int) by;
        if (x0 >= bufW - 1) x0 = bufW - 2;
        if (y0 >= bufH - 1) y0 = bufH - 2;
        if (x0 < 0) x0 = 0;
        if (y0 < 0) y0 = 0;
        float ax = bx - x0, ay = by - y0;
        int i00 = y0 * rowStride + x0 * 4;
        int i10 = i00 + 4;
        int i01 = i00 + rowStride;
        int i11 = i01 + 4;
        ByteBuffer b = buf;
        float w00 = (1 - ax) * (1 - ay), w10 = ax * (1 - ay), w01 = (1 - ax) * ay, w11 = ax * ay;
        int r = (int) ((b.get(i00) & 0xFF) * w00 + (b.get(i10) & 0xFF) * w10
                + (b.get(i01) & 0xFF) * w01 + (b.get(i11) & 0xFF) * w11 + 0.5f);
        int g = (int) ((b.get(i00 + 1) & 0xFF) * w00 + (b.get(i10 + 1) & 0xFF) * w10
                + (b.get(i01 + 1) & 0xFF) * w01 + (b.get(i11 + 1) & 0xFF) * w11 + 0.5f);
        int bl = (int) ((b.get(i00 + 2) & 0xFF) * w00 + (b.get(i10 + 2) & 0xFF) * w10
                + (b.get(i01 + 2) & 0xFF) * w01 + (b.get(i11 + 2) & 0xFF) * w11 + 0.5f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
