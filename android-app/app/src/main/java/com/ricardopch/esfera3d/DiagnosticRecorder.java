package com.ricardopch.esfera3d;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Guarda los datos en bruto de un barrido (fotogramas reducidos, orientaciones, tiempos y
 * parámetros de la cámara) en un .zip en Descargas, para poder reproducir el cosido fuera
 * del móvil y ajustar el método con datos reales.
 */
final class DiagnosticRecorder {
    private static final String TAG = "Esfera3D";
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final File zipFile;
    private ZipOutputStream zip;
    private final StringBuilder info = new StringBuilder();
    private final StringBuilder frames = new StringBuilder();
    private final StringBuilder sensor = new StringBuilder();
    private final String name;
    private int written;

    DiagnosticRecorder(Context c, String id) {
        name = "Esfera3D_diagnostico_" + id + ".zip";
        zipFile = new File(c.getCacheDir(), name);
        try {
            zip = new ZipOutputStream(new FileOutputStream(zipFile));
        } catch (Exception e) {
            Log.w(TAG, "diagnóstico", e);
        }
        frames.append("indice,t_camara,t_sensor_centro,exposicion_ns,lectura_ns,rotacion,vuelta_nominal,")
                .append("rumbo,incl,giro,corr_rumbo,corr_incl,usados,imagen,")
                .append("rc0,rc1,rc2,rc3,rc4,rc5,rc6,rc7,rc8,rl0,rl1,rl2,rl3,rl4,rl5,rl6,rl7,rl8,rr0,rr1,rr2,rr3,rr4,rr5,rr6,rr7,rr8\n");
        sensor.append("t,w,x,y,z\n");
    }

    synchronized void info(String line) {
        info.append(line).append('\n');
    }

    synchronized void sensorSample(long t, float w, float x, float y, float z) {
        sensor.append(t).append(',').append(w).append(',').append(x).append(',').append(y).append(',').append(z).append('\n');
    }

    /** Anota un fotograma; si px no es null, guarda también la imagen reducida. */
    synchronized void frame(String csvLine, int index, int[] px, int w, int h) {
        frames.append(csvLine).append('\n');
        if (px == null || zip == null) return;
        io.execute(() -> {
            try {
                Bitmap b = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
                zip.putNextEntry(new ZipEntry(String.format(java.util.Locale.ROOT, "fotogramas/%05d.jpg", index)));
                b.compress(Bitmap.CompressFormat.JPEG, 82, zip);
                zip.closeEntry();
                b.recycle();
                written++;
            } catch (Exception e) {
                Log.w(TAG, "diagnóstico", e);
            }
        });
    }

    /** Cierra el .zip y lo copia a Descargas. Devuelve dónde quedó, o null si falló. Bloquea hasta terminar. */
    String finish(Context c) {
        final String[] where = new String[1];
        final String infoTxt, framesTxt, sensorTxt;
        synchronized (this) {
            infoTxt = info.toString();
            framesTxt = frames.toString();
            sensorTxt = sensor.toString();
        }
        io.execute(() -> {
            try {
                if (zip == null) return;
                put("info.txt", infoTxt + "imagenes_guardadas=" + written + "\n");
                put("fotogramas.csv", framesTxt);
                put("sensor.csv", sensorTxt);
                zip.close();
                where[0] = copyToDownloads(c);
            } catch (Exception e) {
                Log.w(TAG, "diagnóstico", e);
            }
        });
        io.shutdown();
        try {
            io.awaitTermination(120, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return where[0];
    }

    private void put(String entry, String text) throws Exception {
        zip.putNextEntry(new ZipEntry(entry));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    @SuppressWarnings("deprecation")
    private String copyToDownloads(Context c) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver r = c.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            v.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return null;
            try (InputStream in = new FileInputStream(zipFile); OutputStream os = r.openOutputStream(uri)) {
                if (os == null) return null;
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            v.clear();
            v.put(MediaStore.Downloads.IS_PENDING, 0);
            r.update(uri, v, null, null);
            zipFile.delete();
            return "Descargas/" + name;
        } else {
            File dir = c.getExternalFilesDir(null);
            if (dir == null) return zipFile.getAbsolutePath();
            File dst = new File(dir, name);
            if (!zipFile.renameTo(dst)) return zipFile.getAbsolutePath();
            return dst.getAbsolutePath();
        }
    }
}
