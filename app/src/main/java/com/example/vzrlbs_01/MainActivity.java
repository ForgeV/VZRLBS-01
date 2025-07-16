package com.example.vzrlbs_01;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.GLES11Ext;
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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "ARCoreVIO";
    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 201;
    private static final int REQUEST_PICK_DIRECTORY = 1001;
    private static final long CAPTURE_INTERVAL = 1;

    private Session session;
    private boolean isRecording = false;
    private OutputStreamWriter writer;
    private GLSurfaceView glSurfaceView;
    private MyRenderer renderer;
    private MediaRecorder mediaRecorder;
    private TextView statusText;
    private Uri saveUri;
    private long lastCaptureTime = 0;
    private Surface recorderSurface;
    private android.opengl.EGLSurface recorderEglSurface;
    private android.opengl.EGLDisplay eglDisplay;
    private android.opengl.EGLContext eglContext;

    private class MyRenderer implements GLSurfaceView.Renderer {
        private int cameraTextureId;
        private boolean isInitialized = false;
        private int program;
        private int positionHandle;
        private int texCoordHandle;
        private int textureHandle;
        private FloatBuffer vertexBuffer;
        private FloatBuffer texCoordBuffer;

        // Vertex shader
        private static final String VERTEX_SHADER =
                "attribute vec4 aPosition;\n" +
                        "attribute vec2 aTexCoord;\n" +
                        "varying vec2 vTexCoord;\n" +
                        "void main() {\n" +
                        "  gl_Position = aPosition;\n" +
                        "  vTexCoord = aTexCoord;\n" +
                        "}\n";

        // Fragment shader with 90-degree rotation fix
        private static final String FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n" +
                        "precision mediump float;\n" +
                        "varying vec2 vTexCoord;\n" +
                        "uniform samplerExternalOES uTexture;\n" +
                        "void main() {\n" +
                        "  vec2 rotatedCoord = vec2(vTexCoord.y, 1.0 - vTexCoord.x);\n" + // Rotate 90 degrees
                        "  gl_FragColor = texture2D(uTexture, rotatedCoord);\n" +
                        "}";

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            // Initialize EGL handles
            eglDisplay = EGL14.eglGetCurrentDisplay();
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                Log.e(TAG, "No EGL display available");
                return;
            }
            eglContext = EGL14.eglGetCurrentContext();
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                Log.e(TAG, "No EGL context available");
                return;
            }

            // Create texture for camera
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            cameraTextureId = textures[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            // Create shader program
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            if (program == 0) {
                Log.e(TAG, "Failed to create shader program");
                return;
            }
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
            texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord");
            textureHandle = GLES20.glGetUniformLocation(program, "uTexture");

            // Set up vertex and texcoord buffers for a full-screen quad
            float[] vertices = { -1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f };
            float[] texCoords = { 0.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f };
            ByteBuffer bb = ByteBuffer.allocateDirect(vertices.length * 4);
            bb.order(ByteOrder.nativeOrder());
            vertexBuffer = bb.asFloatBuffer();
            vertexBuffer.put(vertices);
            vertexBuffer.position(0);

            ByteBuffer tb = ByteBuffer.allocateDirect(texCoords.length * 4);
            tb.order(ByteOrder.nativeOrder());
            texCoordBuffer = tb.asFloatBuffer();
            texCoordBuffer.put(texCoords);
            texCoordBuffer.position(0);

            if (session != null) {
                session.setCameraTextureName(cameraTextureId);
            }

            isInitialized = true;
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            GLES20.glViewport(0, 0, width, height);
            if (session != null) {
                session.setDisplayGeometry(0, width, height);
            }
        }

        @SuppressLint("DefaultLocale")
        @Override
        public void onDrawFrame(GL10 gl) {
            if (!isInitialized || session == null) return;
            try {
                Frame frame = session.update();
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
                renderFrame();

                // If recording, render to MediaRecorder surface
                if (isRecording && recorderEglSurface != null && recorderSurface != null && recorderSurface.isValid()) {
                    EGL14.eglMakeCurrent(eglDisplay, recorderEglSurface, recorderEglSurface, eglContext);
                    GLES20.glViewport(0, 0, 640, 480); // Match MediaRecorder video size
                    renderFrame();
                    EGL14.eglSwapBuffers(eglDisplay, recorderEglSurface);
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW), EGL14.eglGetCurrentSurface(EGL14.EGL_READ), eglContext);
                    GLES20.glViewport(0, 0, glSurfaceView.getWidth(), glSurfaceView.getHeight());
                }

                // VIO data saving (unchanged as per user request)
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

        private void renderFrame() {
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
            GLES20.glUniform1i(textureHandle, 0);
            GLES20.glEnableVertexAttribArray(positionHandle);
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
            GLES20.glEnableVertexAttribArray(texCoordHandle);
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(positionHandle);
            GLES20.glDisableVertexAttribArray(texCoordHandle);
        }

        private int createProgram(String vertexSource, String fragmentSource) {
            int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
            if (vertexShader == 0) return 0;
            int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
            if (fragmentShader == 0) return 0;
            int program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertexShader);
            GLES20.glAttachShader(program, fragmentShader);
            GLES20.glLinkProgram(program);
            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "Could not link program: " + GLES20.glGetProgramInfoLog(program));
                GLES20.glDeleteProgram(program);
                return 0;
            }
            return program;
        }

        private int loadShader(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                Log.e(TAG, "Could not compile shader: " + GLES20.glGetShaderInfoLog(shader));
                GLES20.glDeleteShader(shader);
                return 0;
            }
            return shader;
        }
    }

    private Handler handler = new Handler(Looper.getMainLooper());

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
                    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        config.setDepthMode(Config.DepthMode.AUTOMATIC);
                    } else {
                        Log.w(TAG, "Depth mode not supported, using default mode");
                    }
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

                    // VIO data file creation (unchanged)
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
                    if (pfd == null) {
                        Toast.makeText(this, "Не удалось открыть файл видео", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    mediaRecorder = new MediaRecorder();
                    mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
                    mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                    mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                    mediaRecorder.setVideoSize(640, 480);
                    mediaRecorder.setVideoFrameRate(30);
                    mediaRecorder.setOutputFile(pfd.getFileDescriptor());
                    try {
                        mediaRecorder.prepare();
                    } catch (IOException e) {
                        Log.e(TAG, "MediaRecorder prepare failed", e);
                        Toast.makeText(this, "Ошибка подготовки MediaRecorder: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        return;
                    }

                    recorderSurface = mediaRecorder.getSurface();
                    if (recorderSurface == null || !recorderSurface.isValid()) {
                        Log.e(TAG, "MediaRecorder surface is invalid or null");
                        Toast.makeText(this, "Ошибка: поверхность записи недоступна", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // Create EGL surface for recording
                    int[] configAttribs = {
                            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                            EGL14.EGL_RED_SIZE, 8,
                            EGL14.EGL_GREEN_SIZE, 8,
                            EGL14.EGL_BLUE_SIZE, 8,
                            EGL14.EGL_ALPHA_SIZE, 8,
                            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                            EGL14.EGL_NONE
                    };
                    android.opengl.EGLConfig[] configs = new android.opengl.EGLConfig[1];
                    int[] numConfigs = new int[1];
                    EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0);
                    if (numConfigs[0] == 0) {
                        Log.e(TAG, "No valid EGL config found");
                        Toast.makeText(this, "Ошибка: нет подходящей конфигурации EGL", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    recorderEglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], recorderSurface, new int[]{EGL14.EGL_NONE}, 0);
                    if (recorderEglSurface == EGL14.EGL_NO_SURFACE) {
                        int error = EGL14.eglGetError();
                        Log.e(TAG, "Failed to create EGL surface for recording: " + error);
                        Toast.makeText(this, "Ошибка создания EGL поверхности: " + error, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    try {
                        mediaRecorder.start();
                    } catch (Exception e) {
                        Log.e(TAG, "MediaRecorder start failed", e);
                        Toast.makeText(this, "Ошибка запуска MediaRecorder: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
            // Stop VIO data writing (unchanged)
            if (writer != null) {
                writer.flush();
                writer.close();
                writer = null;
            }

            // Stop MediaRecorder
            if (mediaRecorder != null) {
                try {
                    mediaRecorder.stop();
                } catch (Exception e) {
                    Log.e(TAG, "Ошибка при остановке MediaRecorder: " + e.getMessage(), e);
                }
                mediaRecorder.release();
                mediaRecorder = null;
            }

            // Clean up EGL surface
            if (recorderEglSurface != null && eglDisplay != null) {
                EGL14.eglDestroySurface(eglDisplay, recorderEglSurface);
                recorderEglSurface = null;
            }

            // Release recorder surface
            if (recorderSurface != null) {
                recorderSurface.release();
                recorderSurface = null;
            }

            // Pause ARCore session
            if (session != null) {
                try {
                    session.pause();
                } catch (Exception e) {
                    Log.e(TAG, "Ошибка при паузе сессии ARCore: " + e.getMessage(), e);
                }
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
        if (recorderEglSurface != null && eglDisplay != null) {
            EGL14.eglDestroySurface(eglDisplay, recorderEglSurface);
            recorderEglSurface = null;
        }
        if (recorderSurface != null) {
            recorderSurface.release();
            recorderSurface = null;
        }
    }
}