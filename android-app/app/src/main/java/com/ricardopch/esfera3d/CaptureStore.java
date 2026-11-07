package com.ricardopch.esfera3d;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.ricardopch.esfera3d.core.DepthEstimator;
import com.ricardopch.esfera3d.core.JpegXmp;
import com.ricardopch.esfera3d.core.PanoPost;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Almacén de fotos dentro de la app. Cada foto es una carpeta con:
 * izquierda.jpg y derecha.jpg (estéreo real), centro.jpg (panorámica 2D con metadatos
 * de foto esférica), profundidad.png (para el estéreo figurado), miniatura.jpg e info.json.
 */
public final class CaptureStore {
    public static final String LEFT = "izquierda.jpg", RIGHT = "derecha.jpg", CENTER = "centro.jpg",
            DEPTH = "profundidad.png", THUMB = "miniatura.jpg", INFO = "info.json";

    public static final class Entry {
        public String id;
        public File dir;
        public JSONObject info;

        public boolean hasReal() {
            return new File(dir, LEFT).exists() && new File(dir, RIGHT).exists();
        }

        /** true si el barrido tuvo paralaje suficiente para que el 3D real merezca la pena. */
        public boolean measured() {
            return info.optBoolean("paralajeMedida", false);
        }

        public String kind() {
            return info.optString("tipo", "barrido");
        }

        public String title() {
            try {
                Date d = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).parse(id);
                if (d != null) return new SimpleDateFormat("d MMM yyyy, HH:mm", new Locale("es", "ES")).format(d);
            } catch (Exception ignored) {
            }
            return id;
        }

        public String subtitle() {
            if ("importada".equals(kind())) {
                return hasReal() ? "Importada · 3D" : "Importada · 3D figurado";
            }
            if (hasReal() && measured()) return "3D real + figurado";
            return "3D figurado";
        }

        public File file(String name) {
            return new File(dir, name);
        }
    }

    private CaptureStore() {}

    public static File root(Context c) {
        File r = new File(c.getFilesDir(), "capturas");
        if (!r.exists()) r.mkdirs();
        return r;
    }

    public static String newId() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
    }

    public static List<Entry> list(Context c) {
        List<Entry> out = new ArrayList<>();
        File[] dirs = root(c).listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                Entry e = load(d);
                if (e != null) out.add(e);
            }
        }
        Collections.sort(out, (a, b) -> b.id.compareTo(a.id));
        return out;
    }

    public static Entry load(Context c, String id) {
        return load(new File(root(c), id));
    }

    static Entry load(File d) {
        File info = new File(d, INFO);
        if (!d.isDirectory() || !info.exists() || !new File(d, CENTER).exists()) return null;
        try {
            Entry e = new Entry();
            e.id = d.getName();
            e.dir = d;
            e.info = new JSONObject(new String(readAll(info), "UTF-8"));
            return e;
        } catch (Exception ex) {
            return null;
        }
    }

    public static void delete(Entry e) {
        File[] fs = e.dir.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        e.dir.delete();
    }

    /** Guarda una foto completa. left/right pueden ser null (solo estéreo figurado). */
    public static Entry save(Context c, String id, int[] left, int[] right, int[] mono, int w, int h,
                             DepthEstimator.Result depth, JSONObject info) throws IOException {
        File dir = new File(root(c), id);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("No se pudo crear " + dir);
        writeJpeg(new File(dir, CENTER), mono, w, h, true);
        if (left != null && right != null) {
            writeJpeg(new File(dir, LEFT), left, w, h, false);
            writeJpeg(new File(dir, RIGHT), right, w, h, false);
        }
        writeDepth(new File(dir, DEPTH), depth.q, depth.w, depth.h);
        int tw = 512, th = 256;
        writeJpeg(new File(dir, THUMB), PanoPost.downsample(mono, w, h, tw, th), tw, th, false);
        try {
            info.put("ancho", w);
            info.put("alto", h);
            info.put("paralajeMedida", depth.measured);
            info.put("profundidad", depth.describe());
        } catch (JSONException ignored) {
        }
        try (FileOutputStream os = new FileOutputStream(new File(dir, INFO))) {
            os.write(info.toString().getBytes("UTF-8"));
        }
        return load(dir);
    }

    public static byte[] encodeJpeg(int[] px, int w, int h, boolean gpano, int quality) {
        Bitmap bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(w * h / 3);
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos);
        bmp.recycle();
        byte[] bytes = bos.toByteArray();
        if (gpano) bytes = JpegXmp.insertXmp(bytes, JpegXmp.gpano(w, h, null));
        return bytes;
    }

    static void writeJpeg(File f, int[] px, int w, int h, boolean gpano) throws IOException {
        byte[] bytes = encodeJpeg(px, w, h, gpano, 92);
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(bytes);
        }
    }

    static void writeDepth(File f, byte[] q, int w, int h) throws IOException {
        int[] px = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            int g = q[i] & 0xFF;
            px[i] = 0xFF000000 | (g << 16) | (g << 8) | g;
        }
        Bitmap bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        try (FileOutputStream os = new FileOutputStream(f)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
        }
        bmp.recycle();
    }

    /** Lee los píxeles de una imagen; dims recibe {ancho, alto}. */
    public static int[] readPixels(File f, int[] dims) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        o.inScaled = false;
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (b == null) throw new IOException("No se pudo leer " + f.getName());
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        b.recycle();
        if (dims != null) {
            dims[0] = w;
            dims[1] = h;
        }
        return px;
    }

    public static byte[] readDepth(File f, int[] dims) throws IOException {
        int[] px = readPixels(f, dims);
        byte[] q = new byte[px.length];
        for (int i = 0; i < px.length; i++) q[i] = (byte) ((px[i] >> 16) & 0xFF);
        return q;
    }

    public static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        }
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
