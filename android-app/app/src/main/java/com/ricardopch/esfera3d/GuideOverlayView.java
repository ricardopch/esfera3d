package com.ricardopch.esfera3d;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.ricardopch.esfera3d.core.CameraModel;
import com.ricardopch.esfera3d.core.Mat3;
import com.ricardopch.esfera3d.core.SweepGuide;

/**
 * Guía dibujada sobre la cámara: la línea que hay que seguir (a la altura de la vuelta
 * actual, ya proyectada con la orientación real del móvil), lo que ya está cubierto, el
 * punto de mira, la velocidad, los avisos y una miniatura de la panorámica en construcción.
 */
public class GuideOverlayView extends View {

    public interface Source {
        CameraModel camera();

        boolean latestRotation(float[] out);

        /** null antes de empezar. */
        SweepGuide guide();

        boolean started();

        double referenceHeading();

        double introRingPitch();

        Bitmap previewBitmap();

        boolean capturing();
    }

    private Source src;
    private final float[] raw = new float[9];
    private final Paint pDone = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTodo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pAim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSmall = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBox = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBmp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private int insetTop, insetBottom;
    private int bottomReserve;
    private final float density;

    public GuideOverlayView(Context c, AttributeSet a) {
        super(c, a);
        density = getResources().getDisplayMetrics().density;
        pDone.setStyle(Paint.Style.STROKE);
        pDone.setStrokeCap(Paint.Cap.ROUND);
        pDone.setStrokeWidth(7 * density);
        pDone.setColor(Color.argb(230, 80, 220, 120));
        pTodo.setStyle(Paint.Style.STROKE);
        pTodo.setStrokeCap(Paint.Cap.ROUND);
        pTodo.setStrokeWidth(2.5f * density);
        pTodo.setColor(Color.argb(220, 255, 255, 255));
        pAim.setStyle(Paint.Style.STROKE);
        pAim.setStrokeWidth(3 * density);
        pText.setColor(Color.WHITE);
        pText.setTextSize(22 * density);
        pText.setFakeBoldText(true);
        pText.setTextAlign(Paint.Align.CENTER);
        pText.setShadowLayer(4 * density, 0, 0, Color.BLACK);
        pSmall.setColor(Color.argb(235, 255, 255, 255));
        pSmall.setTextSize(14 * density);
        pSmall.setTextAlign(Paint.Align.CENTER);
        pSmall.setShadowLayer(3 * density, 0, 0, Color.BLACK);
        pBox.setColor(Color.argb(150, 0, 0, 0));
        pFill.setStyle(Paint.Style.FILL);
    }

    public void setSource(Source s) {
        this.src = s;
    }

    public void setInsets(int top, int bottom) {
        insetTop = top;
        insetBottom = bottom;
    }

