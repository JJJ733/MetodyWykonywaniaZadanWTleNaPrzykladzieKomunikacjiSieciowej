package com.example.lab3;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.lifecycle.LiveData;

public class MainActivity extends AppCompatActivity {

    private EditText urlEditText;
    private TextView fileSizeText, fileTypeText, bytesDownloadedText;
    private FileInfoTask fileInfoTask;
    private Button downloadButton;
    private ProgressBar progressBar;

    private static final String TAG = "MainActivity";
    private static final int PERMISSIONS_REQUEST_CODE = 123;

    private boolean serviceBound = false;
    private LiveData<ProgressEvent> progressEventLiveData;
    private int lastProgress = 0;
    private int lastTotal = 1;

    // NOWE POLA do zapisu rozmiaru i typu
    private String lastFileSize = "";
    private String lastFileType = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        urlEditText = findViewById(R.id.urlEditText);
        fileSizeText = findViewById(R.id.fileSizeText);
        fileTypeText = findViewById(R.id.fileTypeText);
        bytesDownloadedText = findViewById(R.id.bytesDownloadedText);
        downloadButton = findViewById(R.id.downloadButton);
        Button getInfoButton = findViewById(R.id.getInfoButton);
        progressBar = findViewById(R.id.progressBar);

        fileInfoTask = new FileInfoTask();

        // Bind do usługi
        Intent serviceIntent = new Intent(this, DownloadService.class);
        bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE);

        // sprawdzanie powiadomienia
        ProgressEvent restored = getIntent().getParcelableExtra("progress_event");
        if (restored != null) updateProgress(restored);

        // Get Info (obsluga przycisku)
        getInfoButton.setOnClickListener(v -> {
            String url = urlEditText.getText().toString().trim();
            if (!url.startsWith("https://")) {
                Toast.makeText(MainActivity.this, "URL must start with https://", Toast.LENGTH_SHORT).show();
                return;
            }

            fileInfoTask.executeTask(new FileInfoTask.ResultCallback() {
                @Override
                public void onSuccess(FileInfo result) {

                    lastFileSize = String.valueOf(result.getSize());
                    lastFileType = result.getType();

                    fileSizeText.setText("File size: " + lastFileSize);
                    fileTypeText.setText("File type: " + lastFileType);
                    bytesDownloadedText.setText("Bytes downloaded: 0");
                }

                @Override
                public void onError(Throwable throwable) {
                    Toast.makeText(MainActivity.this, "Error fetching info", Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "Fetching error", throwable);
                }
            }, url);
        });

        // pobieranie
        downloadButton.setOnClickListener(v -> {
            String url = urlEditText.getText().toString().trim();
            if (TextUtils.isEmpty(url) || !url.startsWith("https://")) {
                Toast.makeText(this, "Invalid URL", Toast.LENGTH_SHORT).show();
                return;
            }

            //sprawdzanie uprawnień
            String requiredPermission = getRequiredPermission();
            if (ActivityCompat.checkSelfPermission(this, requiredPermission) == PackageManager.PERMISSION_GRANTED) {
                startDownloadService(url);
            } else {
                ActivityCompat.requestPermissions(this, new String[]{requiredPermission}, PERMISSIONS_REQUEST_CODE);
            }
        });
    }

    private void startDownloadService(String url) {
        Intent intent = new Intent(this, DownloadService.class);
        intent.putExtra(DownloadService.EXTRA_URL, url);
        startService(intent);
    }

    private static String getRequiredPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return Manifest.permission.POST_NOTIFICATIONS;
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Manifest.permission.WRITE_EXTERNAL_STORAGE;
        } else {
            return Manifest.permission.FOREGROUND_SERVICE;
        }
    }

    //prośba o uprawnienie
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST_CODE && grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            downloadButton.performClick();
        } else {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show();
        }
    }

    // polaczanie z pobieraniem i obserwacja paska
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            serviceBound = true;
            DownloadService.DownloadBinder binder = (DownloadService.DownloadBinder) service;
            progressEventLiveData = binder.getProgressEvent();
            progressEventLiveData.observe(MainActivity.this, MainActivity.this::updateProgress);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (progressEventLiveData != null)
                progressEventLiveData.removeObservers(MainActivity.this);
            serviceBound = false;
        }
    };

    //  Zapis stanu
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable("saved_progress", new ProgressEvent(lastProgress, lastTotal, ProgressEvent.IN_PROGRESS));
        outState.putString("file_size", lastFileSize);
        outState.putString("file_type", lastFileType);
    }

    // Odtwarzanie stanu po obrocie
    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);

        // Postęp pobierania
        ProgressEvent restored = savedInstanceState.getParcelable("saved_progress");
        if (restored != null) updateProgress(restored);

        // Rozmiar i typ pliku
        lastFileSize = savedInstanceState.getString("file_size", "");
        lastFileType = savedInstanceState.getString("file_type", "");
        fileSizeText.setText("File size: " + lastFileSize);
        fileTypeText.setText("File type: " + lastFileType);
    }

    private void updateProgress(ProgressEvent event) {
        if (event != null) {
            progressBar.setMax(event.total);
            progressBar.setProgress(event.progress);
            lastProgress = event.progress;
            lastTotal = event.total;

            bytesDownloadedText.setText("Bytes downloaded: " + event.progress + " / " + event.total);

            if (event.result == ProgressEvent.OK) {
                Toast.makeText(this, "Download finished!", Toast.LENGTH_SHORT).show();
            } else if (event.result == ProgressEvent.ERROR) {
                Toast.makeText(this, "Download failed.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (serviceBound) unbindService(serviceConnection);
        if (fileInfoTask != null) fileInfoTask.shutdown();
    }
}
