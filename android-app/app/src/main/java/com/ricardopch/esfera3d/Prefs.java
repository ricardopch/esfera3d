package com.ricardopch.esfera3d;

import android.content.Context;
import android.content.SharedPreferences;

/** Ajustes guardados de la app. */
public final class Prefs {
    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getSharedPreferences("ajustes", Context.MODE_PRIVATE);
    }

    /** Distancia del móvil al eje de giro (el cuerpo), en cm. */
    public int armCm() {
        return sp.getInt("brazo_cm", 45);
    }

    public void setArmCm(int v) {
        sp.edit().putInt("brazo_cm", v).apply();
    }

    /** Ancho de la panorámica: 4096 (alta) o 2048 (ligera). */
    public int panoWidth() {
        return sp.getInt("ancho", 4096);
    }

    public void setPanoWidth(int v) {
        sp.edit().putInt("ancho", v).apply();
    }

    public boolean fullSphere() {
        return sp.getBoolean("esfera_completa", true);
    }

    public void setFullSphere(boolean v) {
        sp.edit().putBoolean("esfera_completa", v).apply();
    }

    // --- Visor de gafas ---

    /** 0 Cardboard, 1 visor de plástico genérico, 2 suave, 3 sin corrección. */
    public int lensPreset() {
        return sp.getInt("lente", 0);
    }

    public void setLensPreset(int v) {
        sp.edit().putInt("lente", v).apply();
    }

    /** Separación entre lentes, mm. */
    public int lensSepMm() {
        return sp.getInt("separacion_mm", 64);
    }

    public void setLensSepMm(int v) {
        sp.edit().putInt("separacion_mm", v).apply();
    }

    /** Escala del campo de visión en las gafas, %. */
    public int vrScalePct() {
        return sp.getInt("escala_vr", 100);
    }

    public void setVrScalePct(int v) {
        sp.edit().putInt("escala_vr", v).apply();
    }

    /** Intensidad del estéreo figurado, %. */
    public int strengthPct() {
        return sp.getInt("intensidad", 100);
    }

    public void setStrengthPct(int v) {
        sp.edit().putInt("intensidad", v).apply();
    }

    public static final String[] LENS_NAMES = {
            "Cardboard (estándar)",
            "Visor de plástico tipo «VR Box»",
            "Corrección suave",
            "Sin corrección de lente"};

    /** {k1, k2, distancia pantalla-lente en metros} de cada tipo de visor. */
    public static float[] lensParams(int preset) {
        switch (preset) {
            case 1:
                return new float[]{0.22f, 0.24f, 0.042f};
            case 2:
                return new float[]{0.12f, 0.06f, 0.040f};
            case 3:
                return new float[]{0f, 0f, 0.040f};
            default:
                return new float[]{0.34f, 0.55f, 0.039f};
        }
    }
}
