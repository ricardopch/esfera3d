package com.ricardopch.esfera3d;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.util.Log;

import com.ricardopch.esfera3d.core.Mat3;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Dibuja la foto 360: en pantalla normal (ventana que se mueve con el móvil) o, en modo
 * gafas, dos imágenes lado a lado (una por ojo) con la distorsión de la lente compensada.
 */
public class PanoRenderer implements GLSurfaceView.Renderer {
    public static final int SRC_REAL = 0, SRC_FIGURADO = 1, SRC_PLANO = 2;

    public interface Listener {
        void onLoaded(boolean ok, String error);
    }

    private final Context ctx;
    private final File dir;
    private final Listener listener;

    // Estado que cambia la interfaz (se lee en el hilo de dibujo)
    public volatile int source = SRC_REAL;
    public volatile boolean vr;
    public volatile boolean anaglyph;
    public volatile boolean gyro = true;
    public volatile float yaw;          // giro añadido por el usuario (rad)
    public volatile float pitch;        // inclinación manual sin giroscopio (rad)
    public volatile float fovY = 75f;   // grados, pantalla normal
    public volatile float strength = 1f;
    public volatile float k1 = 0.34f, k2 = 0.55f, screenToLens = 0.039f, lensSep = 0.064f, vrScale = 1f;
    public volatile float dpi = 400f;
    public volatile int displayRotation;
    public volatile boolean recenterRequested = true;

    private final float[] devRot = Mat3.identity();
    private boolean hasDevRot;

    private int program, aPos;
    private int uTex, uTex2, uDepth, uMode, uAnaglyph, uEyeSign, uEyeK, uRot, uCenter, uPxToTan, uK, uMaxTan;
    private int texL, texR, texM, texD;
    private boolean hasReal;
    private int width, height;
    private FloatBuffer quad;

    public PanoRenderer(Context ctx, File dir, Listener listener) {
        this.ctx = ctx;
        this.dir = dir;
        this.listener = listener;
    }

    public boolean hasReal() {
        return hasReal;
    }