    /** Espacio inferior ocupado por los botones (para no tapar la miniatura). */
    public void setBottomReserve(int px) {
        bottomReserve = px;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (src == null) return;
        float vw = getWidth(), vh = getHeight();
        CameraModel cam = src.camera();
        if (cam == null || !src.latestRotation(raw)) {
            canvas.drawText("Iniciando cámara y sensores…", vw / 2, vh / 2, pText);
            postInvalidateOnAnimation();
            return;
        }
        double ref = src.started() ? src.referenceHeading() : Mat3.cameraHeading(raw);
        float[] r = Mat3.mul(Mat3.rotZ(ref), raw);
        // Encaje de la imagen de cámara en la vista (FIT_CENTER)
        float s = Math.min(vw / cam.width, vh / cam.height);
        float ox = (vw - cam.width * s) / 2f, oy = (vh - cam.height * s) / 2f;

        SweepGuide g = src.guide();
        SweepGuide.Ring ring = g != null ? g.currentRing() : null;
        double targetPitch = ring != null ? ring.pitchDeg : src.introRingPitch();
        double pitch = Math.toDegrees(Mat3.cameraPitch(r));
        double roll = Math.toDegrees(Mat3.cameraRoll(r));
        double heading = Math.toDegrees(Mat3.cameraHeading(r));
        boolean pitchOk = Math.abs(pitch - targetPitch) <= SweepGuide.PITCH_TOL;
        boolean rollOk = Math.abs(roll) <= SweepGuide.ROLL_TOL;

        // Círculo guía: tramos ya cubiertos (verde grueso) y pendientes (blanco)
        boolean doneLook = g != null && g.isDone();
        if (!doneLook) {
            double e = Math.toRadians(targetPitch);
            float[] a = new float[2], b = new float[2];
            for (int bin = 0; bin < 360; bin++) {
                boolean covered = ring != null && ring.bins[bin];
                if (!project(r, cam, s, ox, oy, e, Math.toRadians(bin), a)) continue;
                if (!project(r, cam, s, ox, oy, e, Math.toRadians(bin + 1), b)) continue;
                if (Math.hypot(a[0] - b[0], a[1] - b[1]) > vw) continue;
                if (covered) {
                    canvas.drawLine(a[0], a[1], b[0], b[1], pDone);
                } else if ((bin & 1) == 0) {
                    canvas.drawLine(a[0], a[1], b[0], b[1], pTodo);
                }
            }
            // Marca de salida (rumbo 0)
            if (src.started() && project(r, cam, s, ox, oy, e, 0, a)) {
                pFill.setColor(Color.argb(230, 255, 181, 71));
                canvas.drawCircle(a[0], a[1], 7 * density, pFill);
            }
        }

        // Punto de mira
        float cx = ox + cam.cx * s, cy = oy + cam.cy * s;
        int aimColor = (pitchOk && rollOk) ? Color.rgb(80, 220, 120) : Color.rgb(255, 170, 60);
        pAim.setColor(aimColor);
        canvas.drawCircle(cx, cy, 26 * density, pAim);
        canvas.drawLine(cx - 40 * density, cy, cx - 30 * density, cy, pAim);
        canvas.drawLine(cx + 30 * density, cy, cx + 40 * density, cy, pAim);
        if (src.capturing() && !doneLook) {
            // Flechas: gira hacia la derecha
            pFill.setColor(Color.argb(220, 255, 255, 255));
            for (int k = 0; k < 3; k++) {
                float x0 = cx + (52 + k * 18) * density;
                path.reset();
                path.moveTo(x0, cy - 10 * density);
                path.lineTo(x0 + 10 * density, cy);
                path.lineTo(x0, cy + 10 * density);
                path.close();
                canvas.drawPath(path, pFill);
            }
        }

        // Textos de arriba
        float y = insetTop + 34 * density;
        String title;
        String hint;
        if (!src.started()) {
            title = "Prepárate";
            hint = !rollOk ? "Pon el móvil derecho (vertical)" : (pitchOk ? "Apunta al horizonte y pulsa Empezar" : (pitch < targetPitch ? "Levanta un poco la cámara" : "Baja un poco la cámara"));
        } else if (g == null) {
            title = "";
            hint = "";
        } else {
            int idx = Math.min(g.currentIndex(), g.rings.length - 1);
            title = g.isDone() ? "¡Barrido completo!" : "Vuelta " + (idx + 1) + " de " + g.rings.length + " · " + ringName(g.rings[idx].pitchDeg)
                    + " · " + Math.round((ring != null ? ring.progress() : 1) * 100) + "%";
            hint = hintText(g, ring);
        }
        if (!title.isEmpty()) canvas.drawText(title, vw / 2, y, pSmall);
        if (!hint.isEmpty()) {
            float tw = pText.measureText(hint);
            rect.set(vw / 2 - tw / 2 - 14 * density, y + 10 * density, vw / 2 + tw / 2 + 14 * density, y + 48 * density);
            canvas.drawRoundRect(rect, 12 * density, 12 * density, pBox);
            canvas.drawText(hint, vw / 2, y + 38 * density, pText);
        }
        // Velocidad
        if (g != null && src.capturing()) {
            float bw = 160 * density, bh = 6 * density, bx = vw / 2 - bw / 2, by = y + 60 * density;
            pFill.setColor(Color.argb(120, 0, 0, 0));
            canvas.drawRect(bx, by, bx + bw, by + bh, pFill);
            double sp = Math.min(1, g.speedDegPerSec() / SweepGuide.SPEED_MAX);
            pFill.setColor(sp < 0.6 ? Color.rgb(80, 220, 120) : (sp < 0.85 ? Color.rgb(255, 190, 60) : Color.rgb(255, 80, 70)));
            canvas.drawRect(bx, by, bx + (float) (bw * sp), by + bh, pFill);
            canvas.drawText("velocidad", vw / 2, by + bh + 16 * density, pSmall);
        }

        // Miniatura de la panorámica
        Bitmap pv = src.previewBitmap();
        if (pv != null && src.started()) {
            float mw = vw - 32 * density, mh = mw / 2f;
            float my = vh - insetBottom - bottomReserve - mh - 12 * density;
            rect.set(16 * density, my, 16 * density + mw, my + mh);
            canvas.drawBitmap(pv, null, rect, pBmp);
            pAim.setColor(Color.argb(200, 255, 255, 255));
            pAim.setStrokeWidth(1.5f * density);
            canvas.drawRect(rect, pAim);
            float hx = rect.left + (float) ((heading / 360.0 + 0.5) * mw);
            float hy = rect.top + (float) ((0.5 - pitch / 180.0) * mh);
            pFill.setColor(Color.rgb(255, 181, 71));
            canvas.drawCircle(hx, hy, 5 * density, pFill);
            pAim.setStrokeWidth(3 * density);
        }
        postInvalidateOnAnimation();
    }

