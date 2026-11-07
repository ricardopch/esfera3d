package com.ricardopch.esfera3d;

import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

/** Visor: en la pantalla (moviendo el móvil) o con gafas de realidad virtual. */
public class ViewerActivity extends ComponentActivity implements SensorEventListener {
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_VR = "vr";

    private GLSurfaceView glView;
    private PanoRenderer renderer;
    private View topBar, bottomBar, txtVrHint, txtLoading;
    private Button btnStereo, btnAnaglyph, btnGyro;
    private TextView txtInfo;
    private SensorManager sm;
    private Sensor rotSensor;
    private DisplayManager dm;
    private Prefs prefs;
    private CaptureStore.Entry entry;
    private final float[] rTmp = new float[9];
    private boolean controlsVisible = true;
    private GestureDetector gestures;
    private ScaleGestureDetector scaler;

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int id) {
        }

        @Override
        public void onDisplayRemoved(int id) {
        }

        @Override
        public void onDisplayChanged(int id) {
            updateDisplay();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_viewer);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = new Prefs(this);
        entry = CaptureStore.load(this, getIntent().getStringExtra(EXTRA_ID));
        if (entry == null) {
            Toast.makeText(this, "No se encontró la foto", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        glView = findViewById(R.id.glView);
        topBar = findViewById(R.id.topBar);
        bottomBar = findViewById(R.id.bottomBar);
        txtVrHint = findViewById(R.id.txtVrHint);
        txtLoading = findViewById(R.id.txtLoading);
        txtInfo = findViewById(R.id.txtInfo);
        btnStereo = findViewById(R.id.btnStereo);
        btnAnaglyph = findViewById(R.id.btnAnaglyph);
        btnGyro = findViewById(R.id.btnGyro);
        Ui.padForSystemBars(findViewById(R.id.uiLayer), null);

        renderer = new PanoRenderer(this, entry.dir, (ok, err) -> runOnUiThread(() -> {
            txtLoading.setVisibility(View.GONE);
            if (!ok) {
                new AlertDialog.Builder(this).setMessage("No se pudo mostrar la foto: " + err)
                        .setPositiveButton("Cerrar", (d, w) -> finish()).show();
            }
        }));
        boolean real = entry.hasReal() && entry.measured();
        renderer.source = real ? PanoRenderer.SRC_REAL : PanoRenderer.SRC_FIGURADO;
        applyLensPrefs();
        glView.setEGLContextClientVersion(2);
        glView.setPreserveEGLContextOnPause(true);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        rotSensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (rotSensor == null) rotSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rotSensor == null) renderer.gyro = false;
        dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);

        txtInfo.setText(entry.title() + "\n" + entry.subtitle());
        findViewById(R.id.btnClose).setOnClickListener(v -> finish());
        findViewById(R.id.btnVr).setOnClickListener(v -> setVr(true));
        btnStereo.setOnClickListener(v -> cycleStereo());
        btnAnaglyph.setOnClickListener(v -> {
            renderer.anaglyph = !renderer.anaglyph;
            updateButtons();
        });
        btnGyro.setOnClickListener(v -> {
            if (rotSensor == null) {
                Toast.makeText(this, "Este móvil no tiene giroscopio: mueve la vista con el dedo.", Toast.LENGTH_LONG).show();
                return;
            }
            renderer.gyro = !renderer.gyro;
            renderer.pitch = 0;
            renderer.recenterRequested = renderer.gyro;
            updateButtons();
        });
        findViewById(R.id.btnCenter).setOnClickListener(v -> recenter());
        findViewById(R.id.btnViewerSettings).setOnClickListener(v -> showSettings());

        setupGestures();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (renderer.vr) setVr(false);
                else finish();
            }
        });
        updateButtons();
        updateDisplay();
        if (getIntent().getBooleanExtra(EXTRA_VR, false)) setVr(true);
    }

    private void applyLensPrefs() {
        float[] lp = Prefs.lensParams(prefs.lensPreset());
        renderer.k1 = lp[0];
        renderer.k2 = lp[1];
        renderer.screenToLens = lp[2];
        renderer.lensSep = prefs.lensSepMm() / 1000f;
        renderer.vrScale = prefs.vrScalePct() / 100f;
        renderer.strength = prefs.strengthPct() / 100f;
    }

    @SuppressWarnings("deprecation")
    private void updateDisplay() {
        renderer.displayRotation = getWindowManager().getDefaultDisplay().getRotation();
        DisplayMetrics m = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(m);
        float dpi = (m.xdpi + m.ydpi) / 2f;
        if (dpi < 150 || dpi > 900) dpi = m.densityDpi;
        renderer.dpi = dpi;
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        updateDisplay();
    }

    @Override
    protected void onResume() {
        super.onResume();
        glView.onResume();
        if (rotSensor != null) sm.registerListener(this, rotSensor, SensorManager.SENSOR_DELAY_GAME);
        dm.registerDisplayListener(displayListener, null);
        updateDisplay();
        if (renderer.vr) Ui.setImmersive(this, true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        glView.onPause();
        sm.unregisterListener(this);
        dm.unregisterDisplayListener(displayListener);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        SensorManager.getRotationMatrixFromVector(rTmp, e.values);
        renderer.setDeviceRotation(rTmp);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void setVr(boolean on) {
        renderer.vr = on;
        if (on) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            topBar.setVisibility(View.GONE);
            bottomBar.setVisibility(View.GONE);
            txtVrHint.setVisibility(View.VISIBLE);
            txtVrHint.postDelayed(() -> txtVrHint.setVisibility(View.GONE), 5000);
            Ui.setImmersive(this, true);
            if (rotSensor != null) renderer.gyro = true;
            renderer.anaglyph = false;
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
            Ui.setImmersive(this, false);
            txtVrHint.setVisibility(View.GONE);
            controlsVisible = true;
            topBar.setVisibility(View.VISIBLE);
            bottomBar.setVisibility(View.VISIBLE);
        }
        renderer.recenterRequested = true;
        updateButtons();
    }

    private void recenter() {
        renderer.pitch = 0;
        if (renderer.gyro) renderer.recenterRequested = true;
        else renderer.yaw = 0;
    }

    private void cycleStereo() {
        int s = renderer.source;
        boolean real = renderer.hasReal() || entry.hasReal();
        if (s == PanoRenderer.SRC_REAL) s = PanoRenderer.SRC_FIGURADO;
        else if (s == PanoRenderer.SRC_FIGURADO) s = PanoRenderer.SRC_PLANO;
        else s = real ? PanoRenderer.SRC_REAL : PanoRenderer.SRC_FIGURADO;
        renderer.source = s;
        updateButtons();
        String msg = s == PanoRenderer.SRC_REAL ? "3D real: tomado del paralaje del barrido"
                : (s == PanoRenderer.SRC_FIGURADO ? "3D figurado: calculado con el mapa de profundidad" : "Sin 3D (las dos imágenes iguales)");
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void updateButtons() {
        int s = renderer.source;
        btnStereo.setText(s == PanoRenderer.SRC_REAL ? "3D real" : (s == PanoRenderer.SRC_FIGURADO ? "3D figurado" : "2D"));
        btnAnaglyph.setText(renderer.anaglyph ? "Anaglifo: sí" : "Anaglifo: no");
        btnGyro.setText(renderer.gyro ? "Giroscopio: sí" : "Giroscopio: no");
    }

    private void setupGestures() {
        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (renderer.vr) {
                    recenter();
                    Toast.makeText(ViewerActivity.this, "Vista centrada", Toast.LENGTH_SHORT).show();
                } else {
                    controlsVisible = !controlsVisible;
                    topBar.setVisibility(controlsVisible ? View.VISIBLE : View.GONE);
                    bottomBar.setVisibility(controlsVisible ? View.VISIBLE : View.GONE);
                }
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (renderer.vr) return false;
                float h = Math.max(1, glView.getHeight());
                float radPerPx = (float) (2 * Math.tan(Math.toRadians(renderer.fovY) / 2) / h);
                renderer.yaw -= dx * radPerPx;
                if (!renderer.gyro) {
                    float p = renderer.pitch - dy * radPerPx;
                    renderer.pitch = (float) Math.max(-Math.PI / 2, Math.min(Math.PI / 2, p));
                }
                return true;
            }
        });
        scaler = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                if (renderer.vr) return false;
                float f = renderer.fovY / d.getScaleFactor();
                renderer.fovY = Math.max(30f, Math.min(110f, f));
                return true;
            }
        });
        glView.setOnTouchListener((v, ev) -> {
            scaler.onTouchEvent(ev);
            if (!scaler.isInProgress()) gestures.onTouchEvent(ev);
            return true;
        });
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(glView, 20);
        box.setPadding(pad, pad / 2, pad, 0);

        TextView tLens = new TextView(this);
        tLens.setText("Tipo de gafas (corrección de la lente)");
        box.addView(tLens);
        RadioGroup rg = new RadioGroup(this);
        for (int i = 0; i < Prefs.LENS_NAMES.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(1000 + i);
            rb.setText(Prefs.LENS_NAMES[i]);
            rg.addView(rb);
        }
        rg.check(1000 + prefs.lensPreset());
        rg.setOnCheckedChangeListener((g, id) -> {
            prefs.setLensPreset(id - 1000);
            applyLensPrefs();
        });
        box.addView(rg);

        addSlider(box, "Separación entre lentes", 55, 75, prefs.lensSepMm(), " mm", v -> {
            prefs.setLensSepMm(v);
            applyLensPrefs();
        });
        addSlider(box, "Tamaño de la imagen en las gafas", 60, 140, prefs.vrScalePct(), " %", v -> {
            prefs.setVrScalePct(v);
            applyLensPrefs();
        });
        addSlider(box, "Intensidad del 3D figurado", 0, 300, prefs.strengthPct(), " %", v -> {
            prefs.setStrengthPct(v);
            applyLensPrefs();
        });
        new AlertDialog.Builder(this)
                .setTitle("Ajustes del visor")
                .setView(box)
                .setPositiveButton("Hecho", null)
                .show();
    }

    interface IntSink {
        void set(int v);
    }

    private void addSlider(LinearLayout box, String label, int min, int max, int value, String unit, IntSink sink) {
        TextView t = new TextView(this);
        t.setPadding(0, Ui.dp(glView, 14), 0, 0);
        box.addView(t);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, Math.min(max - min, value - min)));
        t.setText(label + ": " + value + unit);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                t.setText(label + ": " + (p + min) + unit);
                if (fromUser) sink.set(p + min);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        box.addView(sb);
    }
}
