package com.ricardopch.esfera3d;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Guarda en disco (JPEG) los recortes de fotograma del barrido, en segundo plano, para
 * coserlos al terminar. Si el móvil no da abasto, se descartan fotogramas en vez de frenar
 * la cámara.
 */
final class FrameWriter {
    private static final String TAG = "Esfera3D";
    private final File dir;
    private final ThreadPoolExecutor pool;
    private final ArrayDeque<byte[]> free = new ArrayDeque<>();
    private final ThreadLocal<Bitmap> bitmaps = new ThreadLocal<>();
    private volatile int written, failed;

    FrameWriter(File dir) {
        this.dir = dir;
        dir.mkdirs();
        pool = new ThreadPoolExecutor(2, 2, 10, TimeUnit.SECONDS, new ArrayBlockingQueue<>(24));
    }

    File dir() {
        return dir;
    }

    File file(int index) {
        return new File(dir, index + ".jpg");
    }

    /** ¿Cabe otro fotograma en la cola? */
    boolean canAccept() {
        return pool.getQueue().remainingCapacity() > 0;
    }

    /** Array reutilizable de al menos «size» bytes. */
    synchronized byte[] obtain(int size) {
        byte[] b = free.poll();
        return (b != null && b.length >= size) ? b : new byte[size];
    }

    private synchronized void recycle(byte[] b) {
        if (free.size() < 28) free.add(b);
    }

    /** @param rgba recorte cw × ch en RGBA (4 bytes por píxel, sin relleno); obtenido con obtain() */
    void submit(int index, byte[] rgba, int cw, int ch) {
        try {
            pool.execute(() -> {
                try {
                    Bitmap bmp = bitmaps.get();
                    if (bmp == null || bmp.getWidth() != cw || bmp.getHeight() != ch) {
                        bmp = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
                        bitmaps.set(bmp);
                    }
                    bmp.copyPixelsFromBuffer(ByteBuffer.wrap(rgba, 0, cw * ch * 4));
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(file(index)), 1 << 16)) {
                        bmp.compress(Bitmap.CompressFormat.JPEG, 90, os);
                    }
                    written++;
                } catch (Throwable t) {
                    failed++;
                    Log.w(TAG, "guardando fotograma", t);
                } finally {
                    recycle(rgba);
                }
            });
        } catch (Exception rejected) {
            failed++;
            recycle(rgba);
        }
    }

    int written() {
        return written;
    }

    int failed() {
        return failed;
    }

    /** Espera a que se terminen de escribir los fotogramas pendientes. */
    void finish() {
        pool.shutdown();
        try {
            pool.awaitTermination(60, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    /** Lee un fotograma guardado; dims recibe {ancho, alto}. null si no existe. */
    int[] read(int index, int[] dims) {
        File f = file(index);
        if (!f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        o.inScaled = false;
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (b == null) return null;
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        b.recycle();
        dims[0] = w;
        dims[1] = h;
        return px;
    }

    void deleteAll() {
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        dir.delete();
    }
}
