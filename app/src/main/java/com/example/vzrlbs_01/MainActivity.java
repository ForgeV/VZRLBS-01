package com.example.vzrlbs_01;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Surface;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "ARCoreVIO";
    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 201;
    private static final int REQUEST_PICK_DIRECTORY = 1001;
    private static final long CAPTURE_INTERVAL = 100_000_000;

    private Session session;
    private boolean isRecording = false;
    private OutputStreamWriter writer;
    private GLSurfaceView glSurfaceView;
    private MyRenderer renderer;
    private MediaRecorder mediaRecorder;
    private TextView statusText;
    private Uri saveUri;
    private long lastCaptureTime = 0;

    private class MyRenderer implements GLSurfaceView.Renderer {
        private int cameraTextureId;
        private boolean isInitialized = false;

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            cameraTextureId = textures[0];
            isInitialized = true;
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            GLES20.glViewport(0, 0, width, height);
            if (session != null) {
                session.setDisplayGeometry(0, width, height);
            }
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            if (!isInitialized || session == null) return;
            try {
                Frame frame = session.update();
                if (isRecording && writer != null) {
                    long currentTime = System.nanoTime();
                    if (currentTime - lastCaptureTime >= CAPTURE_INTERVAL) {
                        lastCaptureTime = currentTime;
                        Camera camera = frame.getCamera();
                        if (camera.getTrackingState() == TrackingState.TRACKING) {
                            Pose pose = camera.getPose();
                            long timestamp = frame.getTimestamp();
                            float[] translation = pose.getTranslation();
                            float[] rotation = pose.getRotationQuaternion();
                            writer.write(String.format("%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f\n",
                                    timestamp, translation[0], translation[1], translation[2],
                                    rotation[0], rotation[1], rotation[2], rotation[3]));
                            writer.flush();
                            Log.d(TAG, "VIO data captured at timestamp: " + timestamp);
                        } else {
                            Log.w(TAG, "Camera not tracking, state: " + camera.getTrackingState());
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in onDrawFrame", e);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        glSurfaceView = findViewById(R.id.gl_surface_view);
        glSurfaceView.setPreserveEGLContextOnPause(true);
        glSurfaceView.setEGLContextClientVersion(2);
        renderer = new MyRenderer();
        glSurfaceView.setRenderer(renderer);
        glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        statusText = findViewById(R.id.status_text);
        Button startButton = findViewById(R.id.start_button);
        Button stopButton = findViewById(R.id.stop_button);

        startButton.setOnClickListener(v -> startRecording());
        stopButton.setOnClickListener(v -> stopRecording());

        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        String uriString = prefs.getString("save_uri", null);
        if (uriString != null) {
            saveUri = Uri.parse(uriString);
        } else {
            pickSaveDirectory();
        }

        requestPermissions();
    }

    private void pickSaveDirectory() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(intent, REQUEST_PICK_DIRECTORY);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_DIRECTORY && resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri != null) {
                getContentResolver().takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
                prefs.edit().putString("save_uri", treeUri.toString()).apply();
                saveUri = treeUri;
                Toast.makeText(this, "Директория для сохранения выбрана", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Не удалось выбрать директорию", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, REQUEST_CAMERA_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            boolean cameraGranted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            boolean audioGranted = grantResults.length > 1 && grantResults[1] == PackageManager.PERMISSION_GRANTED;
            if (cameraGranted) {
                Toast.makeText(this, "Разрешение на камеру предоставлено", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Разрешение на камеру отклонено", Toast.LENGTH_SHORT).show();
            }
            if (!audioGranted) {
                Toast.makeText(this, "Разрешение на запись звука отклонено (звук необязателен)", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startRecording() {
        if (isRecording) {
            Toast.makeText(this, "Запись уже идет", Toast.LENGTH_SHORT).show();
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Требуется разрешение на камеру", Toast.LENGTH_SHORT).show();
            return;
        }

        if (saveUri == null) {
            Toast.makeText(this, "Пожалуйста, выберите директорию для сохранения", Toast.LENGTH_SHORT).show();
            pickSaveDirectory();
            return;
        }

        ArCoreApk.getInstance().checkAvailabilityAsync(this, availability -> {
            if (availability.isSupported()) {
                try {
                    session = new Session(this);
                    Config config = new Config(session);
                    session.configure(config);
                    session.setCameraTextureName(renderer.cameraTextureId);
                    session.setDisplayGeometry(0, glSurfaceView.getWidth(), glSurfaceView.getHeight());
                    session.resume();

                    String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                    DocumentFile documentFile = DocumentFile.fromTreeUri(this, saveUri);
                    DocumentFile vzrlbsDir = documentFile.findFile("VZRLBS_01");
                    if (vzrlbsDir == null) {
                        vzrlbsDir = documentFile.createDirectory("VZRLBS_01");
                    }
                    if (vzrlbsDir == null) {
                        Toast.makeText(this, "Не удалось создать директорию VZRLBS_01", Toast.LENGTH_SHORT).show();
                        return;
                    }


                    String vioFileName = "vio_data_" + timeStamp + ".csv";
                    DocumentFile vioFile = vzrlbsDir.createFile("text/csv", vioFileName);
                    if (vioFile == null) {
                        Toast.makeText(this, "Не удалось создать файл VIO", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Uri vioUri = vioFile.getUri();
                    OutputStream vioOutputStream = getContentResolver().openOutputStream(vioUri);
                    if (vioOutputStream == null) {
                        Toast.makeText(this, "Не удалось открыть поток для VIO данных", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    writer = new OutputStreamWriter(vioOutputStream);
                    writer.write("timestamp,tx,ty,tz,qx,qy,qz,qw\n");

                    // Setup video recording
                    String videoFileName = "video_" + timeStamp + ".mp4";
                    DocumentFile videoFile = vzrlbsDir.createFile("video/mp4", videoFileName);
                    if (videoFile == null) {
                        Toast.makeText(this, "Не удалось создать файл видео", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Uri videoUri = videoFile.getUri();
                    ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(videoUri, "w");
                    if (pfd != null) {
                        mediaRecorder = new MediaRecorder();
                        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                        mediaRecorder.setVideoSize(640, 480);
                        mediaRecorder.setVideoFrameRate(30);
                        mediaRecorder.setOutputFile(pfd.getFileDescriptor());
                        mediaRecorder.prepare();
                        mediaRecorder.start();
                    } else {
                        Toast.makeText(this, "Не удалось открыть файл видео", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    isRecording = true;
                    lastCaptureTime = System.nanoTime();
                    statusText.setText("Статус: запись");
                    Toast.makeText(this, "Запись начата в Documents/VZRLBS_01", Toast.LENGTH_SHORT).show();
                } catch (UnavailableArcoreNotInstalledException e) {
                    Log.e(TAG, "ARCore не установлен", e);
                    Toast.makeText(this, "ARCore не установлен. Установите Google Play Services for AR.", Toast.LENGTH_LONG).show();
                } catch (UnavailableDeviceNotCompatibleException e) {
                    Log.e(TAG, "Устройство не совместимо с ARCore", e);
                    Toast.makeText(this, "Устройство не поддерживает ARCore", Toast.LENGTH_LONG).show();
                } catch (UnavailableSdkTooOldException e) {
                    Log.e(TAG, "ARCore SDK устарел", e);
                    Toast.makeText(this, "Приложение требует новейшую версию ARCore", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Log.e(TAG, "Ошибка при начале записи", e);
                    Toast.makeText(this, "Ошибка при начале записи: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "ARCore не поддерживается на этом устройстве", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void stopRecording() {
        if (!isRecording) {
            Toast.makeText(this, "Запись не идет", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            if (writer != null) {
                writer.flush();
                writer.close();
                writer = null;
            }
            if (mediaRecorder != null) {
                try {
                    mediaRecorder.stop();
                } catch (Exception e) {
                    Log.e(TAG, "Ошибка при остановке MediaRecorder: " + e.getMessage(), e);
                }
                mediaRecorder.release();
                mediaRecorder = null;
            }
            if (session != null) {
                session.pause();
            }
            isRecording = false;
            statusText.setText("Статус: не записывается");
            Toast.makeText(this, "Запись остановлена", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Log.e(TAG, "Ошибка при остановке записи", e);
            Toast.makeText(this, "Ошибка при остановке записи: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isRecording) {
            stopRecording();
        }
        if (glSurfaceView != null) {
            glSurfaceView.onPause();
        }
        if (session != null) {
            try {
                session.pause();
            } catch (Exception e) {
                Log.e(TAG, "Ошибка при паузе сессии", e);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (session != null) {
            try {
                session.resume();
            } catch (CameraNotAvailableException e) {
                Log.e(TAG, "Камера недоступна", e);
                Toast.makeText(this, "Камера недоступна", Toast.LENGTH_SHORT).show();
            }
        }
        if (glSurfaceView != null) {
            glSurfaceView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (session != null) {
            session.close();
            session = null;
        }
    }
}