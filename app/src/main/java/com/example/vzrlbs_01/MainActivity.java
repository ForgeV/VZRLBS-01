package com.example.vzrlbs_01;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "ARCoreVIO";
    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private static final int REQUEST_WRITE_STORAGE_PERMISSION = 201;

    Session session;
    boolean isRecording = false;
    FileWriter writer;
    Handler handler = new Handler(Looper.getMainLooper());

    Runnable captureRunnable = new Runnable() {
        @SuppressLint("DefaultLocale")
        @Override
        public void run() {
            if (!isRecording) return;
            try {
                Frame frame = session.update();
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
                }
            } catch (Exception e) {
                Log.e(TAG, "Ошибка захвата VIO данных", e);
            }
            handler.postDelayed(this, 100);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button startButton = findViewById(R.id.start_button);
        Button stopButton = findViewById(R.id.stop_button);

        startButton.setOnClickListener(v -> startRecording());
        stopButton.setOnClickListener(v -> stopRecording());

        requestPermissions();
    }

    private void requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_WRITE_STORAGE_PERMISSION);
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
        } else if (requestCode == REQUEST_WRITE_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Разрешение на запись в хранилище предоставлено", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Разрешение на запись в хранилище отклонено", Toast.LENGTH_SHORT).show();
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Требуется разрешение на запись в хранилище", Toast.LENGTH_SHORT).show();
            return;
        }

        ArCoreApk.getInstance().checkAvailabilityAsync(this, availability -> {
            if (availability.isSupported()) {
                try {
                    try {
                        session = new Session(this);
                    } catch (UnavailableArcoreNotInstalledException e) {
                        Log.e(TAG, "ARCore не установлен", e);
                        Toast.makeText(this, "ARCore не установлен. Пожалуйста, установите Google Play Services for AR.", Toast.LENGTH_LONG).show();
                        return;
                    } catch (UnavailableApkTooOldException e) {
                        Log.e(TAG, "ARCore APK устарел", e);
                        Toast.makeText(this, "Пожалуйста, обновите Google Play Services for AR.", Toast.LENGTH_LONG).show();
                        return;
                    } catch (UnavailableSdkTooOldException e) {
                        Log.e(TAG, "ARCore SDK устарел", e);
                        Toast.makeText(this, "Это приложение требует более новой версии ARCore. Пожалуйста, обновите.", Toast.LENGTH_LONG).show();
                        return;
                    } catch (UnavailableDeviceNotCompatibleException e) {
                        Log.e(TAG, "Устройство не совместимо с ARCore", e);
                        Toast.makeText(this, "Это устройство не поддерживает ARCore.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    session.resume();

                    String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                    String fileName = "vio_data_" + timeStamp + ".csv";
                    File mediaDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "VZRLBS_01");
                    if (!mediaDir.exists()) {
                        mediaDir.mkdirs();
                    }
                    File file = new File(mediaDir, fileName);
                    writer = new FileWriter(file);
                    writer.write("timestamp,tx,ty,tz,qx,qy,qz,qw");

                    isRecording = true;
                    Toast.makeText(this, "Запись начата в " + file.getAbsolutePath(), Toast.LENGTH_SHORT).show();

                    handler.post(captureRunnable);
                } catch (CameraNotAvailableException | IOException e) {
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

        handler.removeCallbacks(captureRunnable);
        try {
            writer.close();
            if (session != null) {
                session.pause();
            }
            isRecording = false;
            Toast.makeText(this, "Запись остановлена", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Log.e(TAG, "Ошибка при остановке записи", e);
            Toast.makeText(this, "Ошибка при остановке записи", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isRecording) {
            stopRecording();
        }
        if (session != null) {
            session.pause();
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