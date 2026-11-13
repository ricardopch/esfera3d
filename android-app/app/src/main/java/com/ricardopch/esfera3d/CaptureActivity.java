package com.ricardopch.esfera3d;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
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
import androidx.camera.camera2.interop.Camera2Interop;
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
import com.ricardopch.esfera3d.core.BufferCropSampler;
import com.ricardopch.esfera3d.core.Mat3;
import com.ricardopch.esfera3d.core.MotionTracker;
import com.ricardopch.esfera3d.core.SphereBuilder;
import com.ricardopch.esfera3d.core.SweepSet;
import com.ricardopch.esfera3d.core.SweepStitcher;

import org.json.JSONObject;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Barrido guiado con la cámara. Durante el barrido solo se guardan los fotogramas (con su
 * orientación) y se sigue el movimiento; la foto 3D 360 se cose al terminar, con todos los
 * datos delante (ver {@link SphereBuilder}).
 */
public class CaptureActivity extends ComponentActivity implements GuideOverlayView.Source {
    private static final String TAG = "Esfera3D";
    private static final int REQ_CAMERA = 1;
    private static final double EYE_RADIUS = 0.032; // media distancia entre ojos (m)
    private static final double CAMERA_HEIGHT = 1.4; // altura aproximada del móvil sobre el suelo (m)
    private static final int STATE_PREVIEW = 0, STATE_CAPTURING = 1, STATE_PROCESSING = 2;
    private static final int PW = 512, PH = 256;
    /** Avance mínimo de rumbo (grados) entre fotogramas guardados. */
    private static final double STORE_STEP_DEG = 0.3;

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
    private final float[] rotC = new float[9];
    /** Exposición y lectura (obturador) de cada fotograma, desde los resultados de captura. */
    private final FrameTiming timing = new FrameTiming();
    private volatile CameraModel cam;
    private volatile int state = STATE_PREVIEW;
    /** Panorámica pequeña y rápida, solo para la miniatura de la guía. */
    private volatile SweepStitcher stitcher;
    private volatile SweepGuide guide;
    private boolean fullMode;
    private double ringPitch = 50;
    private boolean processingStarted;
    private String captureId;
    private volatile DiagnosticRecorder diag;
    private boolean diagHeader;
    private int diagIndex;

