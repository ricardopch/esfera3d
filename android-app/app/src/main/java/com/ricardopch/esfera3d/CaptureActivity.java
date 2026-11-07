package com.ricardopch.esfera3d;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.util.Size;
import android.util.SizeF;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.CaptureRequestOptions;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.ricardopch.esfera3d.core.CameraModel;
import com.ricardopch.esfera3d.core.DepthEstimator;
import com.ricardopch.esfera3d.core.PanoPost;
import com.ricardopch.esfera3d.core.RgbaFrameSampler;
import com.ricardopch.esfera3d.core.SweepGuide;
import com.ricardopch.esfera3d.core.SweepStitcher;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Barrido guiado con la cámara y construcción de la foto 3D 360. */
public class CaptureActivity extends ComponentActivity implements GuideOverlayView.Source {
    private static final String TAG = "Esfera3D";
    private static final int REQ_CAMERA = 1;
    private static final double EYE_RADIUS = 0.032; // media distancia entre ojos (m)
    private static final double CAMERA_HEIGHT = 1.5; // altura aproximada del móvil sobre el suelo (m)
    private static final int STATE_PREVIEW = 0, STATE_CAPTURING = 1, STATE_PROCESSING = 2;
    private static final int PW = 512, PH = 256;

    private PreviewView previewView;
    private GuideOverlayView overlay;
    private View panelIntro, panelCapture, panelProcessing;
    private TextView txtProcessing;
    private ProgressBar progress;
    private Button btnSkip;

    private final ExecutorService analysisExec = Executors.newSingleThreadExecutor();
    private ProcessCameraProvider provider;
    private Camera camera;
    private OrientationTracker tracker;
    private Prefs prefs;

    private final RgbaFrameSampler sampler = new RgbaFrameSampler();
    private final float[] rot = new float[9];
    private volatile CameraModel cam;
    private volatile int state = STATE_PREVIEW;
    private volatile SweepStitcher stitcher;
    private volatile SweepGuide guide;
    private boolean fullMode;
    private double ringPitch = 55;
    private boolean processingStarted;

    // Datos ópticos del sensor (para el campo de visión)
    private float focalMm = -1, physLong = -1, pixLong = -1, activeLong = -1, activeShort = -1;

