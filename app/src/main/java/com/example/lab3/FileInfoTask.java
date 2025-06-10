package com.example.lab3;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.URL;
import java.util.concurrent.*;

import javax.net.ssl.HttpsURLConnection;

public class FileInfoTask {
    private static final String TAG = "FileInfoTask";

    private final ExecutorService executorService;
    private final Handler mainHandler;
    private Future<FileInfo> future;

    public FileInfoTask() {
        //dwa rownolegle watki do przetwarzania
        executorService = Executors.newFixedThreadPool(2);
        mainHandler = new Handler(Looper.getMainLooper());
    }

    public interface ResultCallback {
        void onSuccess(FileInfo result);
        void onError(Throwable throwable);
    }

    //pobiera informacje o pliku
    public Future<FileInfo> executeTask(ResultCallback callback, String urlStr) {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }

        Callable<FileInfo> asyncTask = () -> {
            Log.d(TAG, "Task started for: " + urlStr);
            HttpsURLConnection connection = null;
            FileInfo fileInfo = null;

            try {
                URL url = new URL(urlStr);
                connection = (HttpsURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", "Mozilla/5.0");


                int size = connection.getContentLength();
                String type = connection.getContentType();
                fileInfo = new FileInfo(size, type);
                Log.d(TAG, "File size: " + size + ", Type: " + type);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }

            FileInfo finalFileInfo = fileInfo;
            mainHandler.post(() -> callback.onSuccess(finalFileInfo));

            return fileInfo;
        };

        future = executorService.submit(() -> {
            try {
                return asyncTask.call();
            } /*catch (Exception e) {
                Log.e(TAG, "Error during task", e); // <- to pokaże dokładny problem w Logcat
                mainHandler.post(() -> callback.onError(e));
                throw e;
            }*/

            catch (Exception e) {
                Log.e(TAG, "Exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                mainHandler.post(() -> callback.onError(e));
                throw e;
            }

        });


        return future;
    }

    //anulowanie zadania
    public void shutdown() {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
        executorService.shutdown();
    }
}
