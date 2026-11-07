package com.ricardopch.esfera3d;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;

import com.ricardopch.esfera3d.core.DepthEstimator;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Galería de fotos 3D 360 y punto de partida. */
public class MainActivity extends ComponentActivity {
    private static final int REQ_IMPORT = 10, REQ_STORAGE = 11;

    private GridView grid;
    private TextView txtEmpty;
    private final List<CaptureStore.Entry> items = new ArrayList<>();
    private final LruCache<String, Bitmap> thumbs = new LruCache<>(40);
    private Adapter adapter;
    private Prefs prefs;
    private CaptureStore.Entry pendingExport;
    private AlertDialog busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);
        Ui.padForSystemBars(findViewById(R.id.root), null);
        grid = findViewById(R.id.grid);
        txtEmpty = findViewById(R.id.txtEmpty);
        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> showActions(items.get(pos)));
        findViewById(R.id.btnNew).setOnClickListener(v -> startActivity(new Intent(this, CaptureActivity.class)));
        findViewById(R.id.btnImport).setOnClickListener(v -> pickImport());
        findViewById(R.id.btnSettings).setOnClickListener(v -> showSettings());
        findViewById(R.id.btnHelp).setOnClickListener(v -> showHelp());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        items.clear();
        items.addAll(CaptureStore.list(this));
        adapter.notifyDataSetChanged();
        txtEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void open(CaptureStore.Entry e, boolean vr) {
        Intent it = new Intent(this, ViewerActivity.class);
        it.putExtra(ViewerActivity.EXTRA_ID, e.id);
        it.putExtra(ViewerActivity.EXTRA_VR, vr);
        startActivity(it);
    }

    private void showActions(CaptureStore.Entry e) {
        String[] opts = {"Ver en la pantalla", "Ver con gafas VR", "Guardar en la galería del móvil", "Borrar"};
        new AlertDialog.Builder(this)
                .setTitle(e.title())
                .setItems(opts, (d, which) -> {
                    if (which == 0) open(e, false);
                    else if (which == 1) open(e, true);
                    else if (which == 2) export(e);
                    else confirmDelete(e);
                })
                .show();
    }

    private void confirmDelete(CaptureStore.Entry e) {
        new AlertDialog.Builder(this)
                .setMessage("¿Borrar esta foto de la app? (Las copias guardadas en la galería no se tocan.)")
                .setPositiveButton("Borrar", (d, w) -> {
                    CaptureStore.delete(e);
                    thumbs.remove(e.id);
                    refresh();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ------------------------------------------------------------------ exportar

    private void export(CaptureStore.Entry e) {
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingExport = e;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        showBusy("Guardando en la galería…");
        new Thread(() -> {
            try {
                String msg = Exporter.export(this, e, m -> runOnUiThread(() -> setBusy(m)));
                runOnUiThread(() -> {
                    hideBusy();
                    new AlertDialog.Builder(this).setTitle("Guardado").setMessage(msg + "\n\nLa versión 3D puedes abrirla en reproductores de vídeo/foto 360 que admitan estéreo «arriba/abajo».")
                            .setPositiveButton("Vale", null).show();
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    hideBusy();
                    Toast.makeText(this, "No se pudo guardar: " + t.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_STORAGE && pendingExport != null) {
            CaptureStore.Entry e = pendingExport;
            pendingExport = null;
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) export(e);
            else Toast.makeText(this, "Sin permiso no se puede guardar en la galería.", Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ importar

    private void pickImport() {
        new AlertDialog.Builder(this)
                .setTitle("Importar una foto 360")
                .setMessage("Elige una foto 360 equirectangular (proporción 2:1), o una estéreo «arriba/abajo» (1:1) o «lado a lado» (4:1). Si es plana, la app le crea un 3D figurado.")
                .setPositiveButton("Elegir foto", (d, w) -> {
                    Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    it.addCategory(Intent.CATEGORY_OPENABLE);
                    it.setType("image/*");
                    startActivityForResult(it, REQ_IMPORT);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_IMPORT && res == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            showBusy("Importando…");
            new Thread(() -> {
                try {
                    String id = importImage(uri);
                    runOnUiThread(() -> {
                        hideBusy();
                        refresh();
                        CaptureStore.Entry e = CaptureStore.load(this, id);
                        if (e != null) open(e, false);
                    });
                } catch (Throwable t) {
                    runOnUiThread(() -> {
                        hideBusy();
                        new AlertDialog.Builder(this).setMessage("No se pudo importar: " + t.getMessage())
                                .setPositiveButton("Vale", null).show();
                    });
                }
            }).start();
        }
    }

    private String importImage(Uri uri) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        int w = o.outWidth, h = o.outHeight;
        if (w <= 0 || h <= 0) throw new Exception("no es una imagen válida");
        double ratio = w / (double) h;
        int layout; // 0 plana, 1 arriba/abajo, 2 lado a lado
        if (Math.abs(ratio - 2) < 0.15) layout = 0;
        else if (Math.abs(ratio - 1) < 0.1) layout = 1;
        else if (Math.abs(ratio - 4) < 0.3) layout = 2;
        else throw new Exception("la proporción " + w + "×" + h + " no corresponde a una foto 360 equirectangular");
        int eyeW = layout == 2 ? w / 2 : w;
        int target = Math.min(prefs.panoWidth(), eyeW >= 3000 ? 4096 : 2048);
        int sample = 1;
        while (eyeW / (sample * 2) >= target) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        o2.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap src;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            src = BitmapFactory.decodeStream(in, null, o2);
        }
        if (src == null) throw new Exception("no se pudo decodificar la imagen");
        int tw = target, th = target / 2;
        int[] left, right = null;
        if (layout == 0) {
            left = scaled(src, 0, 0, src.getWidth(), src.getHeight(), tw, th);
        } else if (layout == 1) {
            int hh = src.getHeight() / 2;
            left = scaled(src, 0, 0, src.getWidth(), hh, tw, th);
            right = scaled(src, 0, hh, src.getWidth(), hh, tw, th);
        } else {
            int hw = src.getWidth() / 2;
            left = scaled(src, 0, 0, hw, src.getHeight(), tw, th);
            right = scaled(src, hw, 0, hw, src.getHeight(), tw, th);
        }
        src.recycle();
        DepthEstimator.Result d = Exporter.depthFor(left, right, left, tw, th);
        JSONObject info = new JSONObject();
        info.put("tipo", "importada");
        info.put("formato", layout == 0 ? "plana" : (layout == 1 ? "arriba/abajo" : "lado a lado"));
        CaptureStore.Entry e = CaptureStore.save(this, CaptureStore.newId(), left, right, left, tw, th, d, info);
        if (e == null) throw new Exception("no se pudo guardar");
        if (right != null) {
            // Las fotos estéreo importadas son 3D real aunque no se pueda medir su paralaje
            e.info.put("paralajeMedida", true);
            try (java.io.FileOutputStream os = new java.io.FileOutputStream(e.file(CaptureStore.INFO))) {
                os.write(e.info.toString().getBytes("UTF-8"));
            }
        }
        return e.id;
    }

    private static int[] scaled(Bitmap src, int x, int y, int w, int h, int tw, int th) {
        Bitmap part = Bitmap.createBitmap(src, x, y, w, h);
        Bitmap s = Bitmap.createScaledBitmap(part, tw, th, true);
        int[] px = new int[tw * th];
        s.getPixels(px, 0, tw, 0, 0, tw, th);
        if (s != part) s.recycle();
        if (part != src) part.recycle();
        return px;
    }

    // ------------------------------------------------------------------ ajustes y ayuda

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(grid, 20);
        box.setPadding(pad, pad / 2, pad, 0);

        TextView tArm = new TextView(this);
        box.addView(tArm);
        SeekBar sb = new SeekBar(this);
        sb.setMax(60);
        sb.setProgress(prefs.armCm() - 20);
        tArm.setText(armText(prefs.armCm()));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                tArm.setText(armText(p + 20));
                if (u) prefs.setArmCm(p + 20);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        box.addView(sb);

        TextView tq = new TextView(this);
        tq.setText("Calidad de la foto");
        tq.setPadding(0, Ui.dp(grid, 18), 0, 0);
        box.addView(tq);
        RadioGroup rg = new RadioGroup(this);
        RadioButton r1 = new RadioButton(this);
        r1.setId(2001);
        r1.setText("Alta (4096 × 2048 por ojo)");
        RadioButton r2 = new RadioButton(this);
        r2.setId(2002);
        r2.setText("Ligera (2048 × 1024, para móviles con poca memoria)");
        rg.addView(r1);
        rg.addView(r2);
        rg.check(prefs.panoWidth() >= 4096 ? 2001 : 2002);
        rg.setOnCheckedChangeListener((g, id) -> prefs.setPanoWidth(id == 2001 ? 4096 : 2048));
        box.addView(rg);

        new AlertDialog.Builder(this)
                .setTitle("Ajustes")
                .setView(box)
                .setPositiveButton("Hecho", null)
                .show();
    }

    private static String armText(int cm) {
        return "Distancia del móvil a tu cuerpo al girar: " + cm + " cm\n(con el brazo estirado suele ser 40–55 cm; cuanto mayor, menos se separan las franjas de cada ojo)";
    }

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle("Cómo funciona")
                .setMessage("HACER LA FOTO\n"
                        + "• Sujeta el móvil en vertical con el brazo estirado y gira sobre tus pies, despacio (una vuelta en unos 20–30 s). El móvil debe describir un círculo alrededor de ti: así cada parte de la escena se ve desde dos puntos distintos, como con dos ojos.\n"
                        + "• Sigue la línea guía con el punto central. En «Esfera completa» harás 3 vueltas: horizonte, inclinado hacia arriba y hacia abajo.\n"
                        + "• Evita que haya gente u objetos moviéndose cerca.\n\n"
                        + "EL 3D\n"
                        + "• 3D real: se construye con franjas distintas de cada fotograma para cada ojo (estéreo omnidireccional). Funciona si giraste con el brazo estirado.\n"
                        + "• 3D figurado: la app calcula un mapa de profundidad a partir del barrido (y, donde no puede medirla, usa un modelo de suelo y paredes) y genera los dos ojos. Sirve también si giraste sin separar el móvil del cuerpo, y permite ajustar la intensidad.\n\n"
                        + "VERLA\n"
                        + "• En la pantalla: mueve el móvil hacia donde quieras mirar; arrastra con el dedo para girar y pellizca para acercar. Con gafas de cartón rojo/cian, activa «Anaglifo».\n"
                        + "• Con gafas VR: pulsa «Gafas VR», mete el móvil en el visor y mira a tu alrededor. Toca la pantalla (botón del visor) para centrar la vista. Si se ve doble o curvo, en «Ajustes» del visor cambia el tipo de gafas, la separación de lentes o el tamaño.\n\n"
                        + "GUARDAR\n"
                        + "• Las fotos se guardan dentro de la app. Con «Guardar en la galería del móvil» se copian a Imágenes/Esfera3D en formato 3D «arriba/abajo» y en 2D (Google Fotos muestra la 2D como foto esférica).")
                .setPositiveButton("Entendido", null)
                .show();
    }

    private void showBusy(String msg) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(grid, 22);
        box.setPadding(pad, pad, pad, pad);
        TextView t = new TextView(this);
        t.setId(3001);
        t.setText(msg);
        box.addView(t);
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setIndeterminate(true);
        box.addView(pb);
        busy = new AlertDialog.Builder(this).setView(box).setCancelable(false).show();
    }

    private void setBusy(String msg) {
        if (busy != null) {
            TextView t = busy.findViewById(3001);
            if (t != null) t.setText(msg);
        }
    }

    private void hideBusy() {
        if (busy != null) busy.dismiss();
        busy = null;
    }

    // ------------------------------------------------------------------ rejilla

    private class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int i) {
            return items.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            if (v == null) v = getLayoutInflater().inflate(R.layout.item_capture, parent, false);
            CaptureStore.Entry e = items.get(pos);
            ImageView iv = v.findViewById(R.id.thumb);
            ((TextView) v.findViewById(R.id.title)).setText(e.title());
            ((TextView) v.findViewById(R.id.subtitle)).setText(e.subtitle());
            Bitmap bm = thumbs.get(e.id);
            if (bm == null) {
                bm = BitmapFactory.decodeFile(e.file(CaptureStore.THUMB).getAbsolutePath());
                if (bm != null) thumbs.put(e.id, bm);
            }
            iv.setImageBitmap(bm);
            return v;
        }
    }
}
