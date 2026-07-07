package com.camsonmag.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Build;
import android.os.Bundle;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MainActivity extends Activity implements SensorEventListener {
    private GLSurfaceView surfaceView;
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
    private Button sprayButton;

    private SensorManager sensorManager;
    private Sensor accelerometer;
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

        FrameLayout root = new FrameLayout(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(makeHud(), new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        installRequested = false;
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    private View makeHud() {
        FrameLayout hud = new FrameLayout(this);
        hud.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView logo = new TextView(this);
        logo.setText("CAMSON\nMAG");
        logo.setTextColor(Color.WHITE);
        logo.setTextSize(42);
        logo.setLineSpacing(-10, .9f);
        logo.setTypeface(Typeface.DEFAULT_BOLD);
        logo.setShadowLayer(8, 5, 5, Color.BLACK);
        FrameLayout.LayoutParams logoLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        logoLp.topMargin = dp(8);
        logoLp.leftMargin = dp(8);
        hud.addView(logo, logoLp);

        modeBadge = new TextView(this);
        modeBadge.setText("MAPEANDO");
        modeBadge.setTextColor(Color.rgb(182,255,53));
        modeBadge.setTextSize(14);
        modeBadge.setTypeface(Typeface.DEFAULT_BOLD);
        modeBadge.setGravity(Gravity.CENTER);
        modeBadge.setPadding(dp(14), dp(8), dp(14), dp(8));
        modeBadge.setBackground(pill(Color.argb(210,0,0,0), Color.argb(70,255,255,255), dp(28)));
        FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        badgeLp.topMargin = dp(22);
        badgeLp.rightMargin = dp(8);
        hud.addView(modeBadge, badgeLp);

        crossView = new TextView(this);
        crossView.setText("+");
        crossView.setTextColor(Color.argb(210,255,255,255));
        crossView.setTextSize(50);
        crossView.setGravity(Gravity.CENTER);
        crossView.setBackground(pill(Color.argb(60,0,0,0), Color.argb(120,255,255,255), dp(44)));
        FrameLayout.LayoutParams crossLp = new FrameLayout.LayoutParams(dp(86), dp(86), Gravity.CENTER);
        hud.addView(crossView, crossLp);

        statusText = new TextView(this);
        statusText.setText("Mapeando entorno... mueve el móvil despacio y apunta a pared o suelo.");
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(12);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(dp(10), dp(8), dp(10), dp(8));
        statusText.setBackground(pill(Color.argb(185,0,0,0), Color.argb(70,255,255,255), dp(18)));
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        statusLp.topMargin = dp(118);
        statusLp.leftMargin = dp(24);
        statusLp.rightMargin = dp(24);
        hud.addView(statusText, statusLp);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(dp(8), dp(8), dp(8), dp(8));

        paintMeter = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        paintMeter.setMax(100);
        paintMeter.setProgress(100);
        bottom.addView(paintMeter, new LinearLayout.LayoutParams(-1, dp(22)));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        buttons.setPadding(0, dp(8), 0, 0);
        colorButton = hudButton("COLOR", Color.rgb(182,255,53), Color.BLACK);
        sprayButton = hudButton("SPRAY", Color.rgb(255,79,216), Color.WHITE);
        Button clearButton = hudButton("LIMPIAR", Color.rgb(255,48,48), Color.WHITE);
        buttons.addView(colorButton, new LinearLayout.LayoutParams(0, dp(58), 1));
        buttons.addView(sprayButton, new LinearLayout.LayoutParams(0, dp(58), 1));
        buttons.addView(clearButton, new LinearLayout.LayoutParams(0, dp(58), 1));
        bottom.addView(buttons, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        bottomLp.bottomMargin = dp(8);
        hud.addView(bottom, bottomLp);

        colorButton.setOnClickListener(v -> {
            renderer.nextColor();
            colorButton.setText(renderer.colorName());
            vibrate(25);
        });
        clearButton.setOnClickListener(v -> {
            surfaceView.queueEvent(() -> renderer.clearMarks());
            setStatus("Pared limpia. La lata borra sus fantasmas.");
        });
        sprayButton.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) { startSpray(); return true; }
            if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) { stopSpray(); return true; }
            return true;
        });
        setSprayEnabled(false);
        return hud;
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

    private GradientDrawable pill(int color, int stroke, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        g.setStroke(dp(2), stroke);
        return g;
    }

    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + .5f); }

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
        } catch (UnavailableArcoreNotInstalledException | UnavailableUserDeclinedInstallationException e) {
            setStatus("ARCore no está instalado. Instala Servicios de Google Play para RA.");
        } catch (UnavailableApkTooOldException e) {
            setStatus("Actualiza Servicios de Google Play para RA.");
        } catch (UnavailableSdkTooOldException e) {
            setStatus("Actualiza esta app: SDK AR demasiado antiguo.");
        } catch (UnavailableDeviceNotCompatibleException e) {
            setStatus("Este dispositivo no aparece como compatible con ARCore.");
        } catch (CameraNotAvailableException e) {
            setStatus("La cámara está ocupada. Cierra otras apps y reabre Camson Mag.");
        } catch (Exception e) {
            setStatus("No se pudo iniciar AR: " + e.getMessage());
        }
        if (sensorManager != null && accelerometer != null) sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    protected void onPause() {
        super.onPause();
        surfaceView.onPause();
        if (session != null) session.pause();
        if (sensorManager != null) sensorManager.unregisterListener(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
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
            if (!hasCameraPermission) setStatus("Permiso de cámara denegado.");
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
        if (!renderer.isMappingReady()) {
            renderer.setSpraying(false);
            setStatus(renderer.mappingHint());
            modeBadge.setText("MAPEANDO");
            vibrate(18);
            return;
        }
        renderer.setSpraying(true);
        modeBadge.setText("PSSSSHHH");
        vibrate(18);
    }

    private void stopSpray() {
        renderer.setSpraying(false);
        modeBadge.setText(renderer.isMappingReady() ? "VOL = SPRAY" : "MAPEANDO");
    }

    private void setStatus(String text) {
        runOnUiThread(() -> statusText.setText(text));
    }

    private void updatePaint(int progress) {
        runOnUiThread(() -> paintMeter.setProgress(progress));
    }

    private void setSprayEnabled(boolean enabled) {
        runOnUiThread(() -> {
            if (sprayButton != null) {
                sprayButton.setEnabled(enabled);
                sprayButton.setAlpha(enabled ? 1f : 0.55f);
                sprayButton.setText(enabled ? "SPRAY" : "SCAN");
            }
        });
    }

    private void updateMappingUi(int percent, boolean ready, String message) {
        runOnUiThread(() -> {
            statusText.setText(message);
            if (ready) {
                modeBadge.setText("VOL = SPRAY");
                if (crossView != null) crossView.setTextColor(Color.rgb(182,255,53));
                setSprayEnabled(true);
            } else {
                modeBadge.setText("MAP " + percent + "%");
                if (crossView != null) {
                    crossView.setTextColor(percent >= 70 ? Color.rgb(255,228,94) : Color.argb(210,255,255,255));
                }
                setSprayEnabled(false);
            }
        });
    }

    private void reloadCan() {
        renderer.reloadCan();
        updatePaint(100);
        if (renderer.isMappingReady()) {
            setStatus("LATA RECARGADA. Listo para pintar.");
        } else {
            setStatus("LATA RECARGADA. Sigue mapeando el entorno.");
        }
        vibrate(90);
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
        float x = event.values[0], y = event.values[1], z = event.values[2];
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
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

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
