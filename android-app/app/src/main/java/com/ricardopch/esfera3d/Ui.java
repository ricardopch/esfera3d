package com.ricardopch.esfera3d;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

/** Utilidades de interfaz: márgenes de las barras del sistema y pantalla completa. */
public final class Ui {
    private Ui() {}

    public interface InsetsListener {
        void onInsets(int left, int top, int right, int bottom);
    }

    /** Aplica como relleno de la vista el espacio ocupado por barras del sistema y recortes de pantalla. */
    public static void padForSystemBars(View v, InsetsListener also) {
        v.setOnApplyWindowInsetsListener((view, ins) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets s = ins.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                l = s.left;
                t = s.top;
                r = s.right;
                b = s.bottom;
            } else {
                l = ins.getSystemWindowInsetLeft();
                t = ins.getSystemWindowInsetTop();
                r = ins.getSystemWindowInsetRight();
                b = ins.getSystemWindowInsetBottom();
            }
            view.setPadding(l, t, r, b);
            if (also != null) also.onInsets(l, t, r, b);
            return ins;
        });
        v.requestApplyInsets();
    }

    @SuppressWarnings("deprecation")
    public static void setImmersive(Activity a, boolean on) {
        View decor = a.getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = a.getWindow().getInsetsController();
            if (c == null) return;
            if (on) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                c.show(WindowInsets.Type.systemBars());
            }
        } else {
            decor.setSystemUiVisibility(on
                    ? (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
                    : 0);
        }
    }

    public static int dp(View v, float dp) {
        return Math.round(dp * v.getResources().getDisplayMetrics().density);
    }
}
