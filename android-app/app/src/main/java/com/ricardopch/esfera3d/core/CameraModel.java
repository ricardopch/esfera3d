package com.ricardopch.esfera3d.core;

/** Modelo de cámara estenopeica para la imagen ya enderezada (móvil en vertical). */
public final class CameraModel {
    public final int width, height;
    public final float fx, fy, cx, cy;

    public CameraModel(int width, int height, float fx, float fy) {
        this.width = width;
        this.height = height;
        this.fx = fx;
        this.fy = fy;
        this.cx = (width - 1) * 0.5f;
        this.cy = (height - 1) * 0.5f;
    }

    /** Crea el modelo a partir del campo de visión horizontal de la imagen enderezada. */
    public static CameraModel fromHorizontalFov(int width, int height, double hfovDeg) {
        float f = (float) ((width * 0.5) / Math.tan(Math.toRadians(hfovDeg) * 0.5));
        return new CameraModel(width, height, f, f);
    }

    public double hfovDeg() {
        return Math.toDegrees(2 * Math.atan(width * 0.5 / fx));
    }

    public double vfovDeg() {
        return Math.toDegrees(2 * Math.atan(height * 0.5 / fy));
    }

    public CameraModel scaled(float factor) {
        return new CameraModel(width, height, fx * factor, fy * factor);
    }
}
