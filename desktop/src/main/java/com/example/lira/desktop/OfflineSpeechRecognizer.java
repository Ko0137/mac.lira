package com.example.lira.desktop;

import org.vosk.Model;
import org.vosk.Recognizer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class OfflineSpeechRecognizer implements AutoCloseable {
    private final Path modelPath;
    private final AtomicBoolean listening = new AtomicBoolean();
    private TargetDataLine microphone;
    private Thread worker;

    OfflineSpeechRecognizer(Path modelPath) {
        this.modelPath = modelPath;
    }

    static Path defaultModelPath() {
        String configured = System.getProperty("lira.model.path");
        if (configured != null && Files.isDirectory(Path.of(configured))) {
            return Path.of(configured);
        }
        Path external = Path.of(System.getProperty("user.home"), ".lira", "model-ru");
        if (Files.isDirectory(external)) {
            return external;
        }
        return Path.of("model-ru");
    }

    void start(Consumer<String> onResult, Consumer<Exception> onFailure) {
        if (!listening.compareAndSet(false, true)) {
            return;
        }
        worker = new Thread(() -> recognize(onResult, onFailure), "lira-offline-recognition");
        worker.setDaemon(true);
        worker.start();
    }

    private void recognize(Consumer<String> onResult, Consumer<Exception> onFailure) {
        AudioFormat format = new AudioFormat(16_000, 16, 1, true, false);
        try {
            if (!Files.isDirectory(modelPath)) {
                throw new IOException("Не найдена русская офлайн-модель речи: " + modelPath);
            }
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            microphone = (TargetDataLine) AudioSystem.getLine(info);
            microphone.open(format);
            microphone.start();
            try (Model model = new Model(modelPath.toString());
                 Recognizer recognizer = new Recognizer(model, 16_000)) {
                byte[] buffer = new byte[4096];
                while (listening.get()) {
                    int count = microphone.read(buffer, 0, buffer.length);
                    if (count <= 0) {
                        continue;
                    }
                    if (recognizer.acceptWaveForm(buffer, count)) {
                        String recognized = recognizer.getResult();
                        String text = extractText(recognized);
                        if (!text.isBlank()) {
                            onResult.accept(text);
                        }
                    }
                }
                String finalText = extractText(recognizer.getFinalResult());
                if (!finalText.isBlank()) {
                    onResult.accept(finalText);
                }
            }
        } catch (Exception exception) {
            if (listening.get()) {
                onFailure.accept(exception);
            }
        } finally {
            listening.set(false);
            if (microphone != null) {
                microphone.stop();
                microphone.close();
                microphone = null;
            }
        }
    }

    private String extractText(String json) {
        int start = json.indexOf("\"text\":\"");
        if (start < 0) {
            return "";
        }
        start += 8;
        int end = json.indexOf('"', start);
        return end < 0 ? "" : json.substring(start, end).trim();
    }

    boolean isListening() {
        return listening.get();
    }

    @Override
    public void close() {
        listening.set(false);
        if (microphone != null) {
            microphone.stop();
        }
    }
}
