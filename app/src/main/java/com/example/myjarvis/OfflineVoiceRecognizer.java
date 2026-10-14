package com.example.myjarvis;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.StorageService;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public class OfflineVoiceRecognizer {

    private static final String TAG = "OfflineVoiceRecognizer";
    private static final int SAMPLE_RATE = 16000;

    public interface Listener {
        void onResult(String text);

        void onError(String message);
    }

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final StringBuilder transcript = new StringBuilder();
    private volatile AudioRecord audioRecord;
    private volatile boolean continuous;

    public OfflineVoiceRecognizer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        start(false);
    }

    public void start(boolean continuous) {
        this.continuous = continuous;
        StorageService.unpack(context, "model-ru", "model-ru",
                modelDirectory -> {
                    if (stopped.get()) {
                        modelDirectory.close();
                        return;
                    }
                    try {
                        executor.execute(() -> listen(modelDirectory));
                    } catch (RejectedExecutionException exception) {
                        modelDirectory.close();
                        if (!stopped.get()) {
                            reportError(exception);
                        }
                    }
                },
                this::reportError);
    }

    public void stop() {
        stopped.set(true);
        AudioRecord recorder = audioRecord;
        if (recorder != null && recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            try {
                recorder.stop();
            } catch (IllegalStateException exception) {
                Log.w(TAG, "Audio recorder was already stopped", exception);
            }
        }
        executor.shutdown();
    }

    private void listen(Model model) {
        Recognizer recognizer = null;
        AudioRecord recorder = null;
        try {
            if (stopped.get()) {
                return;
            }
            recognizer = new Recognizer(model, SAMPLE_RATE);

            int minimumBufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimumBufferSize <= 0) {
                throw new IOException("Микрофон не поддерживает распознавание речи");
            }
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minimumBufferSize, 8192));
            audioRecord = recorder;
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IOException("Не удалось запустить микрофон");
            }

            recorder.startRecording();
            byte[] buffer = new byte[4096];
            while (!stopped.get()) {
                int bytesRead = recorder.read(buffer, 0, buffer.length);
                if (bytesRead < 0) {
                    if (!stopped.get()) {
                        throw new IOException("Ошибка чтения звука: " + bytesRead);
                    }
                    break;
                }
                if (bytesRead > 0 && recognizer.acceptWaveForm(buffer, bytesRead)) {
                    String text = new JSONObject(recognizer.getResult()).optString("text", "").trim();
                    if (!text.isEmpty()) {
                        if (continuous) {
                            synchronized (transcript) {
                                if (transcript.length() > 0) {
                                    transcript.append(' ');
                                }
                                transcript.append(text);
                            }
                        } else {
                            stopped.set(true);
                            String result = text;
                            mainHandler.post(() -> listener.onResult(result));
                        }
                    }
                }
            }
        } catch (IOException | JSONException | RuntimeException exception) {
            reportError(exception);
        } finally {
            if (continuous && stopped.get() && recognizer != null) {
                try {
                    String remaining = new JSONObject(recognizer.getFinalResult())
                            .optString("text", "").trim();
                    synchronized (transcript) {
                        if (!remaining.isEmpty()) {
                            if (transcript.length() > 0) {
                                transcript.append(' ');
                            }
                            transcript.append(remaining);
                        }
                        String result = transcript.toString().trim();
                        if (!result.isEmpty()) {
                            mainHandler.post(() -> listener.onResult(result));
                        } else if (!Thread.currentThread().isInterrupted()) {
                            mainHandler.post(() -> listener.onError("Речь не распознана"));
                        }
                    }
                } catch (JSONException exception) {
                    reportError(exception);
                }
            }
            audioRecord = null;
            if (recorder != null) {
                try {
                    if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop();
                    }
                } catch (IllegalStateException exception) {
                    Log.w(TAG, "Audio recorder was already stopped", exception);
                } finally {
                    recorder.release();
                }
            }
            if (recognizer != null) {
                recognizer.close();
            }
            model.close();
            executor.shutdown();
        }
    }

    private void reportError(Throwable error) {
        Log.e(TAG, "Offline voice recognition failed", error);
        if (stopped.compareAndSet(false, true)) {
            mainHandler.post(() -> listener.onError(
                    error.getMessage() == null ? "Не удалось распознать речь офлайн" : error.getMessage()));
        }
    }
}
