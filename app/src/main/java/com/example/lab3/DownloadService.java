package com.example.lab3;

import android.app.*;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.TaskStackBuilder;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.*;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;

public class DownloadService extends Service {

    private static final String TAG = "DownloadService";
    private static final String CHANNEL_ID = "download_channel";
    private static final int NOTIFICATION_ID = 1;
    public static final String EXTRA_URL = "download_url";

    private HandlerThread handlerThread;
    private Handler handler;


    // LiveData do przesyłania postępu pobierania (np. do UI)
    private final MutableLiveData<ProgressEvent> progressLiveData = new MutableLiveData<>(null);
    private final IBinder binder = new DownloadBinder();

    private int lastPercent = 0;

    //zwracanie postępu pobierania
    public class DownloadBinder extends Binder {
        public LiveData<ProgressEvent> getProgressEvent() {
            return progressLiveData;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handlerThread = new HandlerThread("DownloadServiceThread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Download Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows download progress");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String fileUrl = intent.getStringExtra(EXTRA_URL);
        handler.post(() -> downloadFile(fileUrl));
        return START_NOT_STICKY;
    }

    //pobieranie pliku
    private void downloadFile(String fileUrl) {
        startForeground(NOTIFICATION_ID, createNotificationWithIntent("Starting download...", 0, 100));

        HttpsURLConnection connection = null;
        OutputStream outputStream = null;
        InputStream inputStream = null;

        try {
            URL url = new URL(fileUrl);
            connection = (HttpsURLConnection) url.openConnection();
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            connection.connect();

            String mimeType = connection.getContentType();
            String fileName = "downloaded_file_" + System.currentTimeMillis() + ".bin";
            int totalBytes = connection.getContentLength();

            Uri fileUri = null;

            //zapisywanie pliku
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver resolver = getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                fileUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                outputStream = resolver.openOutputStream(fileUri);
            } else {
                File path = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName);
                if (path.exists()) path.delete();
                outputStream = new FileOutputStream(path);
            }

            inputStream = new BufferedInputStream(connection.getInputStream());
            byte[] buffer = new byte[4096];
            int bytesRead;
            int totalRead = 0;

            //pobieranie
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                totalRead += bytesRead;

                Log.i(TAG, "Downloaded: " + totalRead + " / " + totalBytes);
                sendMessagesAndUpdateNotification(totalRead, totalBytes, ProgressEvent.IN_PROGRESS);
            }

            //skonczenie pobierania
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && fileUri != null) {
                ContentValues finish = new ContentValues();
                finish.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(fileUri, finish, null, null);
            }

            sendMessagesAndUpdateNotification(totalRead, totalBytes, ProgressEvent.OK);
            sendFinishedNotification("Download complete");

        } catch (Exception e) {
            Log.e(TAG, "Download error", e);
            sendMessagesAndUpdateNotification(0, 0, ProgressEvent.ERROR);
            sendFinishedNotification("Download failed");

        } finally {
            try {
                if (outputStream != null) outputStream.close();
                if (inputStream != null) inputStream.close();
                if (connection != null) connection.disconnect();
            } catch (IOException ignored) {}

            stopForeground(true);
            stopSelf();
        }
    }

    //wysłanie postępu i aktualizacja powiadomienia
    private void sendMessagesAndUpdateNotification(int downloaded, int totalBytes, int resultCode) {
        int percent = (totalBytes > 0) ? (downloaded * 100 / totalBytes) : 0;

        if (percent - lastPercent >= 3 || resultCode != ProgressEvent.IN_PROGRESS) {
            lastPercent = percent;

            String content = "Downloaded " + downloaded + " / " + totalBytes;
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            manager.notify(NOTIFICATION_ID, createNotificationWithIntent(content, downloaded, totalBytes));

            progressLiveData.postValue(new ProgressEvent(downloaded, totalBytes, resultCode));
        }
    }

    //dynamiczne powiadomienie z postępem
    private Notification createNotificationWithIntent(String contentText, int downloaded, int total) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("progress_event", new ProgressEvent(downloaded, total, ProgressEvent.IN_PROGRESS));

        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
        stackBuilder.addNextIntentWithParentStack(intent);
        PendingIntent pendingIntent = stackBuilder.getPendingIntent(0,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        int percent = (total > 0) ? (downloaded * 100 / total) : 0;

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Downloading...")
                .setContentText(contentText + " (" + percent + "%)")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(100, percent, false)
                .setOnlyAlertOnce(true)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    //powiadomienia o sukcesie lub blad
    private void sendFinishedNotification(String message) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("progress_event", new ProgressEvent(100, 100, ProgressEvent.OK));

        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
        stackBuilder.addNextIntentWithParentStack(intent);
        PendingIntent pendingIntent = stackBuilder.getPendingIntent(0,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Download finished")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setProgress(0, 0, false)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification);
    }
}
