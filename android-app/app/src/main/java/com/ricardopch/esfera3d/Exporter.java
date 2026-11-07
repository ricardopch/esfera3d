package com.ricardopch.esfera3d;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.ricardopch.esfera3d.core.DepthEstimator;
import com.ricardopch.esfera3d.core.StereoSynth;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Copia una foto a la galería del móvil (carpeta Imágenes/Esfera3D):
 *  - *_3D_TB.jpg: estéreo 360 "arriba/abajo" (ojo izquierdo arriba), el formato que
 *    entienden la mayoría de visores de realidad virtual y reproductores 360.
 *  - *_2D.jpg: panorámica plana con metadatos de foto esférica (Google Fotos la muestra en 360°).
 */
public final class Exporter {
    public static final String FOLDER = "Esfera3D";

    private Exporter() {}

    public interface Progress {
        void onProgress(String msg);
    }

    public static String export(Context c, CaptureStore.Entry e, Progress p) throws IOException {
        String base = "Esfera3D_" + e.id;
        p.onProgress("Copiando la versión 2D…");
        writeToGallery(c, base + "_2D.jpg", CaptureStore.readAll(e.file(CaptureStore.CENTER)));

        int[] dims = new int[2];
        int[] left, right;
        boolean real = e.hasReal() && e.measured();
        if (real) {
            p.onProgress("Preparando el 3D real…");
            left = CaptureStore.readPixels(e.file(CaptureStore.LEFT), dims);
            right = CaptureStore.readPixels(e.file(CaptureStore.RIGHT), dims);
        } else {
            p.onProgress("Generando el 3D figurado…");
            int[] mono = CaptureStore.readPixels(e.file(CaptureStore.CENTER), dims);
            int[] qd = new int[2];
            byte[] q = CaptureStore.readDepth(e.file(CaptureStore.DEPTH), qd);
            double eye = 0.032 * new Prefs(c).strengthPct() / 100.0;
            left = StereoSynth.synthesize(mono, dims[0], dims[1], q, qd[0], qd[1], eye, true);
            right = StereoSynth.synthesize(mono, dims[0], dims[1], q, qd[0], qd[1], eye, false);
        }
        p.onProgress("Guardando el 3D…");
        int w = dims[0], h = dims[1];
        Bitmap tb = Bitmap.createBitmap(w, h * 2, Bitmap.Config.ARGB_8888);
        tb.setPixels(left, 0, w, 0, 0, w, h);
        left = null;
        tb.setPixels(right, 0, w, 0, h, w, h);
        right = null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream(w * h / 2);
        tb.compress(Bitmap.CompressFormat.JPEG, 92, bos);
        tb.recycle();
        writeToGallery(c, base + "_3D_TB.jpg", bos.toByteArray());
        return "Guardadas en Imágenes/" + FOLDER + ":\n• " + base + "_3D_TB.jpg (3D " + (real ? "real" : "figurado")
                + ", arriba/abajo)\n• " + base + "_2D.jpg (foto esférica normal)";
    }

    @SuppressWarnings("deprecation")
    static void writeToGallery(Context c, String name, byte[] bytes) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver r = c.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER);
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("La galería no aceptó el archivo");
            try (OutputStream os = r.openOutputStream(uri)) {
                if (os == null) throw new IOException("No se pudo escribir en la galería");
                os.write(bytes);
            }
            v.clear();
            v.put(MediaStore.Images.Media.IS_PENDING, 0);
            r.update(uri, v, null, null);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER);
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("No se pudo crear " + dir);
            File f = new File(dir, name);
            try (FileOutputStream os = new FileOutputStream(f)) {
                os.write(bytes);
            }
            MediaScannerConnection.scanFile(c, new String[]{f.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
        }
    }

    /** Usado por la importación: profundidad para fotos externas. */
    public static DepthEstimator.Result depthFor(int[] left, int[] right, int[] mono, int w, int h) {
        return DepthEstimator.estimate(left, right, mono, w, h, 0.032, 1.6, null);
    }
}
