package com.ricardopch.esfera3d;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import com.ricardopch.esfera3d.core.Mat3;

/**
 * Orientación del móvil a partir del sensor de rotación (giroscopio + acelerómetro).
 * Guarda un historial con marcas de tiempo para saber la orientación exacta en el
 * instante en que se tomó cada fotograma.
 */
public final class OrientationTracker implements SensorEventListener {
    private static final int N = 512;
    private final long[] ts = new long[N];
    private final float[] qw = new float[N], qx = new float[N], qy = new float[N], qz = new float[N];
    private int head = -1, count = 0;
    private final SensorManager sm;
    private final Sensor sensor;
    private final float[] tmpQ = new float[4];
    private volatile Recorder recorder;

    /** Recibe cada muestra del sensor (para el diagnóstico). */
    public interface Recorder {
        void onSample(long t, float w, float x, float y, float z);
    }

    public void setRecorder(Recorder r) {
        recorder = r;
    }

    public OrientationTracker(Context ctx) {
        sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        Sensor s = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (s == null) s = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        sensor = s;
    }

    public boolean available() {
        return sensor != null;
    }

    public void start() {
        if (sensor != null) sm.registerListener(this, sensor, 5000);
    }

    public void stop() {
        sm.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        SensorManager.getQuaternionFromVector(tmpQ, e.values);
        synchronized (this) {
            head = (head + 1) % N;
            ts[head] = e.timestamp;
            qw[head] = tmpQ[0];
            qx[head] = tmpQ[1];
            qy[head] = tmpQ[2];
            qz[head] = tmpQ[3];
            if (count < N) count++;
        }
        Recorder r = recorder;
        if (r != null) r.onSample(e.timestamp, tmpQ[0], tmpQ[1], tmpQ[2], tmpQ[3]);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    public synchronized long latestTimestamp() {
        return count == 0 ? 0 : ts[head];
    }

    /** Última orientación conocida (dispositivo → mundo). */
    public boolean latest(float[] out) {
        return rotationAt(Long.MAX_VALUE, out);
    }

    /** Orientación (dispositivo → mundo) en el instante t, interpolando el historial. */
    public synchronized boolean rotationAt(long t, float[] out) {
        if (count == 0) return false;
        if (t >= ts[head]) {
            Mat3.fromQuaternion(qw[head], qx[head], qy[head], qz[head], out);
            return true;
        }
        for (int k = 1; k < count; k++) {
            int j = (head - k + N) % N;
            if (ts[j] <= t) {
                int n = (j + 1) % N;
                long span = ts[n] - ts[j];
                float a = span > 0 ? (float) ((t - ts[j]) / (double) span) : 0f;
                float w0 = qw[j], x0 = qx[j], y0 = qy[j], z0 = qz[j];
                float w1 = qw[n], x1 = qx[n], y1 = qy[n], z1 = qz[n];
                if (w0 * w1 + x0 * x1 + y0 * y1 + z0 * z1 < 0) {
                    w1 = -w1;
                    x1 = -x1;
                    y1 = -y1;
                    z1 = -z1;
                }
                float w = w0 + (w1 - w0) * a, x = x0 + (x1 - x0) * a, y = y0 + (y1 - y0) * a, z = z0 + (z1 - z0) * a;
                float len = (float) Math.sqrt(w * w + x * x + y * y + z * z);
                Mat3.fromQuaternion(w / len, x / len, y / len, z / len, out);
                return true;
            }
        }
        int oldest = (head - count + 1 + N) % N;
        Mat3.fromQuaternion(qw[oldest], qx[oldest], qy[oldest], qz[oldest], out);
        return true;
    }
}