    /** Orientación del móvil (dispositivo → mundo), desde el sensor. */
    public void setDeviceRotation(float[] r) {
        synchronized (devRot) {
            System.arraycopy(r, 0, devRot, 0, 9);
            hasDevRot = true;
        }
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        try {
            program = buildProgram(readRaw(R.raw.pano_vert), readRaw(R.raw.pano_frag));
            aPos = GLES20.glGetAttribLocation(program, "aPos");
            uTex = GLES20.glGetUniformLocation(program, "uTex");
            uTex2 = GLES20.glGetUniformLocation(program, "uTex2");
            uDepth = GLES20.glGetUniformLocation(program, "uDepth");
            uMode = GLES20.glGetUniformLocation(program, "uMode");
            uAnaglyph = GLES20.glGetUniformLocation(program, "uAnaglyph");
            uEyeSign = GLES20.glGetUniformLocation(program, "uEyeSign");
            uEyeK = GLES20.glGetUniformLocation(program, "uEyeK");
            uRot = GLES20.glGetUniformLocation(program, "uRot");
            uCenter = GLES20.glGetUniformLocation(program, "uCenter");
            uPxToTan = GLES20.glGetUniformLocation(program, "uPxToTan");
            uK = GLES20.glGetUniformLocation(program, "uK");
            uMaxTan = GLES20.glGetUniformLocation(program, "uMaxTan");
            quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).position(0);

            int[] max = new int[1];
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, max, 0);
            int maxTex = Math.max(2048, max[0]);
            File fl = new File(dir, CaptureStore.LEFT), fr = new File(dir, CaptureStore.RIGHT);
            hasReal = fl.exists() && fr.exists();
            texM = loadTexture(new File(dir, CaptureStore.CENTER), maxTex);
            texD = loadTexture(new File(dir, CaptureStore.DEPTH), maxTex);
            if (hasReal) {
                texL = loadTexture(fl, maxTex);
                texR = loadTexture(fr, maxTex);
            } else {
                texL = texM;
                texR = texM;
            }
            if (listener != null) listener.onLoaded(true, null);
        } catch (Throwable t) {
            Log.e("Esfera3D", "visor", t);
            if (listener != null) listener.onLoaded(false, t.getMessage());
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        width = w;
        height = h;
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (program == 0 || texM == 0) return;

        float[] view = viewMatrix();
        GLES20.glUseProgram(program);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glUniformMatrix3fv(uRot, 1, false, Mat3.transpose(view), 0);
        GLES20.glUniform1i(uTex, 0);
        GLES20.glUniform1i(uTex2, 1);
        GLES20.glUniform1i(uDepth, 2);
        bind(2, texD);
        int src = source;
        if (src == SRC_REAL && !hasReal) src = SRC_FIGURADO;
        GLES20.glUniform1f(uMode, src == SRC_FIGURADO ? 1f : 0f);
        GLES20.glUniform1f(uEyeK, 0.032f / 0.3f * strength);
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);

        if (!vr) {
            GLES20.glViewport(0, 0, width, height);
            GLES20.glScissor(0, 0, width, height);
            float s = (float) Math.tan(Math.toRadians(fovY) / 2) / (height / 2f);
            GLES20.glUniform2f(uCenter, width / 2f, height / 2f);
            GLES20.glUniform2f(uPxToTan, s, s);
            GLES20.glUniform2f(uK, 0, 0);
            GLES20.glUniform1f(uMaxTan, 1000f);
            if (anaglyph && src != SRC_PLANO) {
                GLES20.glUniform1f(uAnaglyph, 1f);
                bind(0, src == SRC_REAL ? texL : texM);
                bind(1, src == SRC_REAL ? texR : texM);
            } else {
                GLES20.glUniform1f(uAnaglyph, 0f);
                GLES20.glUniform1f(uEyeSign, -1f);
                bind(0, src == SRC_REAL ? texL : texM);
                bind(1, texM);
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        } else {
            GLES20.glUniform1f(uAnaglyph, 0f);
            float mpp = 0.0254f / Math.max(100f, dpi);
            float sepPx = lensSep / mpp;
            int half = width / 2;
            boolean noLens = k1 == 0f && k2 == 0f;
            for (int eye = 0; eye < 2; eye++) {
                int vx = eye * half;
                GLES20.glViewport(vx, 0, half, height);
                GLES20.glScissor(vx, 0, half, height);
                float cx = width / 2f + (eye == 0 ? -sepPx / 2f : sepPx / 2f);
                // Si la pantalla es pequeña y el centro de lente cae fuera de la mitad, se centra en ella.
                if (cx < vx || cx > vx + half) cx = vx + half / 2f;
                float s;
                float maxTan;
                if (noLens) {
                    s = (float) Math.tan(Math.toRadians(45)) / (half / 2f) * vrScale;
                    maxTan = 1000f;
                } else {
                    s = mpp / screenToLens * vrScale;
                    maxTan = (float) Math.tan(Math.toRadians(62));
                }
                GLES20.glUniform2f(uCenter, cx, height / 2f);
                GLES20.glUniform2f(uPxToTan, s, s);
                GLES20.glUniform2f(uK, k1, k2);
                GLES20.glUniform1f(uMaxTan, maxTan);
                GLES20.glUniform1f(uEyeSign, eye == 0 ? -1f : 1f);
                int tex;
                if (src == SRC_REAL) tex = eye == 0 ? texL : texR;
                else tex = texM;
                bind(0, tex);
                bind(1, texM);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            }
        }
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
    }

    /** Matriz cámara virtual → mundo (por filas). */
    private float[] viewMatrix() {
        float[] base;
        if (gyro) {
            float[] dev;
            synchronized (devRot) {
                dev = devRot.clone();
                if (!hasDevRot) dev = levelLook();
            }
            float[] m = Mat3.mul(dev, screenToDevice(displayRotation));
            if (recenterRequested && hasDevRot) {
                recenterRequested = false;
                // El rumbo actual pasa a ser el inicio de la foto
                double fx = -m[2], fy = -m[5];
                float newYaw = (float) Math.atan2(fx, fy);
                yaw = newYaw;
            }
            base = Mat3.mul(Mat3.rotZ(yaw), m);
        } else {
            // Sin giroscopio: mirada horizontal girada con el dedo
            float[] b = levelLookCamera();
            base = Mat3.mul(Mat3.rotZ(yaw), Mat3.mul(Mat3.rotX(pitch), b));
        }
        return base;
    }

    /** Cámara mirando al horizonte hacia el rumbo 0 (derecha = X, arriba = Z, atrás = −Y). */
    private static float[] levelLookCamera() {
        return new float[]{1, 0, 0, 0, 0, -1, 0, 1, 0};
    }

    private static float[] levelLook() {
        return levelLookCamera();
    }

    /** Ejes de la pantalla (derecha, arriba, hacia el usuario) expresados en ejes del dispositivo. */
    static float[] screenToDevice(int rotation) {
        switch (rotation) {
            case 1: // ROTATION_90: el borde superior del móvil queda a la izquierda
                return new float[]{0, 1, 0, -1, 0, 0, 0, 0, 1};
            case 2: // ROTATION_180
                return new float[]{-1, 0, 0, 0, -1, 0, 0, 0, 1};
            case 3: // ROTATION_270
                return new float[]{0, -1, 0, 1, 0, 0, 0, 0, 1};
            default:
                return Mat3.identity();
        }
    }

    private static void bind(int unit, int tex) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
    }

    private int loadTexture(File f, int maxTex) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int sample = 1;
        while (o.outWidth / sample > maxTex) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inScaled = false;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (b == null) throw new IOException("No se pudo abrir " + f.getName());
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        boolean pot = isPow2(b.getWidth()) && isPow2(b.getHeight());
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, pot ? GLES20.GL_REPEAT : GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
        b.recycle();
        return t[0];
    }

    private static boolean isPow2(int v) {
        return v > 0 && (v & (v - 1)) == 0;
    }

    private String readRaw(int id) throws IOException {
        try (InputStream in = ctx.getResources().openRawResource(id)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        }
    }

    private static int buildProgram(String vs, String fs) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vs), f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("Enlazado: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("Sombreador: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }
}
