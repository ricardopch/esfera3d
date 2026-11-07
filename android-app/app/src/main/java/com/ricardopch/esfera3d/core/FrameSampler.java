package com.ricardopch.esfera3d.core;

/** Acceso a los píxeles de un fotograma ya enderezado (u hacia la derecha, v hacia abajo). */
public interface FrameSampler {
    int width();

    int height();

    /** Color ARGB (alfa 255) interpolado en (u, v), o 0 si el punto cae fuera de la imagen. */
    int sample(float u, float v);
}
