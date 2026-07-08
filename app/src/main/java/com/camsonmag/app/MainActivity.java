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
        styleButton = hudButton("TIPO", Color.rgb(255,228,94), Color.BLACK);
        photoButton = hudButton("FOTO", Color.rgb(92,232,255), Color.BLACK);
        sprayButton = hudButton("SPRAY", Color.rgb(255,79,216), Color.WHITE);
        Button clearButton = hudButton("LIMPIAR", Color.rgb(255,48,48), Color.WHITE);
        buttons.addView(colorButton, new LinearLayout.LayoutParams(0, dp(58), 1));
        buttons.addView(styleButton, new LinearLayout.LayoutParams(0, dp(58), 1));
        buttons.addView(photoButton, new LinearLayout.LayoutParams(0, dp(58), 1));
        buttons.addView(sprayButton, new LinearLayout.LayoutParams(0, dp(58), 1));
        buttons.addView(clearButton, new LinearLayout.LayoutParams(0, dp(58), 1));
        bottom.addView(buttons, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        bottomLp.bottomMargin = dp(8);
        hud.addView(bottom, bottomLp);

        colorButton.setOnClickListener(v -> {
            if (photoModeActive && activePhotoView != null) {
                activePhotoView.nextColor();
                colorButton.setText(activePhotoView.colorName());
                setStatus("Color del spray foto cambiado a " + activePhotoView.colorName() + ".");
            } else {
                renderer.nextColor();
                colorButton.setText(renderer.colorName());
            }
            vibrate(25);
        });
        styleButton.setOnClickListener(v -> {
            if (photoModeActive && activePhotoView != null) {
                activePhotoView.nextSprayStyle();
                styleButton.setText(activePhotoView.sprayStyleName());
                setStatus("Boquilla cambiada a " + activePhotoView.sprayStyleName() + ".");
                vibrate(20);
            } else {
                setStatus("Los sprays artísticos avanzados están en MODO FOTO.");
                vibrate(15);
            }
        });
        photoButton.setOnClickListener(v -> capturePhotoCanvas());
        clearButton.setOnClickListener(v -> {
            if (photoModeActive && activePhotoView != null) {
                activePhotoView.clearPaint();
                setStatus("Foto limpia. La niebla de pintura desapareció.");
            } else {
                surfaceView.queueEvent(() -> renderer.clearMarks());
                setStatus("Pared limpia. La lata borra sus fantasmas.");
            }
        });
        sprayButton.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) { startSpray(); return true; }
            if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) { stopSpray(); return true; }
            return true;
        });
        setSprayEnabled(false);
        return hud;
    }


    private interface BitmapCallback {
        void onBitmap(Bitmap bitmap);
    }

    private void capturePhotoCanvas() {
        if (renderer == null || surfaceView == null) return;
        setStatus("Congelando entorno para pintar en modo foto...");
        modeBadge.setText("FOTO");
        vibrate(25);
        surfaceView.queueEvent(() -> renderer.captureFrame(bitmap -> runOnUiThread(() -> showPhotoMode(bitmap))));
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
        activePhotoView = canvas;
        photoModeActive = true;
        overlay.addView(canvas, new FrameLayout.LayoutParams(-1, -1));

        TextView title = new TextView(this);
        title.setText("MODO FOTO");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setShadowLayer(8, 4, 4, Color.BLACK);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        titleLp.topMargin = dp(18);
        titleLp.leftMargin = dp(18);
        overlay.addView(title, titleLp);

        TextView hint = new TextView(this);
        hint.setText("Ahora apunta con giroscopio real. CENTRO recalibra la mira y SENS cambia la velocidad.");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(10), dp(8), dp(10), dp(8));
        hint.setBackground(pill(Color.argb(180,0,0,0), Color.argb(80,255,255,255), dp(18)));
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hintLp.topMargin = dp(74);
        hintLp.leftMargin = dp(18);
        hintLp.rightMargin = dp(18);
        overlay.addView(hint, hintLp);

        Button obra = hudButton("OBRA", Color.rgb(255,228,94), Color.BLACK);
        FrameLayout.LayoutParams obraLp = new FrameLayout.LayoutParams(dp(96), dp(52), Gravity.TOP | Gravity.END);
        obraLp.topMargin = dp(18);
        obraLp.rightMargin = dp(18);
        overlay.addView(obra, obraLp);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout rowTools = new LinearLayout(this);
        rowTools.setOrientation(LinearLayout.HORIZONTAL);
        rowTools.setGravity(Gravity.CENTER);
        LinearLayout rowActions = new LinearLayout(this);
        rowActions.setOrientation(LinearLayout.HORIZONTAL);
        rowActions.setGravity(Gravity.CENTER);
        Button save = hudButton("GUARDAR", Color.rgb(182,255,53), Color.BLACK);
        Button color = hudButton("COLOR", Color.rgb(92,232,255), Color.BLACK);
        Button style = hudButton("TIPO", Color.rgb(255,228,94), Color.BLACK);
        Button sens = hudButton("SENS", Color.rgb(245,238,224), Color.BLACK);
        Button axes = hudButton("EJES", Color.rgb(245,238,224), Color.BLACK);
        Button center = hudButton("CENTRO", Color.rgb(245,238,224), Color.BLACK);
        Button spray = hudButton("SPRAY", Color.rgb(255,79,216), Color.WHITE);
        Button clear = hudButton("LIMPIAR", Color.rgb(255,48,48), Color.WHITE);
        Button exit = hudButton("SALIR", Color.rgb(20,20,20), Color.WHITE);
        rowTools.addView(color, new LinearLayout.LayoutParams(0, dp(52), 1));
        rowTools.addView(style, new LinearLayout.LayoutParams(0, dp(52), 1));
        rowTools.addView(sens, new LinearLayout.LayoutParams(0, dp(52), 1));
        rowTools.addView(axes, new LinearLayout.LayoutParams(0, dp(52), 1));
        rowTools.addView(center, new LinearLayout.LayoutParams(0, dp(52), 1));
        rowActions.addView(save, new LinearLayout.LayoutParams(0, dp(58), 1));
        rowActions.addView(spray, new LinearLayout.LayoutParams(0, dp(58), 1));
        rowActions.addView(clear, new LinearLayout.LayoutParams(0, dp(58), 1));
        rowActions.addView(exit, new LinearLayout.LayoutParams(0, dp(58), 1));
        controls.addView(rowTools, new LinearLayout.LayoutParams(-1, -2));
        controls.addView(rowActions, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams controlsLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        controlsLp.bottomMargin = dp(10);
        overlay.addView(controls, controlsLp);
        color.setText(canvas.colorName());
        style.setText(canvas.sprayStyleName());
        sens.setText(canvas.sensitivityName());
        axes.setText(canvas.axisModeName());

        save.setOnClickListener(v -> {
            try {
                Bitmap out = canvas.exportBitmap();
                saveBitmapToDownloads(out, "camson-mag-foto-" + System.currentTimeMillis() + ".png");
                setStatus("Foto pintada guardada en Descargas/CamsonMag.");
                vibrate(50);
            } catch (Exception e) {
                setStatus("Error guardando foto: " + e.getMessage());
            }
        });
        color.setOnClickListener(v -> {
            canvas.nextColor();
            color.setText(canvas.colorName());
            colorButton.setText(canvas.colorName());
            vibrate(20);
        });
        style.setOnClickListener(v -> {
            canvas.nextSprayStyle();
            String styleName = canvas.sprayStyleName();
            style.setText(styleName);
            if (styleButton != null) styleButton.setText(styleName);
            setStatus("Boquilla de foto: " + styleName + ".");
            vibrate(20);
        });
        sens.setOnClickListener(v -> {
            canvas.nextSensitivity();
            sens.setText(canvas.sensitivityName());
            setStatus("Sensibilidad del giroscopio: " + canvas.sensitivityName() + ".");
            vibrate(18);
        });
        axes.setOnClickListener(v -> {
            canvas.nextAxisMode();
            axes.setText(canvas.axisModeName());
            setStatus("Ejes del giroscopio: " + canvas.axisModeName() + ". Si la mira va al revés, pulsa EJES otra vez.");
            vibrate(18);
        });
        center.setOnClickListener(v -> {
            canvas.centerAim();
            setStatus("Mira recentrada. Ahora esta posición es tu punto cómodo.");
            vibrate(30);
        });
        spray.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                startSpray();
                return true;
            }
            if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                stopSpray();
                return true;
            }
            return true;
        });
        clear.setOnClickListener(v -> {
            canvas.clearPaint();
            setStatus("Pintura de la foto limpiada.");
        });
        final boolean[] obraMode = new boolean[]{ false };
        obra.setOnClickListener(v -> {
            obraMode[0] = !obraMode[0];
            canvas.setStudioMode(obraMode[0]);
            title.setVisibility(obraMode[0] ? View.GONE : View.VISIBLE);
            hint.setVisibility(obraMode[0] ? View.GONE : View.VISIBLE);
            controls.setVisibility(obraMode[0] ? View.GONE : View.VISIBLE);
            obra.setText(obraMode[0] ? "UI" : "OBRA");
            modeBadge.setText(obraMode[0] ? "OBRA" : "FOTO");
            setStatus(obraMode[0] ? "Modo obra: interfaz fuera, lienzo dentro." : "Interfaz de modo foto restaurada.");
            vibrate(25);
        });
        exit.setOnClickListener(v -> closePhotoMode());

        photoOverlay = overlay;
        rootLayout.addView(photoOverlay, new FrameLayout.LayoutParams(-1, -1));
        colorButton.setText(canvas.colorName());
        if (styleButton != null) styleButton.setText(canvas.sprayStyleName());
        updatePaint(canvas.paintPercent());
        setStatus("Modo foto activo. Apunta con el giroscopio. Usa CENTRO para recentrar, SENS para velocidad y EJES si está invertido.");
    }

    private void closePhotoMode() {
        if (activePhotoView != null) activePhotoView.setSpraying(false);
        if (photoOverlay != null && rootLayout != null) {
            rootLayout.removeView(photoOverlay);
            photoOverlay = null;
        }
        activePhotoView = null;
        photoModeActive = false;
        colorButton.setText(renderer.colorName());
        if (styleButton != null) styleButton.setText("TIPO");
        updatePaint(100);
        modeBadge.setText(renderer != null && renderer.isMappingReady() ? "VOL = SPRAY" : "MAPEANDO");
        setStatus("Has vuelto al modo AR. Usa FOTO cuando quieras congelar la escena y pintar sin tracking.");
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
        if (sensorManager != null) {
            if (accelerometer != null) sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
            if (gyroscope != null) sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME);
        }
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
        if (photoModeActive && activePhotoView != null) {
            activePhotoView.setSpraying(true);
            modeBadge.setText("FOTO SPRAY");
            setStatus("Spray activo. Apunta con el giroscopio; usa CENTRO si la mira se escapa.");
            vibrate(18);
            return;
        }
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
        if (photoModeActive && activePhotoView != null) {
            activePhotoView.setSpraying(false);
            modeBadge.setText("FOTO");
            return;
        }
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
        if (photoModeActive && activePhotoView != null) {
            activePhotoView.reloadCan();
            updatePaint(activePhotoView.paintPercent());
            setStatus("LATA RECARGADA. En modo foto ya puedes seguir rociando la imagen.");
            vibrate(90);
            return;
        }
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


    private class PhotoSprayView extends View {
        private final Bitmap baseBitmap;
        private Bitmap strokesBitmap;
        private Canvas strokesCanvas;
        private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint crossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF dest = new RectF();

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

        void centerAim() {
            aimX = getWidth() > 0 ? getWidth() * 0.5f : aimX;
            aimY = getHeight() > 0 ? getHeight() * 0.5f : aimY;
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

        String colorName() { return colorNames[colorIndex]; }

        void nextSprayStyle() {
            sprayStyleIndex = (sprayStyleIndex + 1) % sprayStyles.length;
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

            float margin = dp(24);
            targetAimX = clamp(targetAimX, margin, getWidth() - margin);
            targetAimY = clamp(targetAimY, margin, getHeight() - margin);

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
            targetAimX = getWidth() * (0.5f + nx * 0.34f);
            targetAimY = getHeight() * (0.5f + ny * 0.34f);
            targetAimX = clamp(targetAimX, dp(24), getWidth() - dp(24));
            targetAimY = clamp(targetAimY, dp(24), getHeight() - dp(24));
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
                if (aimX < 0 || aimY < 0) {
                    aimX = w * 0.5f;
                    aimY = h * 0.5f;
                    targetAimX = aimX;
                    targetAimY = aimY;
                }
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            dest.set(0, 0, getWidth(), getHeight());
            canvas.drawBitmap(baseBitmap, null, dest, imagePaint);
            if (strokesBitmap != null) canvas.drawBitmap(strokesBitmap, 0, 0, null);
            drawCrosshair(canvas);
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