    private final int[] previewPx = new int[PW * PH];
    private Bitmap previewBmp;
    private long lastPreviewMs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_capture);
        prefs = new Prefs(this);
        tracker = new OrientationTracker(this);
        previewView = findViewById(R.id.previewView);
        previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        overlay = findViewById(R.id.overlay);
        overlay.setSource(this);
        panelIntro = findViewById(R.id.panelIntro);
        panelCapture = findViewById(R.id.panelCapture);
        panelProcessing = findViewById(R.id.panelProcessing);
        txtProcessing = findViewById(R.id.txtProcessing);
        progress = findViewById(R.id.progress);
        btnSkip = findViewById(R.id.btnSkip);
        previewBmp = Bitmap.createBitmap(PW, PH, Bitmap.Config.ARGB_8888);

        Ui.padForSystemBars(findViewById(R.id.uiLayer), (l, t, r, bo) -> overlay.setInsets(t, bo));

        RadioButton rFull = findViewById(R.id.radioFull);
        RadioButton rQuick = findViewById(R.id.radioQuick);
        if (prefs.fullSphere()) rFull.setChecked(true);
        else rQuick.setChecked(true);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnStart).setOnClickListener(v -> startCapture(rFull.isChecked()));
        findViewById(R.id.btnCancel).setOnClickListener(v -> confirmCancel());
        btnSkip.setOnClickListener(v -> {
            SweepGuide g = guide;
            if (g != null) {
                g.skipRing();
                if (g.isDone()) startProcessing();
            }
        });
        findViewById(R.id.btnFinish).setOnClickListener(v -> {
            SweepStitcher st = stitcher;
            if (st == null) return;
            if (st.mono.coverage() < 0.08) {
                Toast.makeText(this, "Aún hay muy poca imagen: sigue girando un poco más.", Toast.LENGTH_LONG).show();
                return;
            }
            startProcessing();
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (state == STATE_CAPTURING) confirmCancel();
                else if (state == STATE_PREVIEW) finish();
            }
        });

        if (!tracker.available()) {
            new AlertDialog.Builder(this)
                    .setTitle("Sin sensor de giro")
                    .setMessage("Este móvil no tiene sensor de rotación (giroscopio), y el barrido guiado lo necesita para saber hacia dónde apunta la cámara.")
                    .setPositiveButton("Cerrar", (d, w) -> finish())
                    .setCancelable(false)
                    .show();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            bindCamera();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_CAMERA) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) bindCamera();
            else {
                Toast.makeText(this, "Sin permiso de cámara no se puede hacer la foto.", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        tracker.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        tracker.stop();
        if (state == STATE_CAPTURING) {
            // Si la app pasa a segundo plano a mitad de barrido, la orientación deja de ser fiable.
            state = STATE_PREVIEW;
            stitcher = null;
            guide = null;
            Toast.makeText(this, "Barrido cancelado", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        analysisExec.shutdown();
    }

    // ------------------------------------------------------------------ cámara

    private void bindCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                provider = future.get();
                ResolutionSelector analysisRes = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(new ResolutionStrategy(new Size(1280, 960),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build();
                ResolutionSelector previewRes = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .build();
                Preview preview = new Preview.Builder().setResolutionSelector(previewRes).build();
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setResolutionSelector(analysisRes)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(analysisExec, this::analyze);
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                provider.unbindAll();
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
                readCharacteristics();
            } catch (Exception e) {
                Log.e(TAG, "cámara", e);
                showError("No se pudo abrir la cámara: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void readCharacteristics() {
        try {
            String id = Camera2CameraInfo.from(camera.getCameraInfo()).getCameraId();
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            float[] fl = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            SizeF phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            Size pix = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
            Rect act = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (fl != null && fl.length > 0 && phys != null && pix != null && act != null) {
                focalMm = fl[0];
                physLong = Math.max(phys.getWidth(), phys.getHeight());
                pixLong = Math.max(pix.getWidth(), pix.getHeight());
                activeLong = Math.max(act.width(), act.height());
                activeShort = Math.min(act.width(), act.height());
            }
        } catch (Exception e) {
            Log.w(TAG, "sin datos ópticos", e);
        }
    }

    /** Distancia focal en píxeles del fotograma de análisis (W × H). */
    private float focalPx(int w, int h) {
        int longSide = Math.max(w, h), shortSide = Math.min(w, h);
        if (focalMm > 0 && physLong > 0 && pixLong > 0 && activeLong > 0 && activeShort > 0) {
            float bufAspect = longSide / (float) shortSide;
            float usedLong = (activeLong / activeShort > bufAspect) ? activeShort * bufAspect : activeLong;
            float pitchMm = physLong / pixLong;
            float f = focalMm / (pitchMm * usedLong / longSide);
            double fov = Math.toDegrees(2 * Math.atan(longSide / 2.0 / f));
            if (fov > 35 && fov < 110) return f;
        }
        return (float) (longSide / 2.0 / Math.tan(Math.toRadians(33)));
    }

    private void lockExposure() {
        try {
            Camera2CameraControl.from(camera.getCameraControl()).setCaptureRequestOptions(
                    new CaptureRequestOptions.Builder()
                            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
                            .build());
        } catch (Exception e) {
            Log.w(TAG, "no se pudo bloquear la exposición", e);
        }
    }

    // ------------------------------------------------------------------ barrido

    private void startCapture(boolean full) {
        if (cam == null || camera == null) {
            Toast.makeText(this, "Espera a que arranque la cámara…", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.setFullSphere(full);
        fullMode = full;
        int w = prefs.panoWidth();
        if (Runtime.getRuntime().maxMemory() < 380L * 1024 * 1024) w = Math.min(w, 2048);
        double alpha = SweepStitcher.alphaFor(EYE_RADIUS, prefs.armCm() / 100.0);
        SweepStitcher st;
        try {
            st = new SweepStitcher(w, w / 2, alpha);
        } catch (OutOfMemoryError oom) {
            w = 2048;
            st = new SweepStitcher(w, w / 2, alpha);
        }
        double vfov = cam.vfovDeg();
        ringPitch = Math.max(45, Math.min(vfov - 6, 88 - vfov / 2));
        guide = new SweepGuide(full, ringPitch);
        stitcher = st;
        lockExposure();
        panelIntro.setVisibility(View.GONE);
        panelCapture.setVisibility(View.VISIBLE);
        btnSkip.setVisibility(full ? View.VISIBLE : View.GONE);
        panelCapture.post(() -> overlay.setBottomReserve(panelCapture.getHeight()));
        state = STATE_CAPTURING;
    }

    private long toSensorTime(long camTs) {
        long sensorNow = tracker.latestTimestamp();
        long limit = 400_000_000L;
        if (Math.abs(camTs - sensorNow) <= limit) return camTs;
        long off = SystemClock.elapsedRealtimeNanos() - System.nanoTime();
        if (Math.abs(camTs + off - sensorNow) <= limit) return camTs + off;
        return Long.MAX_VALUE; // bases de tiempo desconocidas: última orientación
    }

    private void analyze(ImageProxy img) {
        try {
            int rotDeg = img.getImageInfo().getRotationDegrees();
            if (cam == null) {
                float f = focalPx(img.getWidth(), img.getHeight());
                boolean swap = rotDeg % 180 != 0;
                int uw = swap ? img.getHeight() : img.getWidth(), uh = swap ? img.getWidth() : img.getHeight();
                cam = new CameraModel(uw, uh, f, f);
                Log.i(TAG, String.format(Locale.ROOT, "fotograma %dx%d rot %d, campo %.1f x %.1f grados",
                        img.getWidth(), img.getHeight(), rotDeg, cam.hfovDeg(), cam.vfovDeg()));
            }
            if (state != STATE_CAPTURING) return;
            SweepStitcher st = stitcher;
            SweepGuide g = guide;
            if (st == null || g == null || g.isDone()) return;
            ImageProxy.PlaneProxy plane = img.getPlanes()[0];
            sampler.set(plane.getBuffer(), img.getWidth(), img.getHeight(), plane.getRowStride(), rotDeg);
            long t = toSensorTime(img.getImageInfo().getTimestamp());
            if (!tracker.rotationAt(t, rot)) return;
            st.addFrame(sampler, cam, rot);
            boolean ringDone = g.update(st.headingDeg(), st.pitchDeg(), st.rollDeg(), SystemClock.elapsedRealtimeNanos());
            if (ringDone) runOnUiThread(this::vibrate);
            long now = SystemClock.uptimeMillis();
            if (now - lastPreviewMs > 350) {
                lastPreviewMs = now;
                synchronized (previewPx) {
                    PanoPost.preview(st.mono, previewPx, PW, PH);
                }
                runOnUiThread(() -> {
                    synchronized (previewPx) {
                        previewBmp.setPixels(previewPx, 0, PW, 0, 0, PW, PH);
                    }
                });
            }
            if (g.isDone()) runOnUiThread(this::startProcessing);
        } catch (Throwable e) {
            Log.e(TAG, "análisis", e);
        } finally {
            img.close();
        }
    }

    private void vibrate() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) v.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) {
        }
    }

    private void confirmCancel() {
        new AlertDialog.Builder(this)
                .setMessage("¿Cancelar el barrido? Se perderá lo capturado.")
                .setPositiveButton("Cancelar barrido", (d, w) -> {
                    state = STATE_PREVIEW;
                    stitcher = null;
                    guide = null;
                    finish();
                })
                .setNegativeButton("Seguir", null)
                .show();
    }

    // ------------------------------------------------------------------ procesado

    private void startProcessing() {
        if (processingStarted) return;
        processingStarted = true;
        state = STATE_PROCESSING;
        vibrate();
        panelCapture.setVisibility(View.GONE);
        panelProcessing.setVisibility(View.VISIBLE);
        overlay.setVisibility(View.GONE);
        if (provider != null) provider.unbindAll();
        final SweepStitcher st = stitcher;
        final double arm = prefs.armCm() / 100.0;
        new Thread(() -> process(st, arm), "procesado").start();
    }

    private void step(String msg, int pct) {
        runOnUiThread(() -> {
            txtProcessing.setText(msg);
            progress.setProgress(pct);
        });
    }

    private void process(SweepStitcher st, double arm) {
        try {
            int w = st.W, h = st.H;
            step("Rellenando huecos…", 4);
            PanoPost.fillHoles(st.left);
            step("Rellenando huecos…", 10);
            PanoPost.fillHoles(st.right);
            step("Rellenando huecos…", 16);
            PanoPost.fillHoles(st.mono);
            step("Calculando la profundidad de la escena…", 22);
            DepthEstimator.Result depth = DepthEstimator.estimate(st.left.color, st.right.color, st.mono.color,
                    w, h, EYE_RADIUS, CAMERA_HEIGHT, f -> step("Calculando la profundidad de la escena…", 22 + Math.round(f * 40)));
            Log.i(TAG, "profundidad: " + depth.describe());
            if (depth.measured) {
                step("Corrigiendo la perspectiva…", 64);
                PanoPost.correctVerticalParallax(new int[][]{st.left.color, st.right.color, st.mono.color},
                        w, h, depth.q, depth.w, depth.h, arm);
            }
            PanoPost.poleBlend(st.left.color, st.right.color, st.mono.color, w, h, 60, 75);
            step("Guardando…", 75);
            JSONObject info = new JSONObject();
            info.put("tipo", "barrido");
            info.put("modo", fullMode ? "esfera" : "horizonte");
            info.put("fotogramas", st.framesUsed());
            info.put("brazoCm", Math.round(arm * 100));
            info.put("alfaGrados", Math.toDegrees(st.alpha()));
            CameraModel c = cam;
            if (c != null) {
                info.put("campoH", c.hfovDeg());
                info.put("campoV", c.vfovDeg());
            }
            final CaptureStore.Entry e = CaptureStore.save(this, CaptureStore.newId(), st.left.color, st.right.color,
                    st.mono.color, w, h, depth, info);
            stitcher = null;
            step("¡Listo!", 100);
            runOnUiThread(() -> {
                if (e == null) {
                    showError("No se pudo guardar la foto.");
                    return;
                }
                Intent it = new Intent(this, ViewerActivity.class);
                it.putExtra(ViewerActivity.EXTRA_ID, e.id);
                startActivity(it);
                finish();
            });
        } catch (OutOfMemoryError oom) {
            stitcher = null;
            runOnUiThread(() -> showError("No hay memoria suficiente para procesar la foto. En Ajustes, elige la calidad «ligera» y vuelve a intentarlo."));
        } catch (Throwable t) {
            Log.e(TAG, "procesado", t);
            stitcher = null;
            runOnUiThread(() -> showError("Error al procesar: " + t.getMessage()));
        }
    }

    private void showError(String msg) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("Esfera 3D")
                .setMessage(msg)
                .setPositiveButton("Cerrar", (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    // ------------------------------------------------------------------ fuente de la guía

    @Override
    public CameraModel camera() {
        return cam;
    }

    @Override
    public boolean latestRotation(float[] out) {
        return tracker.latest(out);
    }

    @Override
    public SweepGuide guide() {
        return guide;
    }

    @Override
    public boolean started() {
        return state == STATE_CAPTURING && stitcher != null && stitcher.framesUsed() > 0;
    }

    @Override
    public double referenceHeading() {
        SweepStitcher st = stitcher;
        return st != null ? st.referenceHeading() : 0;
    }

    @Override
    public double introRingPitch() {
        return 0;
    }

    @Override
    public Bitmap previewBitmap() {
        return previewBmp;
    }

    @Override
    public boolean capturing() {
        return state == STATE_CAPTURING;
    }
}
