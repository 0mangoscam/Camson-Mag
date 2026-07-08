package com.camsonmag.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.drawable.GradientDrawable;

import com.google.ar.core.Anchor;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Point;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MainActivity extends Activity implements SensorEventListener {
    private GLSurfaceView surfaceView;
    private FrameLayout rootLayout;
    private View photoOverlay;
    private PhotoSprayView activePhotoView;
    private boolean photoModeActive = false;
    private SprayRenderer renderer;
    private Session session;
    private boolean installRequested;
    private boolean shouldConfigureSession = false;
    private boolean hasCameraPermission = false;

    private TextView statusText;
    private TextView modeBadge;
    private TextView crossView;
    private ProgressBar paintMeter;
    private Button colorButton;
    private Button photoButton;
    private Button sprayButton;
    private Button styleButton;
    private TextView mainRatioChip;
    private TextView mainFlashChip;
    private TextView photoRatioChip;
    private TextView photoFlashChip;
    private int ratioModeIndex = 0;
    private int flashModeIndex = 0;
    private boolean torchOn = false;
    private boolean directPhotoWaiting = false;
    private int directPhotoAttempts = 0;
    private final String[] ratioLabels = new String[] { "3:4", "1:1", "9:16", "FULL" };
    private final String[] flashLabels = new String[] { "⚡A", "⚡ON", "⚡OFF" };

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private Sensor gyroscope;
    private long lastShake = 0;
    private float lastX, lastY, lastZ;
    private boolean hasLast = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        hideSystemBars();
        hasCameraPermission = Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        if (!hasCameraPermission && Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{ Manifest.permission.CAMERA }, 17);
        }

        renderer = new SprayRenderer(this);
        surfaceView = new GLSurfaceView(this);
        surfaceView.setPreserveEGLContextOnPause(true);
        surfaceView.setEGLContextClientVersion(2);
        surfaceView.setRenderer(renderer);
        surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        rootLayout = new FrameLayout(this);
        rootLayout.addView(surfaceView, new FrameLayout.LayoutParams(-1, -1));
        rootLayout.addView(makeHud(), new FrameLayout.LayoutParams(-1, -1));
        setContentView(rootLayout);

        installRequested = false;
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        }
    }

    private View makeHud() {
        FrameLayout hud = new FrameLayout(this);
        hud.setPadding(dp(12), dp(12), dp(12), dp(12));
        hud.setBackgroundColor(Color.argb(35, 0, 0, 0));

        FrameLayout topBar = new FrameLayout(this);
        topBar.setPadding(dp(18), dp(10), dp(18), dp(8));
        topBar.setBackground(topGlassBar());
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(-1, dp(88), Gravity.TOP);
        hud.addView(topBar, topLp);

        ImageView appIcon = new ImageView(this);
        int iconId = getResources().getIdentifier("ic_launcher", "mipmap", getPackageName());
        if (iconId != 0) appIcon.setImageResource(iconId);
        appIcon.setPadding(dp(3), dp(3), dp(3), dp(3));
        appIcon.setBackground(pill(Color.argb(155,10,10,12), Color.argb(95,255,255,255), dp(12)));
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(50), dp(50), Gravity.START | Gravity.CENTER_VERTICAL);
        iconLp.leftMargin = dp(6);
        topBar.addView(appIcon, iconLp);

        TextView directLabel = topText("PHOTO");
        directLabel.setTextSize(14);
        directLabel.setTypeface(Typeface.DEFAULT_BOLD);
        directLabel.setTextColor(Color.rgb(255,136,178));
        FrameLayout.LayoutParams directLp = new FrameLayout.LayoutParams(dp(98), dp(54), Gravity.START | Gravity.CENTER_VERTICAL);
        directLp.leftMargin = dp(66);
        topBar.addView(directLabel, directLp);

        mainRatioChip = topText(ratioLabels[ratioModeIndex]);
        mainRatioChip.setTextSize(16);
        mainRatioChip.setBackground(pill(Color.argb(70,0,0,0), Color.argb(170,255,255,255), dp(6)));
        mainRatioChip.setOnClickListener(v -> cycleRatioMode());
        FrameLayout.LayoutParams ratioLp = new FrameLayout.LayoutParams(dp(58), dp(44), Gravity.CENTER);
        topBar.addView(mainRatioChip, ratioLp);

        mainFlashChip = topText(flashLabels[flashModeIndex]);
        mainFlashChip.setTextSize(24);
        mainFlashChip.setOnClickListener(v -> cycleFlashMode());
        FrameLayout.LayoutParams flashLp = new FrameLayout.LayoutParams(dp(74), dp(54), Gravity.END | Gravity.CENTER_VERTICAL);
        flashLp.rightMargin = dp(64);
        topBar.addView(mainFlashChip, flashLp);

        TextView menuChip = topText("⋮");
        menuChip.setTextSize(34);
        menuChip.setOnClickListener(v -> setStatus("Camson Mag ahora abre el lienzo foto directamente."));
        FrameLayout.LayoutParams menuLp = new FrameLayout.LayoutParams(dp(50), dp(54), Gravity.END | Gravity.CENTER_VERTICAL);
        menuLp.rightMargin = dp(6);
        topBar.addView(menuChip, menuLp);

        crossView = new TextView(this);
        crossView.setText("+");
        crossView.setTextColor(Color.argb(190,255,255,255));
        crossView.setTextSize(58);
        crossView.setGravity(Gravity.CENTER);
        crossView.setShadowLayer(dp(3), 0, 0, Color.BLACK);
        FrameLayout.LayoutParams crossLp = new FrameLayout.LayoutParams(dp(110), dp(110), Gravity.CENTER);
        hud.addView(crossView, crossLp);

        modeBadge = new TextView(this);
        modeBadge.setText("PHOTO");
        modeBadge.setTextColor(Color.rgb(255,136,178));
        modeBadge.setTextSize(15);
        modeBadge.setTypeface(Typeface.DEFAULT_BOLD);
        modeBadge.setGravity(Gravity.CENTER);
        modeBadge.setPadding(dp(14), dp(8), dp(14), dp(8));
        modeBadge.setBackground(pill(Color.argb(172,0,0,0), Color.argb(80,255,136,178), dp(28)));
        FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        badgeLp.bottomMargin = dp(126);
        hud.addView(modeBadge, badgeLp);

        statusText = new TextView(this);
        statusText.setText("Preparando lienzo foto...");
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(12);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(dp(10), dp(8), dp(10), dp(8));
        statusText.setBackground(pill(Color.argb(150,0,0,0), Color.argb(55,255,255,255), dp(18)));
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        statusLp.leftMargin = dp(28);
        statusLp.rightMargin = dp(28);
        statusLp.bottomMargin = dp(82);
        hud.addView(statusText, statusLp);

        paintMeter = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        paintMeter.setMax(100);
        paintMeter.setProgress(100);
        paintMeter.setAlpha(0.45f);
        FrameLayout.LayoutParams meterLp = new FrameLayout.LayoutParams(-1, dp(18), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        meterLp.leftMargin = dp(38);
        meterLp.rightMargin = dp(38);
        meterLp.bottomMargin = dp(54);
        hud.addView(paintMeter, meterLp);

        // Botones ocultos que se reutilizan como estado interno cuando se abre el hub de foto.
        colorButton = hudButton("BOQ", Color.rgb(255,79,216), Color.WHITE);
        styleButton = hudButton("SENS", Color.rgb(245,238,224), Color.BLACK);
        photoButton = hudButton("PHOTO", Color.rgb(92,232,255), Color.BLACK);
        sprayButton = hudButton("SPRAY", Color.rgb(255,136,178), Color.BLACK);
        colorButton.setVisibility(View.GONE);
        styleButton.setVisibility(View.GONE);
        photoButton.setVisibility(View.GONE);
        sprayButton.setVisibility(View.GONE);
        hud.addView(colorButton, new FrameLayout.LayoutParams(1, 1));
        hud.addView(styleButton, new FrameLayout.LayoutParams(1, 1));
        hud.addView(photoButton, new FrameLayout.LayoutParams(1, 1));
        hud.addView(sprayButton, new FrameLayout.LayoutParams(1, 1));

        updateRatioUi();
        updateFlashUi();
        return hud;
    }


    private interface BitmapCallback {
        void onBitmap(Bitmap bitmap);
    }

    private void capturePhotoCanvas() {
        if (renderer == null || surfaceView == null) return;
        setStatus("Entrando directamente al lienzo foto...");
        if (modeBadge != null) modeBadge.setText("PHOTO");
        vibrate(18);
        surfaceView.queueEvent(() -> renderer.captureFrame(bitmap -> runOnUiThread(() -> {
            directPhotoWaiting = false;
            if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
                directPhotoAttempts++;
                if (directPhotoAttempts < 10) {
                    setStatus("Preparando cámara interna... abriendo modo foto en un instante.");
                    scheduleDirectPhotoMode(420);
                } else {
                    setStatus("No pude congelar la imagen todavía. Toca el icono de cámara o pulsa volumen para reintentar.");
                }
                return;
            }
            directPhotoAttempts = 0;
            showPhotoMode(bitmap);
        })));
    }

    private void scheduleDirectPhotoMode(long delayMs) {
        if (!hasCameraPermission || photoModeActive || directPhotoWaiting || rootLayout == null) return;
        directPhotoWaiting = true;
        if (statusText != null) setStatus("Preparando lienzo foto directo...");
        if (modeBadge != null) modeBadge.setText("PHOTO");
        rootLayout.postDelayed(() -> {
            if (!photoModeActive) {
                capturePhotoCanvas();
            } else {
                directPhotoWaiting = false;
            }
        }, delayMs);
    }

    private void showPhotoMode(Bitmap bitmap) {
        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            setStatus("No pude congelar la imagen. Inténtalo otra vez.");
            return;
        }
        if (photoOverlay != null && rootLayout != null) rootLayout.removeView(photoOverlay);

        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.BLACK);

        PhotoSprayView canvas = new PhotoSprayView(this, bitmap);
        canvas.setRatioMode(ratioModeIndex);
        canvas.setFlashVisual(flashModeIndex == 1);
        activePhotoView = canvas;
        photoModeActive = true;
        overlay.addView(canvas, new FrameLayout.LayoutParams(-1, -1));

        // Barra superior: icono, flecha, ratio, flash y menú, como en el diseño.
        FrameLayout topBar = new FrameLayout(this);
        topBar.setPadding(dp(18), dp(10), dp(18), dp(8));
        topBar.setBackground(topGlassBar());
        FrameLayout.LayoutParams topBarLp = new FrameLayout.LayoutParams(-1, dp(88), Gravity.TOP);
        overlay.addView(topBar, topBarLp);

        ImageView appIcon = new ImageView(this);
        int iconId = getResources().getIdentifier("ic_launcher", "mipmap", getPackageName());
        if (iconId != 0) appIcon.setImageResource(iconId);
        appIcon.setPadding(dp(3), dp(3), dp(3), dp(3));
        appIcon.setBackground(pill(Color.argb(155,10,10,12), Color.argb(95,255,255,255), dp(12)));
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(50), dp(50), Gravity.START | Gravity.CENTER_VERTICAL);
        iconLp.leftMargin = dp(2);
        topBar.addView(appIcon, iconLp);

        TextView collapseChip = topText("⌄");
        collapseChip.setTextSize(30);
        collapseChip.setOnClickListener(v -> closePhotoMode());
        FrameLayout.LayoutParams collapseLp = new FrameLayout.LayoutParams(dp(50), dp(50), Gravity.START | Gravity.CENTER_VERTICAL);
        collapseLp.leftMargin = dp(70);
        topBar.addView(collapseChip, collapseLp);

        photoRatioChip = topText(ratioLabels[ratioModeIndex]);
        photoRatioChip.setTextSize(18);
        photoRatioChip.setBackground(pill(Color.argb(42,0,0,0), Color.argb(220,255,255,255), dp(6)));
        photoRatioChip.setOnClickListener(v -> cycleRatioMode());
        FrameLayout.LayoutParams ratioLp = new FrameLayout.LayoutParams(dp(58), dp(42), Gravity.CENTER);
        topBar.addView(photoRatioChip, ratioLp);

        LinearLayout rightTop = new LinearLayout(this);
        rightTop.setOrientation(LinearLayout.HORIZONTAL);
        rightTop.setGravity(Gravity.CENTER_VERTICAL);
        photoFlashChip = topText(flashLabels[flashModeIndex]);
        photoFlashChip.setTextSize(22);
        photoFlashChip.setOnClickListener(v -> cycleFlashMode());
        TextView moreChip = topText("⋮");
        moreChip.setTextSize(32);
        moreChip.setOnClickListener(v -> setStatus("Menú pronto: capas, galería y ajustes del hub."));
        rightTop.addView(photoFlashChip, new LinearLayout.LayoutParams(dp(70), dp(50)));
        rightTop.addView(moreChip, new LinearLayout.LayoutParams(dp(44), dp(50)));
        FrameLayout.LayoutParams rightLp = new FrameLayout.LayoutParams(-2, dp(54), Gravity.END | Gravity.CENTER_VERTICAL);
        topBar.addView(rightTop, rightLp);

        // Zoom flotante encima del hub.
        LinearLayout zoomChip = new LinearLayout(this);
        zoomChip.setOrientation(LinearLayout.HORIZONTAL);
        zoomChip.setGravity(Gravity.CENTER);
        zoomChip.setPadding(dp(8), 0, dp(8), 0);
        zoomChip.setBackground(pill(Color.argb(205, 10, 10, 12), Color.argb(58,255,255,255), dp(18)));
        TextView minus = zoomText("−");
        TextView zoom = zoomText("1.0x");
        TextView plus = zoomText("+");
        minus.setOnClickListener(v -> setStatus("Zoom visual pronto: por ahora el lienzo mantiene 1.0x."));
        plus.setOnClickListener(v -> setStatus("Zoom visual pronto: por ahora el lienzo mantiene 1.0x."));
        zoomChip.addView(minus, new LinearLayout.LayoutParams(dp(42), dp(36)));
        zoomChip.addView(zoom, new LinearLayout.LayoutParams(dp(72), dp(36)));
        zoomChip.addView(plus, new LinearLayout.LayoutParams(dp(42), dp(36)));
        FrameLayout.LayoutParams zoomLp = new FrameLayout.LayoutParams(-2, dp(38), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        zoomLp.bottomMargin = dp(318);
        overlay.addView(zoomChip, zoomLp);

        // Panel inferior exacto: arco negro, dial central, Clear/Save, tarjetas y tabs.
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(14), dp(14), dp(14), dp(0));
        panel.setBackground(bottomPanel());
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(-1, dp(318), Gravity.BOTTOM);
        overlay.addView(panel, panelLp);

        FrameLayout upperPanel = new FrameLayout(this);
        panel.addView(upperPanel, new LinearLayout.LayoutParams(-1, dp(126)));

        Button clear = roundPanelButton("◌\nClear");
        FrameLayout.LayoutParams clearLp = new FrameLayout.LayoutParams(dp(88), dp(88), Gravity.START | Gravity.CENTER_VERTICAL);
        clearLp.leftMargin = dp(42);
        upperPanel.addView(clear, clearLp);

        SprayDialView sprayDial = new SprayDialView(this);
        FrameLayout.LayoutParams dialLp = new FrameLayout.LayoutParams(dp(142), dp(122), Gravity.CENTER);
        upperPanel.addView(sprayDial, dialLp);
        sprayDial.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) { startSpray(); return true; }
            if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) { stopSpray(); return true; }
            return true;
        });

        Button save = roundPanelButton("⇩\nSave");
        FrameLayout.LayoutParams saveLp = new FrameLayout.LayoutParams(dp(88), dp(88), Gravity.END | Gravity.CENTER_VERTICAL);
        saveLp.rightMargin = dp(42);
        upperPanel.addView(save, saveLp);

        LinearLayout nozzleRow = new LinearLayout(this);
        nozzleRow.setOrientation(LinearLayout.HORIZONTAL);
        nozzleRow.setGravity(Gravity.CENTER);
        nozzleRow.setPadding(dp(2), 0, dp(2), 0);
        panel.addView(nozzleRow, new LinearLayout.LayoutParams(-1, dp(104)));

        String[] labels = new String[] { "Classic", "Shadow", "Soft", "3D" };
        int[] styles = new int[] { 1, 2, 7, 3 };
        int[] colorIdx = new int[] { 1, 6, 2, 3 };
        int[] cardColors = new int[] {
                Color.rgb(255,136,178),
                Color.rgb(245,238,224),
                Color.rgb(92,180,255),
                Color.rgb(255,205,42)
        };
        for (int i = 0; i < labels.length; i++) {
            final int styleIndex = styles[i];
            final int colorIndex = colorIdx[i];
            final NozzleCardView card = new NozzleCardView(this, labels[i], cardColors[i]);
            card.setOnClickListener(v -> {
                canvas.setSprayStyle(styleIndex);
                canvas.setColorIndex(colorIndex);
                markNozzleSelection(nozzleRow, card);
                colorButton.setText(canvas.sprayStyleName());
                setStatus("Boquilla " + canvas.sprayStyleName() + " preparada.");
                vibrate(18);
            });
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(0, dp(96), 1);
            cardLp.leftMargin = dp(6);
            cardLp.rightMargin = dp(6);
            nozzleRow.addView(card, cardLp);
        }
        if (nozzleRow.getChildCount() > 0) markNozzleSelection(nozzleRow, nozzleRow.getChildAt(0));

        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setGravity(Gravity.CENTER);
        tabRow.setPadding(0, dp(2), 0, 0);
        tabRow.setBackground(pill(Color.argb(105, 0,0,0), Color.argb(30,255,255,255), dp(0)));
        TextView sprayTab = bottomTab("SPRAY", true);
        TextView photoTab = bottomTab("CAMERA", false);
        sprayTab.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) { startSpray(); return true; }
            if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) { stopSpray(); return true; }
            return true;
        });
        photoTab.setOnClickListener(v -> closePhotoMode());
        tabRow.addView(sprayTab, new LinearLayout.LayoutParams(0, dp(78), 1));
        tabRow.addView(photoTab, new LinearLayout.LayoutParams(0, dp(78), 1));
        panel.addView(tabRow, new LinearLayout.LayoutParams(-1, dp(82)));

        clear.setOnClickListener(v -> {
            canvas.clearPaint();
            setStatus("Lienzo limpio.");
            vibrate(18);
        });
        save.setOnClickListener(v -> {
            try {
                Bitmap out = canvas.exportBitmap();
                saveBitmapToDownloads(out, "camson-mag-foto-" + System.currentTimeMillis() + ".png");
                setStatus("Obra guardada en Descargas/CamsonMag.");
                vibrate(50);
            } catch (Exception e) {
                setStatus("Error guardando foto: " + e.getMessage());
            }
        });

        photoOverlay = overlay;
        rootLayout.addView(photoOverlay, new FrameLayout.LayoutParams(-1, -1));
        colorButton.setText(canvas.sprayStyleName());
        if (styleButton != null) styleButton.setText(canvas.sensitivityName());
        updatePaint(canvas.paintPercent());
        setStatus("Modo foto directo. Elige boquilla, apunta con el móvil y mantén SPRAY o volumen.");
    }

    private void closePhotoMode() {
        if (activePhotoView != null) activePhotoView.setSpraying(false);
        if (photoOverlay != null && rootLayout != null) {
            rootLayout.removeView(photoOverlay);
            photoOverlay = null;
        }
        activePhotoView = null;
        photoModeActive = false;
        colorButton.setText("BOQ");
        if (styleButton != null) styleButton.setText("SENS");
        photoRatioChip = null;
        photoFlashChip = null;
        updateRatioUi();
        updateFlashUi();
        updatePaint(100);
        modeBadge.setText("PHOTO");
        setStatus("Preparando una nueva foto directamente...");
        scheduleDirectPhotoMode(360);
    }

    private void cycleRatioMode() {
        ratioModeIndex = (ratioModeIndex + 1) % ratioLabels.length;
        updateRatioUi();
        if (activePhotoView != null) activePhotoView.setRatioMode(ratioModeIndex);
        setStatus("Formato de lienzo: " + ratioLabels[ratioModeIndex] + ".");
        vibrate(18);
    }

    private void updateRatioUi() {
        String label = ratioLabels[Math.max(0, Math.min(ratioLabels.length - 1, ratioModeIndex))];
        if (mainRatioChip != null) mainRatioChip.setText(label);
        if (photoRatioChip != null) photoRatioChip.setText(label);
    }

    private float currentRatioAspect() {
        switch (ratioModeIndex) {
            case 0: return 3f / 4f;
            case 1: return 1f;
            case 2: return 9f / 16f;
            default: return 0f;
        }
    }

    private void cycleFlashMode() {
        flashModeIndex = (flashModeIndex + 1) % flashLabels.length;
        updateFlashUi();
        applyFlashMode();
        if (activePhotoView != null) activePhotoView.setFlashVisual(flashModeIndex == 1);
        setStatus(flashModeIndex == 1 ? "Flash activo: linterna si Android la permite y brillo visual sobre el lienzo." : flashModeIndex == 2 ? "Flash apagado." : "Flash automático preparado.");
        vibrate(18);
    }

    private void updateFlashUi() {
        String label = flashLabels[Math.max(0, Math.min(flashLabels.length - 1, flashModeIndex))];
        if (mainFlashChip != null) mainFlashChip.setText(label);
        if (photoFlashChip != null) photoFlashChip.setText(label);
    }

    private void applyFlashMode() {
        boolean wantTorch = flashModeIndex == 1;
        setTorchEnabled(wantTorch);
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = wantTorch ? 1.0f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            getWindow().setAttributes(lp);
        } catch (Exception ignored) {}
    }

    private void setTorchEnabled(boolean enabled) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) return;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK && flash != null && flash) {
                    manager.setTorchMode(id, enabled);
                    torchOn = enabled;
                    return;
                }
            }
        } catch (Exception ignored) {
            torchOn = false;
        }
    }

    private void saveBitmapToDownloads(Bitmap bitmap, String filename) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CamsonMag");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new Exception("No se pudo crear archivo");
            OutputStream out = resolver.openOutputStream(uri);
            if (out != null) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                out.close();
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "CamsonMag");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, filename);
            FileOutputStream out = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.close();
        }
    }

    private TextView zoomText(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private TextView bottomTab(String text, boolean active) {
        TextView t = new TextView(this);
        t.setText(active ? text + "\n━" : text);
        t.setTextColor(active ? Color.rgb(255,136,178) : Color.argb(135,255,255,255));
        t.setTextSize(active ? 17 : 16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(6), 0, dp(2));
        return t;
    }

    private Button roundPanelButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(2), dp(2), dp(2), dp(2));
        b.setBackground(radialButtonBg());
        return b;
    }

    private GradientDrawable radialButtonBg() {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { Color.argb(215,42,42,48), Color.argb(236,5,5,8) }
        );
        g.setShape(GradientDrawable.OVAL);
        g.setStroke(dp(1), Color.argb(95,255,255,255));
        return g;
    }

    private GradientDrawable topGlassBar() {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { Color.argb(252, 0,0,0), Color.argb(224, 6,6,8), Color.argb(148, 0,0,0) }
        );
        g.setStroke(dp(1), Color.argb(16,255,255,255));
        return g;
    }

    private Button hudButton(String text, int color, int textColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13);
        b.setTextColor(textColor);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        b.setBackground(pill(color, Color.argb(80,0,0,0), dp(16)));
        return b;
    }

    private Button darkToolButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(3), dp(3), dp(3), dp(3));
        b.setBackground(pill(Color.argb(175, 12, 12, 16), Color.argb(68,255,255,255), dp(24)));
        return b;
    }

    private TextView topText(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setShadowLayer(dp(1), 0, 0, Color.BLACK);
        return t;
    }

    private TextView nozzleChip(String label, int accent) {
        TextView chip = new TextView(this);
        chip.setText("●\n" + label);
        chip.setTextColor(Color.WHITE);
        chip.setTextSize(11);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(4), dp(4), dp(4), dp(4));
        chip.setShadowLayer(dp(2), 0, 0, Color.BLACK);
        chip.setBackground(pill(Color.argb(178, 12, 12, 16), Color.argb(130, Color.red(accent), Color.green(accent), Color.blue(accent)), dp(16)));
        chip.setAlpha(0.76f);
        return chip;
    }

    private void markNozzleSelection(LinearLayout row, View selected) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            boolean on = child == selected;
            child.setAlpha(on ? 1f : 0.68f);
            child.setScaleX(on ? 1.045f : 0.97f);
            child.setScaleY(on ? 1.045f : 0.97f);
            if (child instanceof NozzleCardView) ((NozzleCardView) child).setActive(on);
        }
    }

    private GradientDrawable bottomPanel() {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { Color.argb(78, 0,0,0), Color.argb(232, 2,2,4), Color.argb(248, 0,0,0) }
        );
        float r = dp(34);
        g.setCornerRadii(new float[] { r,r, r,r, 0,0, 0,0 });
        g.setStroke(dp(1), Color.argb(42,255,255,255));
        return g;
    }

    private GradientDrawable pill(int color, int stroke, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        g.setStroke(dp(2), stroke);
        return g;
    }

    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + .5f); }
    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        if (!hasCameraPermission) return;
        try {
            if (session == null) {
                switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    case INSTALL_REQUESTED:
                        installRequested = true;
                        setStatus("Instalando/actualizando Servicios de Google Play para RA...");
                        return;
                    case INSTALLED:
                        break;
                }
                session = new Session(this);
                Config config = new Config(session);
                config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL);
                config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
                config.setFocusMode(Config.FocusMode.AUTO);
                if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    config.setDepthMode(Config.DepthMode.AUTOMATIC);
                    renderer.setDepthSupported(true);
                } else {
                    renderer.setDepthSupported(false);
                }
                session.configure(config);
                shouldConfigureSession = true;
                renderer.setSession(session);
            }
            if (shouldConfigureSession) {
                shouldConfigureSession = false;
                renderer.setDisplayGeometry(getWindowManager().getDefaultDisplay().getRotation(), surfaceView.getWidth(), surfaceView.getHeight());
            }
            session.resume();
            surfaceView.onResume();
            scheduleDirectPhotoMode(650);
        } catch (UnavailableArcoreNotInstalledException | UnavailableUserDeclinedInstallationException e) {
            setStatus("La cámara interna necesita Servicios de Google Play para RA.");
        } catch (UnavailableApkTooOldException e) {
            setStatus("Actualiza Servicios de Google Play para RA.");
        } catch (UnavailableSdkTooOldException e) {
            setStatus("Actualiza esta app: SDK AR demasiado antiguo.");
        } catch (UnavailableDeviceNotCompatibleException e) {
            setStatus("Este dispositivo no aparece como compatible con ARCore.");
        } catch (CameraNotAvailableException e) {
            setStatus("La cámara está ocupada. Cierra otras apps y reabre Camson Mag.");
        } catch (Exception e) {
            setStatus("No se pudo iniciar la cámara: " + e.getMessage());
        }
        if (sensorManager != null) {
            if (accelerometer != null) sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
            if (gyroscope != null) sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        setTorchEnabled(false);
        surfaceView.onPause();
        if (session != null) session.pause();
        if (sensorManager != null) sensorManager.unregisterListener(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        setTorchEnabled(false);
        if (session != null) {
            session.close();
            session = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == 17) {
            hasCameraPermission = grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED;
            if (!hasCameraPermission) {
                setStatus("Permiso de cámara denegado.");
            } else {
                scheduleDirectPhotoMode(650);
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            startSpray();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            stopSpray();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void startSpray() {
        if (photoModeActive && activePhotoView != null) {
            activePhotoView.setSpraying(true);
            modeBadge.setText("FOTO SPRAY");
            setStatus("Spray activo. Apunta con el giroscopio; usa CENTRO si la mira se escapa.");
            vibrate(18);
            return;
        }
        scheduleDirectPhotoMode(120);
        return;
    }

    private void stopSpray() {
        if (photoModeActive && activePhotoView != null) {
            activePhotoView.setSpraying(false);
            modeBadge.setText("FOTO");
            return;
        }
        // En la pantalla principal ya no hay spray AR: volumen captura foto.
    }

    private void setStatus(String text) {
        runOnUiThread(() -> statusText.setText(text));
    }

    private void updatePaint(int progress) {
        runOnUiThread(() -> paintMeter.setProgress(progress));
    }

    private void setSprayEnabled(boolean enabled) {
        runOnUiThread(() -> {
            if (sprayButton != null && photoModeActive) {
                sprayButton.setEnabled(enabled);
                sprayButton.setAlpha(enabled ? 1f : 0.55f);
                sprayButton.setText(enabled ? "SPRAY" : "SCAN");
            }
        });
    }

    private void updateMappingUi(int percent, boolean ready, String message) {
        if (!photoModeActive) return;
        runOnUiThread(() -> {
            if (statusText != null) statusText.setText("Modo foto activo.");
        });
    }

    private void reloadCan() {
        if (photoModeActive && activePhotoView != null) {
            activePhotoView.reloadCan();
            updatePaint(activePhotoView.paintPercent());
            setStatus("LATA RECARGADA. En modo foto ya puedes seguir rociando la imagen.");
            vibrate(90);
            return;
        }
        updatePaint(100);
        setStatus("Listo. Congela una foto para pintar encima.");
        vibrate(70);
    }

    private void vibrate(long ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(ms);
        } catch (Exception ignored) {}
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor != null && event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            if (photoModeActive && activePhotoView != null) {
                int rotation = getWindowManager().getDefaultDisplay().getRotation();
                activePhotoView.updateGyroAim(event.values[0], event.values[1], event.values[2], event.timestamp, rotation);
            }
            return;
        }

        if (event.sensor != null && event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = event.values[0], y = event.values[1], z = event.values[2];
            if (photoModeActive && activePhotoView != null && gyroscope == null) {
                int rotation = getWindowManager().getDefaultDisplay().getRotation();
                activePhotoView.updateTiltAimFallback(x, y, z, rotation);
            }
            if (!hasLast) { lastX = x; lastY = y; lastZ = z; hasLast = true; return; }
            float dx = x - lastX, dy = y - lastY, dz = z - lastZ;
            lastX = x; lastY = y; lastZ = z;
            float force = (float)Math.sqrt(dx*dx + dy*dy + dz*dz);
            long now = System.currentTimeMillis();
            if (force > 18.5f && now - lastShake > 900) {
                lastShake = now;
                reloadCan();
            }
        }
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}


    private class SprayDialView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        SprayDialView(Context c) { super(c); setLayerType(View.LAYER_TYPE_SOFTWARE, null); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            float cx = w * 0.5f;
            float cy = h * 0.56f;
            float rr = Math.min(w, h) * 0.42f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(205, 0,0,0));
            p.setShadowLayer(dp(14), 0, dp(5), Color.argb(190,0,0,0));
            canvas.drawCircle(cx, cy, rr, p);
            p.clearShadowLayer();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(4));
            p.setColor(Color.argb(95,255,255,255));
            canvas.drawCircle(cx, cy, rr * .88f, p);
            p.setStrokeWidth(dp(6));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(Color.rgb(255,136,178));
            r.set(cx - rr*.92f, cy - rr*.92f, cx + rr*.92f, cy + rr*.92f);
            canvas.drawArc(r, 142, 84, false, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(255,136,178));
            canvas.drawCircle(cx - dp(17), cy - rr - dp(5), dp(4), p);
            canvas.drawCircle(cx, cy - rr - dp(7), dp(4), p);
            p.setColor(Color.argb(130,255,255,255));
            canvas.drawCircle(cx + dp(17), cy - rr - dp(5), dp(4), p);

            // Boquilla central estilo lata.
            float capW = rr * .92f;
            float capH = rr * .62f;
            r.set(cx - capW/2, cy - capH*.35f, cx + capW/2, cy + capH*.65f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(255,145,182));
            p.setShadowLayer(dp(10), 0, dp(3), Color.argb(170,255,100,150));
            canvas.drawRoundRect(r, dp(16), dp(16), p);
            p.clearShadowLayer();
            p.setColor(Color.rgb(255,188,210));
            canvas.drawOval(cx - capW*.28f, cy - capH*.52f, cx + capW*.28f, cy - capH*.12f, p);
            p.setColor(Color.argb(230,20,20,24));
            canvas.drawCircle(cx + capW*.19f, cy + capH*.16f, dp(8), p);
            p.setColor(Color.argb(180,255,255,255));
            canvas.drawCircle(cx + capW*.17f, cy + capH*.12f, dp(2), p);
        }
    }

    private class NozzleCardView extends View {
        private final String label;
        private final int accent;
        private boolean active = false;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        NozzleCardView(Context c, String label, int accent) {
            super(c);
            this.label = label;
            this.accent = accent;
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }
        void setActive(boolean v) { active = v; invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            r.set(dp(3), dp(3), w - dp(3), h - dp(3));
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(active ? 230 : 184, 10,10,12));
            p.setShadowLayer(dp(8), 0, dp(3), Color.argb(180,0,0,0));
            canvas.drawRoundRect(r, dp(16), dp(16), p);
            p.clearShadowLayer();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(active ? 2 : 1));
            p.setColor(active ? Color.rgb(255,136,178) : Color.argb(46,255,255,255));
            canvas.drawRoundRect(r, dp(16), dp(16), p);

            float cx = w * .5f;
            float top = h * .18f;
            float capW = Math.min(w * .54f, dp(52));
            float capH = h * .31f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(140,0,0,0));
            canvas.drawOval(cx - capW*.58f, top + capH*.62f, cx + capW*.58f, top + capH*.98f, p);
            p.setColor(accent);
            p.setShadowLayer(dp(8), 0, dp(2), Color.argb(150, Color.red(accent), Color.green(accent), Color.blue(accent)));
            r.set(cx - capW*.45f, top + capH*.18f, cx + capW*.45f, top + capH*.88f);
            canvas.drawRoundRect(r, dp(10), dp(10), p);
            p.clearShadowLayer();
            p.setColor(Color.argb(235,255,255,255));
            canvas.drawOval(cx - capW*.28f, top, cx + capW*.28f, top + capH*.32f, p);
            p.setColor(Color.argb(230,20,20,24));
            canvas.drawCircle(cx + capW*.16f, top + capH*.54f, dp(6), p);
            p.setColor(Color.argb(165,255,255,255));
            canvas.drawCircle(cx + capW*.14f, top + capH*.50f, dp(1.5f), p);

            p.setShadowLayer(dp(2), 0, dp(1), Color.BLACK);
            p.setColor(active ? Color.WHITE : Color.argb(210,255,255,255));
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextSize(dp(13));
            canvas.drawText(label, cx, h - dp(18), p);
            p.clearShadowLayer();
        }
    }

    private class PhotoSprayView extends View {
        private final Bitmap baseBitmap;
        private Bitmap strokesBitmap;
        private Canvas strokesCanvas;
        private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint crossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF dest = new RectF();
        private final RectF aimBounds = new RectF();
        private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint flashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private int ratioMode = 0;
        private boolean flashVisual = false;
        private int colorIndex = 0;
        private int sprayStyleIndex = 0;
        private float aimX = -1f, aimY = -1f;
        private float lastSprayX = -1f, lastSprayY = -1f;
        private boolean hasSprayTrail = false;
        private boolean studioMode = false;
        private boolean spraying = false;
        private float paintLeft = 100f;

        private float smoothTiltX = 0f, smoothTiltY = 0f;
        private boolean tiltCalibrated = false;
        private float baseTiltX = 0f, baseTiltY = 0f;
        private float targetAimX = -1f, targetAimY = -1f;
        private long lastGyroTimestamp = 0L;
        private int sensitivityIndex = 1;
        private int axisModeIndex = 0;
        private final float[] sensitivityValues = new float[] { 760f, 1180f, 1680f };
        private final String[] sensitivityNames = new String[] { "PRECISO", "NORMAL", "RAPIDO" };
        private final String[] axisModeNames = new String[] { "EJES A", "EJES B", "EJES C", "EJES D" };

        private final int[] colors = new int[] {
                Color.rgb(182,255,53),
                Color.rgb(255,79,216),
                Color.rgb(92,232,255),
                Color.rgb(255,228,94),
                Color.rgb(255,48,48),
                Color.rgb(245,238,224),
                Color.rgb(20,20,20)
        };
        private final String[] colorNames = new String[] {
                "LIMA", "MAGENTA", "CYAN", "AMARILLO", "ROJO", "BLANCO", "NEGRO"
        };
        private final String[] sprayStyles = new String[] {
                "FINO", "CLASSIC", "SOMBRA", "3D", "FATCAP", "DRIP", "TUBO", "PASTEL"
        };

        private final Runnable sprayLoop = new Runnable() {
            @Override
            public void run() {
                if (!spraying) return;
                if (paintLeft <= 0f) {
                    spraying = false;
                    hasSprayTrail = false;
                    setStatus("Lata vacía en modo foto. Agita el móvil para recargar.");
                    return;
                }
                sprayBurst();
                invalidate();
                postDelayed(this, 28);
            }
        };

        PhotoSprayView(Context context, Bitmap bitmap) {
            super(context);
            baseBitmap = bitmap;
            dotPaint.setStyle(Paint.Style.FILL);
            dotPaint.setColor(colors[colorIndex]);
            dotPaint.setStrokeCap(Paint.Cap.ROUND);
            dotPaint.setStrokeJoin(Paint.Join.ROUND);
            crossPaint.setStyle(Paint.Style.STROKE);
            crossPaint.setStrokeWidth(dp(2));
            crossPaint.setColor(Color.WHITE);
            setBackgroundColor(Color.BLACK);
        }

        void setStudioMode(boolean enabled) {
            studioMode = enabled;
            invalidate();
        }

        void setRatioMode(int index) {
            ratioMode = Math.max(0, Math.min(ratioLabels.length - 1, index));
            updateContentRect();
            clampAimToContent();
            invalidate();
        }

        void setFlashVisual(boolean enabled) {
            flashVisual = enabled;
            invalidate();
        }

        void centerAim() {
            updateContentRect();
            if (getWidth() > 0 && getHeight() > 0) {
                aimX = aimBounds.centerX();
                aimY = aimBounds.centerY();
            }
            targetAimX = aimX;
            targetAimY = aimY;
            smoothTiltX = 0f;
            smoothTiltY = 0f;
            lastGyroTimestamp = 0L;
            tiltCalibrated = false;
            hasSprayTrail = false;
            invalidate();
        }

        void nextSensitivity() {
            sensitivityIndex = (sensitivityIndex + 1) % sensitivityNames.length;
        }

        String sensitivityName() {
            return sensitivityNames[sensitivityIndex];
        }

        void nextAxisMode() {
            axisModeIndex = (axisModeIndex + 1) % axisModeNames.length;
            lastGyroTimestamp = 0L;
        }

        String axisModeName() {
            return axisModeNames[axisModeIndex];
        }

        void setSpraying(boolean value) {
            if (value == spraying) return;
            spraying = value;
            removeCallbacks(sprayLoop);
            if (spraying) {
                hasSprayTrail = false;
                post(sprayLoop);
            } else {
                hasSprayTrail = false;
            }
        }

        void reloadCan() {
            paintLeft = 100f;
            updatePaint(100);
        }

        int paintPercent() {
            return Math.max(0, Math.min(100, Math.round(paintLeft)));
        }

        void nextColor() {
            colorIndex = (colorIndex + 1) % colors.length;
            dotPaint.setColor(colors[colorIndex]);
            invalidate();
        }

        void setColorIndex(int index) {
            colorIndex = Math.max(0, Math.min(colors.length - 1, index));
            dotPaint.setColor(colors[colorIndex]);
            invalidate();
        }

        String colorName() { return colorNames[colorIndex]; }

        void nextSprayStyle() {
            sprayStyleIndex = (sprayStyleIndex + 1) % sprayStyles.length;
            invalidate();
        }

        void setSprayStyle(int index) {
            sprayStyleIndex = Math.max(0, Math.min(sprayStyles.length - 1, index));
            hasSprayTrail = false;
            invalidate();
        }

        String sprayStyleName() { return sprayStyles[sprayStyleIndex]; }

        void clearPaint() {
            if (strokesBitmap != null) {
                strokesBitmap.eraseColor(Color.TRANSPARENT);
                invalidate();
            }
        }

        Bitmap exportBitmap() {
            Bitmap out = Bitmap.createBitmap(Math.max(1, getWidth()), Math.max(1, getHeight()), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            draw(c);
            return out;
        }

        void updateGyroAim(float gx, float gy, float gz, long timestamp, int rotation) {
            if (getWidth() <= 0 || getHeight() <= 0) return;
            if (aimX < 0 || aimY < 0) centerAim();
            if (targetAimX < 0 || targetAimY < 0) {
                targetAimX = aimX;
                targetAimY = aimY;
            }

            if (lastGyroTimestamp == 0L) {
                lastGyroTimestamp = timestamp;
                return;
            }

            float dt = (timestamp - lastGyroTimestamp) * 1.0e-9f;
            lastGyroTimestamp = timestamp;
            if (dt <= 0f) return;
            if (dt > 0.045f) dt = 0.045f;

            float moveX;
            float moveY;
            switch (rotation) {
                case 1:
                    moveX = -gx;
                    moveY = -gy;
                    break;
                case 2:
                    moveX = gy;
                    moveY = gx;
                    break;
                case 3:
                    moveX = gx;
                    moveY = gy;
                    break;
                default:
                    moveX = -gy;
                    moveY = gx;
                    break;
            }

            if (axisModeIndex == 1 || axisModeIndex == 3) moveX = -moveX;
            if (axisModeIndex == 2 || axisModeIndex == 3) moveY = -moveY;

            float dead = 0.018f;
            if (Math.abs(moveX) < dead) moveX = 0f;
            if (Math.abs(moveY) < dead) moveY = 0f;

            float speed = sensitivityValues[sensitivityIndex];
            float boost = spraying ? 0.72f : 1.0f;
            targetAimX += moveX * speed * boost * dt;
            targetAimY += moveY * speed * boost * dt;

            updateContentRect();
            float margin = dp(24);
            targetAimX = clamp(targetAimX, aimBounds.left + margin, aimBounds.right - margin);
            targetAimY = clamp(targetAimY, aimBounds.top + margin, aimBounds.bottom - margin);

            aimX = aimX * 0.74f + targetAimX * 0.26f;
            aimY = aimY * 0.74f + targetAimY * 0.26f;
            postInvalidateOnAnimation();
        }

        void updateTiltAimFallback(float x, float y, float z, int rotation) {
            if (getWidth() <= 0 || getHeight() <= 0) return;
            float tx, ty;
            switch (rotation) {
                case 1: tx = y; ty = x; break;
                case 2: tx = x; ty = -y; break;
                case 3: tx = -y; ty = -x; break;
                default: tx = -x; ty = y; break;
            }

            if (!tiltCalibrated) {
                baseTiltX = tx;
                baseTiltY = ty;
                centerAim();
                tiltCalibrated = true;
                return;
            }

            float relX = tx - baseTiltX;
            float relY = ty - baseTiltY;
            smoothTiltX = smoothTiltX * 0.94f + relX * 0.06f;
            smoothTiltY = smoothTiltY * 0.94f + relY * 0.06f;

            float nx = clamp(smoothTiltX / 5.0f, -1f, 1f);
            float ny = clamp(smoothTiltY / 5.0f, -1f, 1f);
            updateContentRect();
            targetAimX = aimBounds.centerX() + nx * aimBounds.width() * 0.34f;
            targetAimY = aimBounds.centerY() + ny * aimBounds.height() * 0.34f;
            targetAimX = clamp(targetAimX, aimBounds.left + dp(24), aimBounds.right - dp(24));
            targetAimY = clamp(targetAimY, aimBounds.top + dp(24), aimBounds.bottom - dp(24));
            aimX = aimX * 0.86f + targetAimX * 0.14f;
            aimY = aimY * 0.86f + targetAimY * 0.14f;
            postInvalidateOnAnimation();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            removeCallbacks(sprayLoop);
            spraying = false;
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (w > 0 && h > 0) {
                strokesBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                strokesCanvas = new Canvas(strokesBitmap);
                updateContentRect();
                if (aimX < 0 || aimY < 0) {
                    aimX = aimBounds.centerX();
                    aimY = aimBounds.centerY();
                    targetAimX = aimX;
                    targetAimY = aimY;
                }
                clampAimToContent();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            updateContentRect();
            canvas.drawColor(Color.BLACK);
            canvas.drawBitmap(baseBitmap, null, dest, imagePaint);
            if (flashVisual) {
                flashPaint.setStyle(Paint.Style.FILL);
                flashPaint.setColor(Color.argb(34, 255, 244, 218));
                canvas.drawRect(dest, flashPaint);
            }
            if (strokesBitmap != null) canvas.drawBitmap(strokesBitmap, 0, 0, null);
            drawRatioMask(canvas);
            drawCrosshair(canvas);
        }

        private void updateContentRect() {
            float w = Math.max(1, getWidth());
            float h = Math.max(1, getHeight());
            float aspect;
            switch (ratioMode) {
                case 0: aspect = 3f / 4f; break;
                case 1: aspect = 1f; break;
                case 2: aspect = 9f / 16f; break;
                default: aspect = 0f; break;
            }
            if (aspect <= 0f) {
                dest.set(0, 0, w, h);
            } else {
                float targetW = w;
                float targetH = targetW / aspect;
                if (targetH > h) {
                    targetH = h;
                    targetW = targetH * aspect;
                }
                float left = (w - targetW) * 0.5f;
                float top = (h - targetH) * 0.5f;
                dest.set(left, top, left + targetW, top + targetH);
            }
            aimBounds.set(dest);
        }

        private void clampAimToContent() {
            if (getWidth() <= 0 || getHeight() <= 0) return;
            updateContentRect();
            float margin = dp(24);
            aimX = clamp(aimX, aimBounds.left + margin, aimBounds.right - margin);
            aimY = clamp(aimY, aimBounds.top + margin, aimBounds.bottom - margin);
            targetAimX = clamp(targetAimX, aimBounds.left + margin, aimBounds.right - margin);
            targetAimY = clamp(targetAimY, aimBounds.top + margin, aimBounds.bottom - margin);
        }

        private void drawRatioMask(Canvas canvas) {
            if (ratioMode == 3) return;
            dimPaint.setStyle(Paint.Style.FILL);
            dimPaint.setColor(Color.argb(studioMode ? 92 : 64, 0, 0, 0));
            canvas.drawRect(0, 0, getWidth(), dest.top, dimPaint);
            canvas.drawRect(0, dest.bottom, getWidth(), getHeight(), dimPaint);
            canvas.drawRect(0, dest.top, dest.left, dest.bottom, dimPaint);
            canvas.drawRect(dest.right, dest.top, getWidth(), dest.bottom, dimPaint);
            crossPaint.setStyle(Paint.Style.STROKE);
            crossPaint.setStrokeWidth(dp(1.2f));
            crossPaint.setColor(Color.argb(120,255,255,255));
            canvas.drawRect(dest, crossPaint);
        }

        private void drawCrosshair(Canvas canvas) {
            if (aimX < 0 || aimY < 0) return;
            float r = studioMode ? dp(13) : dp(18);
            crossPaint.setColor(studioMode ? Color.argb(175,255,255,255) : Color.argb(220,255,255,255));
            crossPaint.setStrokeWidth(studioMode ? dp(1.5f) : dp(2f));
            canvas.drawCircle(aimX, aimY, r, crossPaint);
            canvas.drawLine(aimX - r - dp(10), aimY, aimX - dp(4), aimY, crossPaint);
            canvas.drawLine(aimX + dp(4), aimY, aimX + r + dp(10), aimY, crossPaint);
            canvas.drawLine(aimX, aimY - r - dp(10), aimX, aimY - dp(4), crossPaint);
            canvas.drawLine(aimX, aimY + dp(4), aimX, aimY + r + dp(10), crossPaint);

            if (!studioMode) {
                Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
                label.setTextSize(dp(12));
                label.setColor(Color.WHITE);
                label.setShadowLayer(dp(2), 0, 0, Color.BLACK);
                canvas.drawText(sprayStyleName(), aimX + dp(26), aimY - dp(10), label);
            }
        }

        private void sprayBurst() {
            if (strokesCanvas == null) return;
            float currentX = aimX;
            float currentY = aimY;
            int steps = 1;

            if (hasSprayTrail) {
                float dx = currentX - lastSprayX;
                float dy = currentY - lastSprayY;
                float dist = (float)Math.sqrt(dx * dx + dy * dy);
                steps = Math.max(1, Math.min(10, (int)(dist / dp(6f)) + 1));
                for (int i = 1; i <= steps; i++) {
                    float t = i / (float)steps;
                    float x = lastSprayX + dx * t;
                    float y = lastSprayY + dy * t;
                    sprayAt(x, y, lastSprayX + dx * Math.max(0f, (i - 1) / (float)steps), lastSprayY + dy * Math.max(0f, (i - 1) / (float)steps));
                }
            } else {
                sprayAt(currentX, currentY, currentX, currentY);
                hasSprayTrail = true;
            }

            lastSprayX = currentX;
            lastSprayY = currentY;
            float cost = 0.28f + steps * 0.16f;
            if (sprayStyleIndex == 4) cost += 0.10f;
            if (sprayStyleIndex == 6) cost += 0.08f;
            paintLeft = Math.max(0f, paintLeft - cost);
            updatePaint(paintPercent());
        }

        private void sprayAt(float x, float y, float prevX, float prevY) {
            switch (sprayStyleIndex) {
                case 0: sprayFine(x, y, prevX, prevY); break;
                case 1: sprayClassic(x, y, prevX, prevY); break;
                case 2: sprayShadow(x, y, prevX, prevY); break;
                case 3: spray3D(x, y, prevX, prevY); break;
                case 4: sprayFatCap(x, y, prevX, prevY); break;
                case 5: sprayDrip(x, y, prevX, prevY); break;
                case 6: sprayTube(x, y, prevX, prevY); break;
                case 7: sprayPastel(x, y, prevX, prevY); break;
                default: sprayClassic(x, y, prevX, prevY); break;
            }
        }

        private void sprayFine(float x, float y, float px, float py) {
            drawBridge(px, py, x, y, dp(5.5f), colors[colorIndex], 150, false);
            sprayCoreAt(x, y, colors[colorIndex], dp(5.5f), 7, 105, 210, 0f, 0f, 0.65f);
            sprayMistAt(x, y, colors[colorIndex], 5, dp(8f), 30, 90, 0f, 0f, 0.45f);
        }

        private void sprayClassic(float x, float y, float px, float py) {
            drawBridge(px, py, x, y, dp(9.0f), colors[colorIndex], 118, false);
            sprayCoreAt(x, y, colors[colorIndex], dp(9.5f), 12, 105, 225, 0f, 0f, 0.85f);
            sprayMistAt(x, y, colors[colorIndex], 8, dp(12.5f), 32, 96, 0f, 0f, 0.55f);
        }

        private void sprayShadow(float x, float y, float px, float py) {
            int shadowColor = Color.argb(155, 0, 0, 0);
            drawBridge(px + dp(7), py + dp(6), x + dp(7), y + dp(6), dp(12.5f), shadowColor, 120, false);
            sprayCoreAt(x + dp(7), y + dp(6), shadowColor, dp(10.5f), 9, 70, 150, 0f, 0f, 0.86f);
            drawBridge(px, py, x, y, dp(9.5f), colors[colorIndex], 145, false);
            sprayCoreAt(x, y, colors[colorIndex], dp(9f), 10, 120, 235, 0f, 0f, 0.82f);
            sprayMistAt(x, y, colors[colorIndex], 5, dp(11f), 35, 95, 0f, 0f, 0.42f);
        }

        private void spray3D(float x, float y, float px, float py) {
            int base = colors[colorIndex];
            int dark = mixWith(Color.BLACK, base, 0.28f);
            int light = mixWith(Color.WHITE, base, 0.48f);
            drawBridge(px + dp(7), py + dp(8), x + dp(7), y + dp(8), dp(13f), dark, 145, false);
            drawBridge(px, py, x, y, dp(11f), base, 170, false);
            drawBridge(px - dp(4), py - dp(5), x - dp(4), y - dp(5), dp(4.5f), light, 120, false);
            sprayCoreAt(x, y, base, dp(8.5f), 8, 115, 220, 0f, 0f, 0.75f);
        }

        private void sprayFatCap(float x, float y, float px, float py) {
            drawBridge(px, py, x, y, dp(18f), colors[colorIndex], 128, false);
            sprayCoreAt(x, y, colors[colorIndex], dp(16f), 16, 115, 235, 0f, 0f, 1.15f);
            sprayMistAt(x, y, colors[colorIndex], 13, dp(20f), 35, 115, 0f, 0f, 0.75f);
        }

        private void sprayDrip(float x, float y, float px, float py) {
            drawBridge(px, py, x, y, dp(12f), colors[colorIndex], 150, false);
            sprayCoreAt(x, y, colors[colorIndex], dp(10f), 11, 120, 235, 0f, 0f, 0.9f);
            sprayMistAt(x, y, colors[colorIndex], 6, dp(11f), 35, 90, 0f, 0f, 0.45f);
            drawDripsAt(x, y, colors[colorIndex], 3, dp(22), 0f, 0f, 0.65f);
        }

        private void sprayTube(float x, float y, float px, float py) {
            int base = colors[colorIndex];
            int dark = mixWith(Color.BLACK, base, 0.45f);
            int midDark = mixWith(Color.BLACK, base, 0.22f);
            int light = mixWith(Color.WHITE, base, 0.55f);
            drawBridge(px + dp(8), py + dp(9), x + dp(8), y + dp(9), dp(16f), dark, 190, false);
            drawBridge(px + dp(3), py + dp(4), x + dp(3), y + dp(4), dp(15f), midDark, 165, false);
            drawBridge(px, py, x, y, dp(13f), base, 210, false);
            drawBridge(px - dp(5), py - dp(6), x - dp(5), y - dp(6), dp(5.2f), light, 160, true);
            sprayMistAt(x, y, base, 3, dp(7f), 20, 55, 0f, 0f, 0.32f);
        }

        private void sprayPastel(float x, float y, float px, float py) {
            int soft = mixWith(Color.WHITE, colors[colorIndex], 0.34f);
            drawBridge(px, py, x, y, dp(15f), soft, 48, false);
            sprayMistAt(x, y, soft, 18, dp(18f), 24, 72, 0f, 0f, 0.92f);
            sprayCoreAt(x, y, soft, dp(9f), 6, 55, 110, 0f, 0f, 0.72f);
        }

        private void drawBridge(float x1, float y1, float x2, float y2, float width, int color, int alpha, boolean highlight) {
            dotPaint.setStyle(Paint.Style.STROKE);
            dotPaint.setStrokeCap(Paint.Cap.ROUND);
            dotPaint.setStrokeJoin(Paint.Join.ROUND);
            dotPaint.setStrokeWidth(width);
            dotPaint.setColor(color);
            dotPaint.setAlpha(alpha);
            strokesCanvas.drawLine(x1, y1, x2, y2, dotPaint);
            if (highlight) {
                dotPaint.setAlpha(Math.min(135, alpha));
                dotPaint.setStrokeWidth(width * 0.55f);
                strokesCanvas.drawLine(x1, y1, x2, y2, dotPaint);
            }
            dotPaint.setStyle(Paint.Style.FILL);
        }

        private void sprayCoreAt(float cx, float cy, int color, float radius, int dots, int alphaMin, int alphaMax, float dx, float dy, float sizeScale) {
            for (int i = 0; i < dots; i++) {
                double a = Math.random() * Math.PI * 2.0;
                float rr = (float)Math.pow(Math.random(), 2.25) * radius;
                float px = cx + dx + (float)Math.cos(a) * rr;
                float py = cy + dy + (float)Math.sin(a) * rr;
                float size = (dp(1.4f) + (float)Math.random() * dp(3.2f)) * sizeScale;
                int alpha = alphaMin + (int)(Math.random() * Math.max(1, alphaMax - alphaMin + 1));
                dotPaint.setStyle(Paint.Style.FILL);
                dotPaint.setColor(color);
                dotPaint.setAlpha(alpha);
                strokesCanvas.drawCircle(px, py, size, dotPaint);
            }
            dotPaint.setColor(color);
            dotPaint.setAlpha(Math.min(170, alphaMax));
            strokesCanvas.drawCircle(cx + dx, cy + dy, radius * 0.22f * sizeScale, dotPaint);
        }

        private void sprayMistAt(float cx, float cy, int color, int dots, float spread, int alphaMin, int alphaMax, float dx, float dy, float sizeScale) {
            for (int i = 0; i < dots; i++) {
                double a = Math.random() * Math.PI * 2.0;
                float rr = (float)Math.pow(Math.random(), 3.0) * spread;
                float px = cx + dx + (float)Math.cos(a) * rr;
                float py = cy + dy + (float)Math.sin(a) * rr;
                float size = (dp(0.65f) + (float)Math.random() * dp(1.9f)) * sizeScale;
                int alpha = alphaMin + (int)(Math.random() * Math.max(1, alphaMax - alphaMin + 1));
                dotPaint.setStyle(Paint.Style.FILL);
                dotPaint.setColor(color);
                dotPaint.setAlpha(alpha);
                strokesCanvas.drawCircle(px, py, size, dotPaint);
            }
        }

        private void drawDripsAt(float cx, float cy, int color, int count, float maxLen, float dx, float dy, float density) {
            for (int i = 0; i < count; i++) {
                if (Math.random() > 0.72) continue;
                float dripLen = dp(5) + (float)Math.random() * maxLen;
                float px = cx + dx + ((float)Math.random() - 0.5f) * dp(20) * density;
                float py = cy + dy + ((float)Math.random() - 0.5f) * dp(7) * density;
                dotPaint.setStyle(Paint.Style.STROKE);
                dotPaint.setStrokeCap(Paint.Cap.ROUND);
                dotPaint.setColor(color);
                dotPaint.setAlpha(70 + (int)(Math.random() * 80));
                dotPaint.setStrokeWidth(dp(1.4f) + (float)Math.random() * dp(2.2f));
                strokesCanvas.drawLine(px, py, px + ((float)Math.random() - 0.5f) * dp(3), py + dripLen, dotPaint);
            }
            dotPaint.setStyle(Paint.Style.FILL);
        }

        private int mixWith(int target, int base, float amount) {
            int r = (int)(Color.red(base) * (1f - amount) + Color.red(target) * amount);
            int g = (int)(Color.green(base) * (1f - amount) + Color.green(target) * amount);
            int b = (int)(Color.blue(base) * (1f - amount) + Color.blue(target) * amount);
            return Color.rgb(clamp(r, 0, 255), clamp(g, 0, 255), clamp(b, 0, 255));
        }

        private float clamp(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }
        private int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            // La mira se controla con el móvil. El dedo no empuja la pintura.
            return true;
        }
    }

    private class SprayRenderer implements GLSurfaceView.Renderer {
        private final Activity activity;
        private Session arSession;
        private final BackgroundRenderer background = new BackgroundRenderer();
        private final MarkRenderer markRenderer = new MarkRenderer();
        private final List<SprayMark> marks = new ArrayList<>();
        private final float[] viewMatrix = new float[16];
        private final float[] projectionMatrix = new float[16];
        private final float[] viewProjectionMatrix = new float[16];
        private int viewportWidth = 1, viewportHeight = 1, displayRotation = 0;
        private volatile boolean spraying = false;
        private float paintLeft = 100f;
        private long lastSprayMs = 0;
        private int colorIndex = 0;
        private BitmapCallback captureCallback = null;
        private final int[] colors = new int[] {
                Color.rgb(182,255,53), Color.rgb(255,79,216), Color.rgb(92,232,255),
                Color.rgb(255,228,94), Color.rgb(255,48,48), Color.rgb(245,238,224), Color.rgb(20,20,20)
        };
        private final String[] colorNames = new String[] { "LIMA", "MAGENTA", "CYAN", "AMARILLO", "ROJO", "BLANCO", "NEGRO" };

        private boolean depthSupported = false;
        private volatile boolean mappingReady = false;
        private int stableFrames = 0;
        private int lastUiPercent = -1;
        private boolean lastUiReady = false;
        private long lastUiUpdate = 0;
        private volatile String mappingHint = "Mapeando entorno...";

        SprayRenderer(Activity activity) { this.activity = activity; }
        void setSession(Session session) { this.arSession = session; }
        void setDepthSupported(boolean supported) { depthSupported = supported; }
        boolean isMappingReady() { return mappingReady; }
        String mappingHint() { return mappingHint; }
        void setSpraying(boolean b) { spraying = b; }
        void reloadCan() { paintLeft = 100f; updatePaint(100); }
        void nextColor() { colorIndex = (colorIndex + 1) % colors.length; }
        String colorName() { return colorNames[colorIndex]; }
        void setDisplayGeometry(int rotation, int width, int height) {
            displayRotation = rotation;
            viewportWidth = Math.max(1, width);
            viewportHeight = Math.max(1, height);
            if (arSession != null) arSession.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight);
        }
        void clearMarks() {
            for (SprayMark m : marks) m.anchor.detach();
            marks.clear();
        }

        void captureFrame(BitmapCallback callback) {
            captureCallback = callback;
        }

        @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            GLES20.glClearColor(0,0,0,1);
            background.createOnGlThread();
            markRenderer.createOnGlThread();
        }

        @Override public void onSurfaceChanged(GL10 gl, int width, int height) {
            GLES20.glViewport(0,0,width,height);
            setDisplayGeometry(getWindowManager().getDefaultDisplay().getRotation(), width, height);
        }

        @Override public void onDrawFrame(GL10 gl) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            if (arSession == null) return;
            try {
                arSession.setCameraTextureName(background.getTextureId());
                Frame frame = arSession.update();
                background.draw(frame);
                Camera camera = frame.getCamera();
                if (camera.getTrackingState() != TrackingState.TRACKING) {
                    mappingReady = false;
                    stableFrames = 0;
                    pushMappingUi(5, false, "Mapeando entorno... mueve el móvil despacio para que AR encuentre pared o suelo.");
                    deliverCaptureIfRequested();
                    return;
                }
                camera.getViewMatrix(viewMatrix, 0);
                camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f);
                Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0);
                updateMapping(frame, camera);
                if (spraying && mappingReady) maybeSpray(frame);
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                GLES20.glDepthMask(false);
                for (Iterator<SprayMark> it = marks.iterator(); it.hasNext();) {
                    SprayMark mark = it.next();
                    if (mark.anchor.getTrackingState() == TrackingState.STOPPED) { it.remove(); continue; }
                    if (mark.anchor.getTrackingState() == TrackingState.TRACKING) markRenderer.draw(mark, viewProjectionMatrix);
                }
                GLES20.glDepthMask(true);
                GLES20.glDisable(GLES20.GL_BLEND);
                deliverCaptureIfRequested();
            } catch (Throwable t) {
                setStatus("AR se ha quejado: " + t.getMessage());
            }
        }

        private void updateMapping(Frame frame, Camera camera) {
            int trackedPlanes = 0;
            int trackedVerticalPlanes = 0;
            int trackedHorizontalPlanes = 0;

            for (Plane plane : arSession.getAllTrackables(Plane.class)) {
                if (plane.getTrackingState() != TrackingState.TRACKING) continue;
                if (plane.getSubsumedBy() != null) continue;
                trackedPlanes++;
                if (plane.getType() == Plane.Type.VERTICAL) trackedVerticalPlanes++;
                else if (plane.getType() == Plane.Type.HORIZONTAL_UPWARD_FACING
                        || plane.getType() == Plane.Type.HORIZONTAL_DOWNWARD_FACING) trackedHorizontalPlanes++;
            }

            HitResult centerHit = findWorldHit(frame);
            boolean hasCenterHit = centerHit != null;
            boolean centerIsVerticalPlane = false;
            if (centerHit != null && centerHit.getTrackable() instanceof Plane) {
                Plane p = (Plane) centerHit.getTrackable();
                centerIsVerticalPlane = p.getType() == Plane.Type.VERTICAL;
            }

            boolean stableNow = hasCenterHit || trackedPlanes > 0;
            if (stableNow) stableFrames = Math.min(120, stableFrames + 1);
            else stableFrames = Math.max(0, stableFrames - 2);

            int score = 0;
            if (camera.getTrackingState() == TrackingState.TRACKING) score += 20;
            if (trackedPlanes > 0) score += 20;
            if (trackedHorizontalPlanes > 0) score += 10;
            if (trackedVerticalPlanes > 0) score += 20;
            if (hasCenterHit) score += 20;
            if (centerIsVerticalPlane) score += 10;
            if (depthSupported) score += 10;

            int percent = Math.min(100, score);
            if (stableFrames >= 8) percent = Math.min(100, percent + 8);
            if (stableFrames >= 14) percent = Math.min(100, percent + 8);
            if (stableFrames >= 18) percent = Math.min(100, percent + 8);

            mappingReady = trackedPlanes > 0
                    && hasCenterHit
                    && stableFrames >= 18
                    && (trackedVerticalPlanes > 0 || centerIsVerticalPlane || trackedHorizontalPlanes > 0);

            if (mappingReady) {
                percent = 100;
                mappingHint = "Listo para pintar. Apunta y pulsa volumen o SPRAY.";
            } else if (trackedPlanes == 0) {
                mappingHint = "Mapeando entorno... mueve el móvil lento y apunta a pared o suelo con textura.";
            } else if (trackedVerticalPlanes == 0 && trackedHorizontalPlanes > 0) {
                mappingHint = "Suelo detectado. Para graffiti mejor apunta a una pared con textura.";
            } else if (!hasCenterHit) {
                mappingHint = "Superficie detectada. Coloca la cruz sobre pared o suelo para fijar el punto de pintura.";
            } else if (stableFrames < 18) {
                mappingHint = "Fijando superficie... mantén la mira estable un instante.";
            } else {
                mappingHint = "Mapeando...";
            }

            pushMappingUi(percent, mappingReady, mappingHint);
        }

        private void pushMappingUi(int percent, boolean ready, String message) {
            long now = System.currentTimeMillis();
            if (percent == lastUiPercent && ready == lastUiReady && now - lastUiUpdate < 220) return;
            lastUiPercent = percent;
            lastUiReady = ready;
            lastUiUpdate = now;
            updateMappingUi(percent, ready, message);
        }


        private void deliverCaptureIfRequested() {
            BitmapCallback callback = captureCallback;
            if (callback == null) return;
            captureCallback = null;
            try {
                callback.onBitmap(readCurrentFrameBitmap());
            } catch (Throwable t) {
                callback.onBitmap(null);
            }
        }

        private Bitmap readCurrentFrameBitmap() {
            int w = Math.max(1, viewportWidth);
            int h = Math.max(1, viewportHeight);
            IntBuffer buffer = IntBuffer.allocate(w * h);
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer);
            int[] src = buffer.array();
            int[] dst = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int pix = src[y * w + x];
                    int blue = (pix >> 16) & 0xff;
                    int red = (pix << 16) & 0x00ff0000;
                    int fixed = (pix & 0xff00ff00) | red | blue;
                    dst[(h - y - 1) * w + x] = fixed;
                }
            }
            return Bitmap.createBitmap(dst, w, h, Bitmap.Config.ARGB_8888);
        }

        private void maybeSpray(Frame frame) {
            long now = System.currentTimeMillis();
            if (now - lastSprayMs < 45) return;
            lastSprayMs = now;
            if (paintLeft <= 0f) {
                setSpraying(false);
                setStatus("Lata vacía. Agita para recargar.");
                return;
            }

            HitResult bestHit = findWorldHit(frame);
            if (bestHit != null) {
                Anchor anchor = bestHit.createAnchor();
                marks.add(new SprayMark(anchor, colors[colorIndex], 0.075f + (float)Math.random()*0.055f));
                while (marks.size() > 280) {
                    SprayMark old = marks.remove(0);
                    old.anchor.detach();
                }
                paintLeft = Math.max(0f, paintLeft - 0.65f);
                updatePaint((int) paintLeft);
                setStatus("Pintura anclada al mundo. Mueve el móvil: debería quedarse ahí.");
                return;
            }
            setStatus("Aún no tengo superficie. Muévete lento en forma de 8 y apunta a una pared con textura.");
        }

        private HitResult findWorldHit(Frame frame) {
            List<HitResult> hits = frame.hitTest(viewportWidth / 2f, viewportHeight / 2f);
            HitResult verticalPlaneHit = null;
            HitResult anyPlaneHit = null;
            HitResult pointHit = null;

            for (HitResult hit : hits) {
                Trackable trackable = hit.getTrackable();

                if (trackable instanceof Plane) {
                    Plane plane = (Plane) trackable;
                    if (plane.getTrackingState() != TrackingState.TRACKING) continue;
                    if (!plane.isPoseInPolygon(hit.getHitPose())) continue;
                    if (plane.getType() == Plane.Type.VERTICAL) {
                        verticalPlaneHit = hit;
                        break;
                    }
                    if (anyPlaneHit == null) anyPlaneHit = hit;
                } else if (trackable instanceof Point) {
                    Point point = (Point) trackable;
                    if (point.getTrackingState() != TrackingState.TRACKING) continue;
                    if (point.getOrientationMode() == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL && pointHit == null) {
                        pointHit = hit;
                    }
                }
            }

            if (verticalPlaneHit != null) return verticalPlaneHit;
            if (anyPlaneHit != null) return anyPlaneHit;
            return pointHit;
        }
    }

    private static class SprayMark {
        final Anchor anchor;
        final float[] rgba;
        final float radius;
        final float jitter;
        SprayMark(Anchor anchor, int color, float radius) {
            this.anchor = anchor;
            this.radius = radius;
            this.jitter = (float)Math.random() * 6.28318f;
            this.rgba = new float[] {
                    Color.red(color)/255f, Color.green(color)/255f, Color.blue(color)/255f, .92f
            };
        }
    }

    private static class BackgroundRenderer {
        private int textureId = -1;
        private int program;
        private int positionAttrib;
        private int texCoordAttrib;
        private int textureUniform;
        private boolean textureCoordsReady = false;
        private final FloatBuffer quadVertices = floatBuffer(new float[] { -1,-1, 1,-1, -1,1, 1,1 });
        private final FloatBuffer transformedQuadTex = floatBuffer(new float[] { 0,0, 0,0, 0,0, 0,0 });

        int getTextureId() { return textureId; }
        void createOnGlThread() {
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            textureId = textures[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            program = createProgram(
                    "attribute vec4 a_Position; attribute vec2 a_TexCoord; varying vec2 v_TexCoord; void main(){ gl_Position=a_Position; v_TexCoord=a_TexCoord; }",
                    "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES u_Texture; varying vec2 v_TexCoord; void main(){ gl_FragColor=texture2D(u_Texture, v_TexCoord); }");
            positionAttrib = GLES20.glGetAttribLocation(program, "a_Position");
            texCoordAttrib = GLES20.glGetAttribLocation(program, "a_TexCoord");
            textureUniform = GLES20.glGetUniformLocation(program, "u_Texture");
        }
        void draw(Frame frame) {
            if (frame != null && (frame.hasDisplayGeometryChanged() || !textureCoordsReady)) {
                quadVertices.position(0);
                transformedQuadTex.position(0);
                frame.transformCoordinates2d(
                        Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                        quadVertices,
                        Coordinates2d.TEXTURE_NORMALIZED,
                        transformedQuadTex);
                textureCoordsReady = true;
            }
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            GLES20.glDepthMask(false);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            GLES20.glUniform1i(textureUniform, 0);
            quadVertices.position(0);
            GLES20.glVertexAttribPointer(positionAttrib, 2, GLES20.GL_FLOAT, false, 0, quadVertices);
            GLES20.glEnableVertexAttribArray(positionAttrib);
            transformedQuadTex.position(0);
            GLES20.glVertexAttribPointer(texCoordAttrib, 2, GLES20.GL_FLOAT, false, 0, transformedQuadTex);
            GLES20.glEnableVertexAttribArray(texCoordAttrib);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(positionAttrib);
            GLES20.glDisableVertexAttribArray(texCoordAttrib);
            GLES20.glDepthMask(true);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        }
    }

    private static class MarkRenderer {
        private int program;
        private int positionAttrib;
        private int uvAttrib;
        private int mvpUniform;
        private int colorUniform;
        private final FloatBuffer vertices = floatBuffer(new float[] {
                -1,0,-1,  1,0,-1,  -1,0,1,  1,0,1
        });
        private final FloatBuffer uvs = floatBuffer(new float[] { 0,0, 1,0, 0,1, 1,1 });
        private final float[] model = new float[16];
        private final float[] scale = new float[16];
        private final float[] tmp = new float[16];
        private final float[] mvp = new float[16];

        void createOnGlThread() {
            program = createProgram(
                    "uniform mat4 u_Mvp; attribute vec3 a_Position; attribute vec2 a_Uv; varying vec2 v_Uv; void main(){ v_Uv=a_Uv; gl_Position=u_Mvp*vec4(a_Position,1.0); }",
                    "precision mediump float; uniform vec4 u_Color; varying vec2 v_Uv; void main(){ float d=distance(v_Uv, vec2(.5,.5)); if(d>.5) discard; float mist=1.0-smoothstep(.12,.50,d); float grain=fract(sin(dot(v_Uv*91.7,vec2(12.9898,78.233)))*43758.5453); float a=(.25+grain*.75)*mist*u_Color.a; gl_FragColor=vec4(u_Color.rgb,a); }");
            positionAttrib = GLES20.glGetAttribLocation(program, "a_Position");
            uvAttrib = GLES20.glGetAttribLocation(program, "a_Uv");
            mvpUniform = GLES20.glGetUniformLocation(program, "u_Mvp");
            colorUniform = GLES20.glGetUniformLocation(program, "u_Color");
        }
        void draw(SprayMark mark, float[] viewProjectionMatrix) {
            mark.anchor.getPose().toMatrix(model, 0);
            Matrix.setIdentityM(scale, 0);
            Matrix.scaleM(scale, 0, mark.radius, mark.radius, mark.radius);
            Matrix.multiplyMM(tmp, 0, model, 0, scale, 0);
            Matrix.multiplyMM(mvp, 0, viewProjectionMatrix, 0, tmp, 0);
            GLES20.glUseProgram(program);
            GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvp, 0);
            GLES20.glUniform4fv(colorUniform, 1, mark.rgba, 0);
            vertices.position(0);
            GLES20.glVertexAttribPointer(positionAttrib, 3, GLES20.GL_FLOAT, false, 0, vertices);
            GLES20.glEnableVertexAttribArray(positionAttrib);
            uvs.position(0);
            GLES20.glVertexAttribPointer(uvAttrib, 2, GLES20.GL_FLOAT, false, 0, uvs);
            GLES20.glEnableVertexAttribArray(uvAttrib);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(positionAttrib);
            GLES20.glDisableVertexAttribArray(uvAttrib);
        }
    }

    private static FloatBuffer floatBuffer(float[] values) {
        ByteBuffer bb = ByteBuffer.allocateDirect(values.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(values);
        fb.position(0);
        return fb;
    }

    private static int loadShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("Shader error: " + log);
        }
        return shader;
    }

    private static int createProgram(String vertex, String fragment) {
        int v = loadShader(GLES20.GL_VERTEX_SHADER, vertex);
        int f = loadShader(GLES20.GL_FRAGMENT_SHADER, fragment);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, v);
        GLES20.glAttachShader(program, f);
        GLES20.glLinkProgram(program);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new RuntimeException("Program error: " + log);
        }
        return program;
    }
}