    static String ringName(double pitch) {
        if (pitch > 5) return "arriba";
        if (pitch < -5) return "abajo";
        return "horizonte";
    }

    static String hintText(SweepGuide g, SweepGuide.Ring ring) {
        switch (g.hint()) {
            case SLOWER:
                return "Más despacio";
            case RAISE:
                return ring != null && ring.pitchDeg > 5 ? "Inclina más hacia arriba" : "Levanta la cámara";
            case LOWER:
                return ring != null && ring.pitchDeg < -5 ? "Inclina más hacia abajo" : "Baja la cámara";
            case STRAIGHTEN:
                return "Endereza el móvil";
            case RING_DONE:
                if (ring == null) return "¡Vuelta completa!";
                return ring.pitchDeg > 5 ? "¡Bien! Ahora inclina hacia ARRIBA" : (ring.pitchDeg < -5 ? "¡Bien! Ahora inclina hacia ABAJO" : "¡Vuelta completa!");
            case ALL_DONE:
                return "¡Terminado!";
            case START:
            case TURN:
            default:
                return "Gira despacio →";
        }
    }

    /** Proyecta la dirección (elevación e, rumbo h) en la pantalla. false si queda detrás. */
    private static boolean project(float[] r, CameraModel cam, float s, float ox, float oy, double e, double h, float[] out) {
        double dx = Math.cos(e) * Math.sin(h), dy = Math.cos(e) * Math.cos(h), dz = Math.sin(e);
        double ux = r[0] * dx + r[3] * dy + r[6] * dz;
        double uy = r[1] * dx + r[4] * dy + r[7] * dz;
        double uz = r[2] * dx + r[5] * dy + r[8] * dz;
        if (uz > -0.15) return false;
        double u = cam.cx + cam.fx * ux / -uz, v = cam.cy - cam.fy * uy / -uz;
        out[0] = (float) (ox + u * s);
        out[1] = (float) (oy + v * s);
        return true;
    }
}
