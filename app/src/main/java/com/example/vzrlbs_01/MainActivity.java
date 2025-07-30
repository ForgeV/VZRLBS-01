package com.example.vzrlbs_01;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
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
import com.google.ar.core.CameraIntrinsics;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.ar.core.RecordingConfig;
import com.google.ar.core.RecordingStatus;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.PlaybackStatus;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.RecordingFailedException;
import com.google.ar.core.exceptions.SessionPausedException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;

import org.json.JSONObject;

import java.io.PrintWriter;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "ARCoreVIO";
    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private static final int REQUEST_PICK_DIRECTORY = 1001;
    private static final int REQUEST_PICK_MP4 = 1002;

    private Session session;
    private AppState appState = AppState.IDLE;
    private GLSurfaceView glSurfaceView;
    private MyRenderer renderer;
    private TextView statusText;
    private Uri saveUri;
    private List<VioData> vioDataList;
    private List<TouchData> touchDataList;
    private Handler handler = new Handler(Looper.getMainLooper());

    private enum AppState {
        IDLE,
        RECORDING,
        PLAYBACK
    }

    @SuppressLint("ClickableViewAccessibility")
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
        glSurfaceView.setOnTouchListener((v, event) -> {
            if (appState == AppState.RECORDING || appState == AppState.PLAYBACK) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                    float x = event.getX();
                    float y = event.getY();
                    long timestamp = renderer.latestFrameTimestamp;
                    touchDataList.add(new TouchData(timestamp, x, y));
                    Log.d(TAG, "Recorded touch: timestamp=" + timestamp + ", x=" + x + ", y=" + y);
                }
            }
            return true;
        });
        statusText = findViewById(R.id.status_text);
        Button startButton = findViewById(R.id.start_button);
        Button stopButton = findViewById(R.id.stop_button);
        Button extractButton = findViewById(R.id.extract_button);
        Button filesButton = findViewById(R.id.files_button);

        startButton.setOnClickListener(v -> startRecording());
        stopButton.setOnClickListener(v -> stopRecording());
        extractButton.setOnClickListener(v -> startPlayback());
        filesButton.setOnClickListener(v -> {
            if (saveUri != null) {
                Intent intent = new Intent(MainActivity.this, FileExplorerActivity.class);
                intent.setData(saveUri);
                startActivity(intent);
            } else {
                Toast.makeText(MainActivity.this, "Сначала выберите директорию для сохранения", Toast.LENGTH_SHORT).show();
            }
        });

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
        } else if (requestCode == REQUEST_PICK_MP4 && resultCode == RESULT_OK && data != null) {
            Uri mp4Uri = data.getData();
            if (mp4Uri != null) {
                try {
                    if (session != null) {
                        session.pause();
                        session.close();
                        session = null;
                        Log.d(TAG, "Session paused and closed before starting playback");
                    }
                    session = new Session(this);
                    Config config = new Config(session);
                    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        config.setDepthMode(Config.DepthMode.AUTOMATIC);
                    }
                    session.configure(config);
                    session.setDisplayGeometry(0, glSurfaceView.getWidth(), glSurfaceView.getHeight());
                    session.setPlaybackDatasetUri(mp4Uri);
                    session.setCameraTextureName(renderer.cameraTextureId);
                    session.resume();
                    appState = AppState.PLAYBACK;
                    vioDataList = new ArrayList<>();
                    statusText.setText("Статус: воспроизведение");
                    Toast.makeText(this, "Воспроизведение начато", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Log.e(TAG, "Ошибка при начале воспроизведения", e);
                    Toast.makeText(this, "Ошибка при начале воспроизведения: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private void requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Разрешение на камеру предоставлено", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Разрешение на камеру отклонено", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startRecording() {

        touchDataList = new ArrayList<>();
        if (appState != AppState.IDLE) {
            Toast.makeText(this, "Невозможно начать запись во время воспроизведения", Toast.LENGTH_SHORT).show();
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
                    if (session != null) {
                        session.pause();
                        session.close();
                        session = null;
                    }
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

                    String recordingFileName = "recording_" + timeStamp + ".mp4";
                    DocumentFile recordingFile = vzrlbsDir.createFile("video/mp4", recordingFileName);
                    if (recordingFile == null) {
                        Toast.makeText(this, "Не удалось создать файл для записи", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Uri recordingUri = recordingFile.getUri();

                    RecordingConfig recordingConfig = new RecordingConfig(session);
                    recordingConfig.setMp4DatasetUri(recordingUri);
                    recordingConfig.setAutoStopOnPause(true);

                    session.startRecording(recordingConfig);

                    RecordingStatus status = session.getRecordingStatus();
                    if (status != RecordingStatus.OK) {
                        Log.e(TAG, "Recording failed to start: " + status);
                        Toast.makeText(this, "Не удалось начать запись: " + status, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    session.resume();
                    Log.d(TAG, "Session resumed after starting recording");

                    glSurfaceView.queueEvent(() -> {
                        try {
                            Frame frame = session.update();
                            saveCameraParameters(frame);
                        } catch (Exception e) {
                            Log.e(TAG, "Error getting frame for camera parameters", e);
                        }
                    });

                    appState = AppState.RECORDING;
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
                } catch (RecordingFailedException e) {
                    Log.e(TAG, "Ошибка при начале записи", e);
                    Toast.makeText(this, "Ошибка при начале записи: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Log.e(TAG, "Неизвестная ошибка", e);
                    Toast.makeText(this, "Неизвестная ошибка: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "ARCore не поддерживается на этом устройстве", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void stopRecording() {

        saveTouchData();
        if (appState != AppState.RECORDING) {
            Toast.makeText(this, "Запись не идет", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            session.stopRecording();

            if (session != null) {
                try {
                    session.pause();
                    Log.d(TAG, "Session paused after stopping recording");
                } catch (Exception e) {
                    Log.e(TAG, "Ошибка при паузе сессии ARCore: " + e.getMessage(), e);
                }
            }

            appState = AppState.IDLE;
            statusText.setText("Статус: не записывается");
            Toast.makeText(this, "Запись остановлена", Toast.LENGTH_SHORT).show();
        } catch (RecordingFailedException e) {
            Log.e(TAG, "Ошибка при остановке записи", e);
            Toast.makeText(this, "Ошибка при остановке записи: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void startPlayback() {
        if (appState != AppState.IDLE) {
            Toast.makeText(this, "Невозможно начать воспроизведение во время записи или воспроизведения", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/mp4");
        startActivityForResult(intent, REQUEST_PICK_MP4);
    }

    private void stopPlayback() {
        if (session != null) {
            try {
                session.pause();
                Log.d(TAG, "Session paused in stopPlayback");
            } catch (Exception e) {
                Log.e(TAG, "Ошибка при паузе сессии в stopPlayback", e);
            }
        }
        Log.d(TAG, "Stopping playback, VIO data entries: " + (vioDataList != null ? vioDataList.size() : 0));
        appState = AppState.IDLE;
        statusText.setText("Статус: не записывается");

        if (vioDataList != null && !vioDataList.isEmpty()) {
            try {
                String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                DocumentFile documentFile = DocumentFile.fromTreeUri(this, saveUri);
                DocumentFile vzrlbsDir = documentFile.findFile("VZRLBS_01");
                if (vzrlbsDir == null) {
                    vzrlbsDir = documentFile.createDirectory("VZRLBS_01");
                }
                if (vzrlbsDir == null) {
                    throw new Exception("Не удалось создать директорию VZRLBS_01");
                }
                String csvFileName = "vio_data_" + timeStamp + ".csv";
                DocumentFile csvFile = vzrlbsDir.createFile("text/csv", csvFileName);
                if (csvFile == null) {
                    throw new Exception("Не удалось создать файл CSV");
                }
                Uri csvUri = csvFile.getUri();
                try (OutputStream os = getContentResolver().openOutputStream(csvUri)) {
                    PrintWriter writer = new PrintWriter(os);
                    writer.println("timestamp,tx,ty,tz,qx,qy,qz,qw");
                    for (VioData data : vioDataList) {
                        Pose pose = data.pose;
                        float[] translation = pose.getTranslation();
                        float[] rotation = pose.getRotationQuaternion();
                        writer.println(data.timestamp + "," + translation[0] + "," + translation[1] + "," + translation[2] + "," +
                                rotation[0] + "," + rotation[1] + "," + rotation[2] + "," + rotation[3]);
                    }
                    writer.flush();
                    Log.d(TAG, "Saved " + vioDataList.size() + " VIO data entries to " + csvFileName);
                }
                Toast.makeText(this, "Данные VIO сохранены в " + csvFileName, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "Ошибка при сохранении данных VIO", e);
                Toast.makeText(this, "Ошибка при сохранении данных VIO: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        } else {
            Log.e(TAG, "No VIO data to save, vioDataList is empty or null");
            Toast.makeText(this, "Нет данных VIO для сохранения", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (appState == AppState.RECORDING) {
            stopRecording();
        } else if (appState == AppState.PLAYBACK) {
            stopPlayback();
        }
        if (glSurfaceView != null) {
            glSurfaceView.onPause();
        }
        if (session != null) {
            try {
                session.pause();
                Log.d(TAG, "Session paused in onPause");
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
                Log.d(TAG, "Session resumed in onResume");
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

    private class MyRenderer implements GLSurfaceView.Renderer {
        private int cameraTextureId;
        private boolean isInitialized = false;
        private int program;
        private int positionHandle;
        private int texCoordHandle;
        private int textureHandle;
        private FloatBuffer vertexBuffer;
        private FloatBuffer texCoordBuffer;
        private volatile long latestFrameTimestamp;

        private static final String VERTEX_SHADER =
                "attribute vec4 aPosition;\n" +
                        "attribute vec2 aTexCoord;\n" +
                        "varying vec2 vTexCoord;\n" +
                        "void main() {\n" +
                        "  gl_Position = aPosition;\n" +
                        "  vTexCoord = aTexCoord;\n" +
                        "}\n";

        private static final String FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n" +
                        "precision mediump float;\n" +
                        "varying vec2 vTexCoord;\n" +
                        "uniform samplerExternalOES uTexture;\n" +
                        "void main() {\n" +
                        "  vec2 rotatedCoord = vec2(vTexCoord.y, 1.0 - vTexCoord.x);\n" +
                        "  gl_FragColor = texture2D(uTexture, rotatedCoord);\n" +
                        "}";

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            cameraTextureId = textures[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            if (program == 0) {
                Log.e(TAG, "Failed to create shader program");
                return;
            }
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
            texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord");
            textureHandle = GLES20.glGetUniformLocation(program, "uTexture");

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

        @Override
        public void onDrawFrame(GL10 gl) {
            if (!isInitialized || session == null) {
                Log.d(TAG, "Renderer not initialized or session is null");
                return;
            }

            if (appState == AppState.RECORDING) {
                try {
                    RecordingStatus status = session.getRecordingStatus();
                    Log.d(TAG, "Recording status in onDrawFrame: " + status);
                    Frame frame = session.update();
                    latestFrameTimestamp = frame.getTimestamp();
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
                    renderFrame();
                } catch (SessionPausedException e) {
                    Log.e(TAG, "Session is paused in onDrawFrame while recording", e);
                } catch (Exception e) {
                    Log.e(TAG, "Error in onDrawFrame during recording", e);
                }
            } else if (appState == AppState.PLAYBACK) {
                try {
                    PlaybackStatus status = session.getPlaybackStatus();
                    Log.d(TAG, "Playback status in onDrawFrame: " + status);
                    if (status == PlaybackStatus.OK) {
                        Frame frame = session.update();
                        Camera camera = frame.getCamera();
                        long timestamp = frame.getTimestamp();
                        if (camera.getTrackingState() == TrackingState.TRACKING && timestamp > 0) {
                            Pose pose = camera.getPose();
                            vioDataList.add(new VioData(timestamp, pose));
                            Log.d(TAG, "Added VIO data: timestamp=" + timestamp + ", position=[" + pose.getTranslation()[0] + "," + pose.getTranslation()[1] + "," + pose.getTranslation()[2] + "]");
                        } else {
                            Log.w(TAG, "Invalid frame: tracking state=" + camera.getTrackingState() + ", timestamp=" + timestamp);
                        }
                        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
                        renderFrame();
                    } else if (status == PlaybackStatus.FINISHED) {
                        Log.d(TAG, "Playback finished");
                        handler.post(() -> stopPlayback());
                    } else if (status == PlaybackStatus.IO_ERROR) {
                        Log.e(TAG, "Playback IO error");
                        handler.post(() -> {
                            Toast.makeText(MainActivity.this, "Ошибка воспроизведения: IO error", Toast.LENGTH_SHORT).show();
                            stopPlayback();
                        });
                    }
                } catch (SessionPausedException e) {
                    Log.e(TAG, "Session is paused in onDrawFrame during playback", e);
                } catch (Exception e) {
                    Log.e(TAG, "Error in onDrawFrame during playback", e);
                }
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
    private void saveTouchData() {
        if (touchDataList == null || touchDataList.isEmpty()) {
            Log.d(TAG, "No touch data to save");
            return;
        }

        try {
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            DocumentFile documentFile = DocumentFile.fromTreeUri(this, saveUri);
            DocumentFile vzrlbsDir = documentFile.findFile("VZRLBS_01");
            if (vzrlbsDir == null) {
                vzrlbsDir = documentFile.createDirectory("VZRLBS_01");
            }
            if (vzrlbsDir == null) {
                throw new Exception("Не удалось создать директорию VZRLBS_01");
            }
            String csvFileName = "touch_data_" + timeStamp + ".csv";
            DocumentFile csvFile = vzrlbsDir.createFile("text/csv", csvFileName);
            if (csvFile == null) {
                throw new Exception("Не удалось создать файл CSV");
            }
            Uri csvUri = csvFile.getUri();
            try (OutputStream os = getContentResolver().openOutputStream(csvUri)) {
                PrintWriter writer = new PrintWriter(os);
                writer.println("timestamp,x,y");
                for (TouchData data : touchDataList) {
                    writer.println(data.timestamp + "," + data.x + "," + data.y);
                }
                writer.flush();
                Log.d(TAG, "Saved " + touchDataList.size() + " touch data entries to " + csvFileName);
                Toast.makeText(this, "Данные касаний сохранены в " + csvFileName, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка при сохранении данных касаний", e);
            Toast.makeText(this, "Ошибка при сохранении данных касаний: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    private void saveCameraParameters(Frame frame) {
        if (frame == null) return;

        try {
            Camera camera = frame.getCamera();
            CameraIntrinsics intrinsics = camera.getImageIntrinsics();

            float[] focalLength = intrinsics.getFocalLength();
            float[] principalPoint = intrinsics.getPrincipalPoint();
            int[] imageDimensions = intrinsics.getImageDimensions();

            JSONObject json = new JSONObject();
            json.put("focal_length_x", focalLength[0]);
            json.put("focal_length_y", focalLength[1]);
            json.put("principal_point_x", principalPoint[0]);
            json.put("principal_point_y", principalPoint[1]);
            json.put("image_width", imageDimensions[0]);
            json.put("image_height", imageDimensions[1]);

            String jsonString = json.toString();

            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            DocumentFile documentFile = DocumentFile.fromTreeUri(this, saveUri);
            DocumentFile vzrlbsDir = documentFile.findFile("VZRLBS_01");
            if (vzrlbsDir == null) {
                vzrlbsDir = documentFile.createDirectory("VZRLBS_01");
            }
            if (vzrlbsDir == null) {
                throw new Exception("Не удалось создать директорию VZRLBS_01");
            }
            String jsonFileName = "camera_params_" + timeStamp + ".json";
            DocumentFile jsonFile = vzrlbsDir.createFile("application/json", jsonFileName);
            if (jsonFile == null) {
                throw new Exception("Не удалось создать JSON-файл");
            }
            Uri jsonUri = jsonFile.getUri();
            try (OutputStream os = getContentResolver().openOutputStream(jsonUri)) {
                os.write(jsonString.getBytes());
                os.flush();
                Log.d(TAG, "Camera parameters saved to " + jsonFileName);
                Toast.makeText(this, "Параметры камеры сохранены в " + jsonFileName, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка при сохранении параметров камеры", e);
            Toast.makeText(this, "Ошибка при сохранении параметров камеры: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    private static class TouchData {
        long timestamp;
        float x;
        float y;

        TouchData(long timestamp, float x, float y) {
            this.timestamp = timestamp;
            this.x = x;
            this.y = y;
        }
    }
    private static class VioData {
        long timestamp;
        Pose pose;
        /// //////////
        VioData(long timestamp, Pose pose) {
            this.timestamp = timestamp;
            this.pose = pose;
        }
    }
}