    // Fotogramas guardados para el cosido
    private volatile SweepSet sweep;
    private volatile FrameWriter writer;
    private MotionTracker motion;
    private int thumbStep = 3;
    private float thumbF;
    private byte[] full;
    private int bufW, bufH, bufRot, cropX, cropY, cropW, cropH;
    private double lastStoredHeading;
    private float lastStoredRing = Float.NaN;
    private int dropped;
    private int panoWidth = 4096;

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
            SweepSet set = sweep;
            if (set == null) return;
            if (set.n < 80) {
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
            abandonCapture();
            Toast.makeText(this, "Barrido cancelado", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        analysisExec.shutdown();
    }

    private void abandonCapture() {
        state = STATE_PREVIEW;
        stitcher = null;
        guide = null;
        sweep = null;
        tracker.setRecorder(null);
        diag = null;
        final FrameWriter w = writer;
        writer = null;
        if (w != null) new Thread(() -> {
            w.finish();
            w.deleteAll();
        }).start();
    }

    // ------------------------------------------------------------------ cámara

    private void bindCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                provider = future.get();
                // 960 × 720 da ya la resolución de una panorámica de 4096 de ancho
                ResolutionSelector analysisRes = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(new ResolutionStrategy(new Size(960, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build();
                ResolutionSelector previewRes = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .build();
                Preview.Builder pb = new Preview.Builder().setResolutionSelector(previewRes);
                ImageAnalysis.Builder ab = new ImageAnalysis.Builder()
                        .setResolutionSelector(analysisRes)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST);
                // Sin estabilizador (óptico ni electrónico): mueve la imagen sin que el giroscopio
                // lo sepa. Si el móvil no deja quitarlo, el cosido lo compensa midiendo la imagen.
                Camera2Interop.Extender<Preview> pe = new Camera2Interop.Extender<>(pb);
                pe.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF);
                pe.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
                Camera2Interop.Extender<ImageAnalysis> ae = new Camera2Interop.Extender<>(ab);
                ae.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF);
                ae.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
                ae.setSessionCaptureCallback(new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                        Long ts = result.get(CaptureResult.SENSOR_TIMESTAMP);
                        Long exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                        Long skew = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW);
                        if (ts != null) timing.put(ts, exp != null ? exp : -1, skew != null ? skew : -1);
                    }
                });
                Preview preview = pb.build();
                ImageAnalysis analysis = ab.build();
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

    /**
     * Distancia focal aproximada, en píxeles del fotograma de análisis (W × H), según los datos
     * del fabricante. Suele fallar un 5–15 % (el vídeo recorta el sensor): la focal real se
     * calcula después a partir del propio barrido.
     */
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
        if (cam == null || camera == null || motion == null) {
            Toast.makeText(this, "Espera a que arranque la cámara…", Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.setFullSphere(full);
        fullMode = full;
        panoWidth = prefs.panoWidth();
        if (Runtime.getRuntime().maxMemory() < 400L * 1024 * 1024) panoWidth = Math.min(panoWidth, 2048);
        double alpha = SweepStitcher.alphaFor(EYE_RADIUS, prefs.armCm() / 100.0);
        SweepStitcher st = new SweepStitcher(PW, PH, alpha);
        st.setAlignment(false);
        // Vueltas de arriba y abajo con bastante solape con la del horizonte (el campo de visión
        // real suele ser un 10 % menor que el declarado)
        ringPitch = Math.max(40, Math.min(52, cam.vfovDeg() / 1.1 - 14));
        guide = new SweepGuide(full, ringPitch);
        captureId = CaptureStore.newId();
        writer = new FrameWriter(new File(getCacheDir(), "barrido_" + captureId));
        SweepSet set = new SweepSet();
        set.cam = cam;
        lastStoredRing = Float.NaN;
        dropped = 0;
        stitcher = st;
        lockExposure();
        panelIntro.setVisibility(View.GONE);
        panelCapture.setVisibility(View.VISIBLE);
        btnSkip.setVisibility(full ? View.VISIBLE : View.GONE);
        panelCapture.post(() -> overlay.setBottomReserve(panelCapture.getHeight()));
        if (prefs.diagnostics()) {
            DiagnosticRecorder d = new DiagnosticRecorder(this, captureId);
            d.info("version=1.3 dispositivo=" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " (Android " + android.os.Build.VERSION.SDK_INT + ")");
            d.info(String.format(Locale.ROOT, "focal_mm=%.3f fisico_largo_mm=%.3f pixeles_largo=%.0f activo=%.0fx%.0f",
                    focalMm, physLong, pixLong, activeLong, activeShort));
            d.info(String.format(Locale.ROOT, "camara_enderezada=%dx%d fx=%.2f fy=%.2f campoH=%.2f campoV=%.2f",
                    cam.width, cam.height, cam.fx, cam.fy, cam.hfovDeg(), cam.vfovDeg()));
            d.info(String.format(Locale.ROOT, "alfa=%.3f radio_cm=%d ancho=%d esfera=%b vuelta_incl=%.2f",
                    Math.toDegrees(alpha), prefs.armCm(), panoWidth, full, ringPitch));
            tracker.setRecorder(d::sensorSample);
            diag = d;
        }
        sweep = set;
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
            int w = img.getWidth(), h = img.getHeight();
            if (cam == null) {
                float f = focalPx(w, h);
                boolean swap = rotDeg % 180 != 0;
                int uw = swap ? h : w, uh = swap ? w : h;
                thumbStep = Math.max(2, Math.round(uw / 360f));
                thumbF = f / thumbStep;
                motion = new MotionTracker(uw / thumbStep, uh / thumbStep, thumbF);
                bufW = w;
                bufH = h;
                bufRot = rotDeg;
                // Franja central de columnas que hace falta para coser (centro y los dos ojos)
                int half = Math.round(uw * 0.28f);
                int[] c = BufferCropSampler.cropForColumns(w, h, rotDeg, uw / 2 - half, uw / 2 + half);
                cropX = c[0];
                cropY = c[1];
                cropW = c[2];
                cropH = c[3];
                cam = new CameraModel(uw, uh, f, f);
                Log.i(TAG, String.format(Locale.ROOT, "fotograma %dx%d rot %d, campo declarado %.1f x %.1f grados",
                        w, h, rotDeg, cam.hfovDeg(), cam.vfovDeg()));
            }
            if (state != STATE_CAPTURING || w != bufW || h != bufH || rotDeg != bufRot) return;
            SweepStitcher st = stitcher;
            SweepGuide g = guide;
            SweepSet set = sweep;
            FrameWriter fw = writer;
            if (st == null || g == null || set == null || fw == null || g.isDone()) return;
            CameraModel c = cam;

            // Copia del fotograma (el búfer de la cámara es lento de leer píxel a píxel)
            ImageProxy.PlaneProxy plane = img.getPlanes()[0];
            int stride = plane.getRowStride();
            ByteBuffer src = plane.getBuffer();
            src.rewind();
            int need = stride * (h - 1) + w * 4;
            if (full == null || full.length < need) full = new byte[need];
            src.get(full, 0, Math.min(need, src.remaining()));
            sampler.set(ByteBuffer.wrap(full), w, h, stride, rotDeg);

            // Orientación del móvil en el centro de la exposición del fotograma
            long camTs = img.getImageInfo().getTimestamp();
            long base = toSensorTime(camTs);
            long[] er = timing.get(camTs);
            long tFrame;
            if (base == Long.MAX_VALUE) {
                if (!tracker.latest(rotC)) return;
                tFrame = camTs;
            } else {
                tFrame = base + er[0] / 2 + er[1] / 2;
                if (!tracker.rotationAt(tFrame, rotC)) return;
            }
            SweepGuide.Ring ringNow = g.currentRing();
            if (ringNow == null) return;
            float nominal = (float) ringNow.pitchDeg;
            double pitch = Math.toDegrees(Mat3.cameraPitch(rotC));
            double heading = Math.toDegrees(Mat3.cameraHeading(rotC));
            boolean steady = Math.abs(pitch - nominal) <= 7;

            // Seguimiento del movimiento con la imagen (y calibración en la vuelta del horizonte)
            byte[] gray = sampler.grayThumbnail(thumbStep);
            float[] acc = motion.update(gray, rotC, tFrame, steady && Math.abs(nominal) < 1);

            // Miniatura y guía
            st.addFrame(sampler, c, rotC, rotC, rotC, nominal);
            boolean ringDone = g.update(st.headingDeg(), st.pitchDeg(), st.rollDeg(), SystemClock.elapsedRealtimeNanos());
            if (ringDone) runOnUiThread(this::vibrate);

            // Guardar el fotograma para el cosido
            boolean stored = false;
            if (Math.abs(pitch - nominal) <= 12
                    && (lastStoredRing != nominal || Math.abs(SweepGuide.wrap180(heading - lastStoredHeading)) >= STORE_STEP_DEG)) {
                if (fw.canAccept()) {
                    byte[] crop = fw.obtain(cropW * cropH * 4);
                    for (int y = 0; y < cropH; y++) {
                        System.arraycopy(full, (cropY + y) * stride + cropX * 4, crop, y * cropW * 4, cropW * 4);
                    }
                    int idx = set.add(tFrame, rotC, nominal, acc);
                    fw.submit(idx, crop, cropW, cropH);
                    lastStoredRing = nominal;
                    lastStoredHeading = heading;
                    stored = true;
                } else {
                    dropped++;
                }
            }

            DiagnosticRecorder d = diag;
            if (d != null) {
                if (!diagHeader) {
                    diagHeader = true;
                    d.info("bufer=" + w + "x" + h + " paso_fila=" + stride + " rotacion=" + rotDeg
                            + " recorte=" + cropX + "," + cropY + "," + cropW + "x" + cropH + " miniatura_paso=" + thumbStep);
                }
                StringBuilder sb = new StringBuilder(400);
                sb.append(diagIndex).append(',').append(camTs).append(',').append(base == Long.MAX_VALUE ? -1 : base).append(',')
                        .append(er[0]).append(',').append(er[1]).append(',').append(rotDeg).append(',').append(nominal).append(',')
                        .append(String.format(Locale.ROOT, "%.3f,%.3f,%.3f,%.4f,%.4f,%d,", st.headingDeg(), st.pitchDeg(), st.rollDeg(),
                                Math.toDegrees(acc[1]), Math.toDegrees(acc[0]), stored ? set.n : -set.n));
                boolean keep = (diagIndex % 2) == 0;
                sb.append(keep ? 1 : 0);
                for (int rep = 0; rep < 3; rep++) for (float v : rotC) sb.append(',').append(v);
                int tw = c.width / 2, th = c.height / 2;
                d.frame(sb.toString(), diagIndex, keep ? sampler.uprightThumbnail(tw, th) : null, tw, th);
                diagIndex++;
            }

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
                    abandonCapture();
                    finish();
                })
                .setNegativeButton("Seguir", null)
                .show();
    }

    // ------------------------------------------------------------------ procesado

    private void startProcessing() {
        if (processingStarted || sweep == null) return;
        processingStarted = true;
        state = STATE_PROCESSING;
        vibrate();
        panelCapture.setVisibility(View.GONE);
        panelProcessing.setVisibility(View.VISIBLE);
        overlay.setVisibility(View.GONE);
        if (provider != null) provider.unbindAll();
        tracker.setRecorder(null);
        stitcher = null;
        final double arm = prefs.armCm() / 100.0;
        new Thread(() -> process(arm), "procesado").start();
    }

    private void step(String msg, int pct) {
        runOnUiThread(() -> {
            txtProcessing.setText(msg);
            progress.setProgress(pct);
        });
    }

    private void process(double arm) {
        final SweepSet set = sweep;
        final FrameWriter fw = writer;
        final DiagnosticRecorder d = diag;
        diag = null;
        StringBuilder log = new StringBuilder();
        try {
            step("Guardando los últimos fotogramas…", 2);
            try {
                analysisExec.submit(() -> { }).get(); // que termine el fotograma en curso
            } catch (Exception ignored) {
            }
            fw.finish();
            // Focal real, medida en el propio barrido
            double k = motion.focalFactor(thumbF);
            CameraModel real = cam.scaled((float) k);
            set.cam = real;
            log.append(String.format(Locale.ROOT, "fotogramas guardados %d (descartados %d, fallidos %d); seguimiento %d/%d\n",
                    set.n, dropped, fw.failed(), motion.measured(), motion.total()));
            log.append(String.format(Locale.ROOT, "focal x%.3f (%d muestras): campo real %.1f x %.1f grados\n",
                    k, motion.focalSamples(), real.hfovDeg(), real.vfovDeg()));

            final int[] dims = new int[2];
            SweepSet.Store store = i -> {
                int[] px = fw.read(i, dims);
                return px == null ? null : new BufferCropSampler(px, dims[0], dims[1], cropX, cropY, bufW, bufH, bufRot);
            };
            SphereBuilder.Options o = new SphereBuilder.Options();
            o.width = panoWidth;
            o.alphaRad = SweepStitcher.alphaFor(EYE_RADIUS, arm);
            SphereBuilder.Result r;
            try {
                r = SphereBuilder.build(set, store, o, f -> step("Cosiendo la foto…", 4 + Math.round(f * 66)));
            } catch (OutOfMemoryError oom) {
                if (o.width <= 2048) throw oom;
                log.append("sin memoria a 4096: se repite a 2048\n");
                o.width = 2048;
                r = SphereBuilder.build(set, store, o, f -> step("Cosiendo la foto…", 4 + Math.round(f * 66)));
            }
            log.append(r.log);
            log.append(String.format(Locale.ROOT, "discrepancia de mezcla %.2f; cobertura %.3f\n", r.blendError, r.mono.coverage()));
            int w = r.mono.w, h = r.mono.h;

            step("Calculando la profundidad de la escena…", 72);
            float[] cols = PanoPost.ratioToColumnDistance(motion.parallaxRatio(k), r.refHeadingRad, arm, 360);
            DepthEstimator.Result depth = PanoPost.finish(r.left, r.right, r.mono, cols, arm, EYE_RADIUS, CAMERA_HEIGHT,
                    f -> step("Calculando la profundidad de la escena…", 72 + Math.round(f * 14)));
            log.append("profundidad: ").append(depth.describe()).append('\n');
            Log.i(TAG, log.toString());

            step("Guardando…", 88);
            JSONObject info = new JSONObject();
            info.put("tipo", "barrido");
            info.put("version", "1.3");
            info.put("modo", fullMode ? "esfera" : "horizonte");
            info.put("fotogramas", r.framesUsed);
            info.put("radioCm", Math.round(arm * 100));
            info.put("alfaGrados", Math.toDegrees(o.alphaRad));
            info.put("factorFocal", k);
            info.put("campoH", real.hfovDeg());
            info.put("campoV", real.vfovDeg());
            info.put("registro", log.toString());
            String id = captureId != null ? captureId : CaptureStore.newId();
            final CaptureStore.Entry e = CaptureStore.save(this, id, r.left.color, r.right.color, r.mono.color, w, h, depth, info);
            sweep = null;
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
            log.append("ERROR: sin memoria\n");
            runOnUiThread(() -> showError("No hay memoria suficiente para procesar la foto. En Ajustes, elige la calidad «ligera» y vuelve a intentarlo."));
        } catch (Throwable t) {
            Log.e(TAG, "procesado", t);
            log.append("ERROR: ").append(t).append('\n');
            runOnUiThread(() -> showError("Error al procesar: " + t.getMessage()));
        } finally {
            sweep = null;
            if (fw != null) fw.deleteAll();
            if (d != null) {
                for (String line : log.toString().split("\n")) d.info("# " + line);
                String where = d.finish(getApplicationContext());
                runOnUiThread(() -> Toast.makeText(getApplicationContext(),
                        where != null ? "Diagnóstico guardado en " + where : "No se pudo guardar el diagnóstico",
                        Toast.LENGTH_LONG).show());
            }
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
        SweepStitcher st = stitcher;
        return state == STATE_CAPTURING && st != null && st.framesUsed() > 0;
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
