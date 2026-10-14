package com.example.myjarvis;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.StorageService;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.text.Normalizer;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LiraBackgroundService extends Service {

    public static final String ACTION_ENABLE = "com.example.myjarvis.action.ENABLE_VOICE";
    public static final String ACTION_DISABLE = "com.example.myjarvis.action.DISABLE_VOICE";
    public static final String ACTION_COMMAND = "com.example.myjarvis.action.VOICE_COMMAND";
    public static final String EXTRA_VOICE_COMMAND = "voice_command";
    public static final String EXTRA_START_VOICE = "start_voice_recognition";

    private static final String TAG = "LiraBackgroundService";
    private static final String CHANNEL_ID = "LiraVoiceServiceChannel";
    private static final String RESULT_CHANNEL_ID = "LiraVoiceResults";
    private static final int NOTIFICATION_ID = 1337;
    private static final int COMMAND_NOTIFICATION_ID = 1338;
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final String PREFERENCES = "LiraPrefs";

    private volatile boolean listening;
    private volatile boolean destroyed;
    private AudioRecord audioRecord;
    private ExecutorService audioExecutor;
    private Model model;
    private long wakeWordExpiry;

    @Override
    public void onCreate() {
        super.onCreate();
        LibVosk.setLogLevel(LogLevel.WARNINGS);
        createNotificationChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_DISABLE.equals(intent.getAction())) {
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putBoolean("background_voice_enabled", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getBoolean("background_voice_enabled", false)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForegroundServiceNotification();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            reportFailure("Нет разрешения на микрофон. Включи его в настройках приложения.");
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putBoolean("background_voice_enabled", false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!listening) {
            initializeOfflineModel();
        }
        return START_STICKY;
    }

    private void initializeOfflineModel() {
        listening = true;
        try {
            StorageService.unpack(this, "model-ru", "model-ru",
                    loadedModel -> {
                        if (destroyed || !listening) {
                            loadedModel.close();
                            return;
                        }
                        model = loadedModel;
                        startListening();
                    },
                    exception -> {
                        Log.e(TAG, "Failed to load the offline speech model", exception);
                        reportFailure("Не удалось загрузить офлайн-модель речи. Перезапусти режим.");
                        stopSelf();
                    });
        } catch (RuntimeException exception) {
            Log.e(TAG, "Failed to initialize the offline speech model", exception);
            reportFailure("Не удалось запустить офлайн-распознавание.");
            stopSelf();
        }
    }

    private void startForegroundServiceNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, LiraBackgroundService.class);
        stopIntent.setAction(ACTION_DISABLE);
        PendingIntent stopPendingIntent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? PendingIntent.getForegroundService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                : PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("L.I.R.A. слушает «Лира»")
                .setContentText("Офлайн-микрофон активен. Коснись, чтобы выключить.")
                .setSmallIcon(R.drawable.ic_lira_logo)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Выключить", stopPendingIntent)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                "Фоновое распознавание L.I.R.A.", NotificationManager.IMPORTANCE_LOW));
        manager.createNotificationChannel(new NotificationChannel(RESULT_CHANNEL_ID,
                "Голосовые команды L.I.R.A.", NotificationManager.IMPORTANCE_DEFAULT));
    }

    private void startListening() {
        if (audioExecutor != null || destroyed || !listening || model == null) {
            return;
        }
        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (bufferSize <= 0) {
            reportFailure("Устройство не поддерживает запись с микрофона.");
            stopSelf();
            return;
        }
        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, Math.max(bufferSize, 8192));
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                reportFailure("Не удалось инициализировать микрофон.");
                stopSelf();
                return;
            }
            audioRecord.startRecording();
            audioExecutor = Executors.newSingleThreadExecutor();
            audioExecutor.execute(this::recognizeCommands);
        } catch (SecurityException | IllegalStateException exception) {
            Log.e(TAG, "Could not start background audio recording", exception);
            reportFailure("Не удалось включить микрофон. Проверь разрешения и настройки батареи.");
            stopSelf();
        }
    }

    private void recognizeCommands() {
        AudioRecord recorder = audioRecord;
        Model voiceModel = model;
        if (recorder == null || voiceModel == null) {
            return;
        }
        try (Recognizer recognizer = new Recognizer(voiceModel, SAMPLE_RATE)) {
            byte[] buffer = new byte[4096];
            while (listening && !destroyed) {
                int bytesRead = recorder.read(buffer, 0, buffer.length);
                if (bytesRead < 0) {
                    if (listening && !destroyed) {
                        throw new IOException("AudioRecord returned " + bytesRead);
                    }
                    break;
                }
                if (bytesRead > 0 && recognizer.acceptWaveForm(buffer, bytesRead)) {
                    processRecognizedText(new JSONObject(recognizer.getResult())
                            .optString("text", "").trim());
                }
            }
        } catch (JSONException | IOException | RuntimeException exception) {
            if (listening && !destroyed) {
                Log.e(TAG, "Background voice recognition failed", exception);
                reportFailure("Фоновое распознавание остановилось: проверь микрофон.");
                stopSelf();
            }
        } finally {
            if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                try {
                    recorder.stop();
                } catch (IllegalStateException exception) {
                    Log.w(TAG, "The background audio recorder was already stopped.", exception);
                }
            }
            recorder.release();
            if (audioRecord == recorder) {
                audioRecord = null;
            }
            voiceModel.close();
            if (model == voiceModel) {
                model = null;
            }
            audioExecutor = null;
        }
    }

    private void processRecognizedText(String recognized) {
        String text = recognized.trim();
        if (text.isEmpty()) {
            return;
        }
        java.util.regex.Matcher wakeMatcher = java.util.regex.Pattern.compile(
                "^(?:(?:эй|окей|ок|слушай)\\s+)*(?:лира|лиро|лера|мира|ира|lira)"
                        + "(?=\\s|$|[,.:;!?—-])(.*)$",
                java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE)
                .matcher(text);
        if (wakeMatcher.matches()) {
            text = wakeMatcher.group(1).trim()
                    .replaceAll("^[\\s,.:;!?—-]+", "")
                    .replaceAll("(?iu)^(?:(?:лира|лиро|лера|мира|ира|lira)"
                            + "[\\s,.:;!?—-]*)+", "");
            wakeWordExpiry = System.currentTimeMillis() + 12000L;
            if (text.isEmpty()) {
                return;
            }
        } else if (System.currentTimeMillis() > wakeWordExpiry) {
            return;
        } else {
            wakeWordExpiry = 0;
        }
        wakeWordExpiry = 0;
        executeOrNotify(text);
    }

    private void executeOrNotify(String command) {
        String lower = command.toLowerCase(new Locale("ru"));
        List<FinanceVoiceParser.Entry> entries = FinanceVoiceParser.parse(command);
        if (!entries.isEmpty()) {
            OfflineDataStore store = new OfflineDataStore(this);
            try {
                String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
                for (FinanceVoiceParser.Entry entry : entries) {
                    store.addTransaction(entry.title, entry.amountCents, entry.income,
                            date, entry.category);
                }
            } catch (RuntimeException exception) {
                Log.e(TAG, "Failed to save voice-recorded finance entries", exception);
                notifyCommand("Не удалось сохранить финансовую операцию.", null);
                return;
            } finally {
                store.close();
            }
            notifyCommand("Записала " + entries.size() + " финансовых операций.", null);
            return;
        }

        if (startsWithCommand(lower, "запиши заметку", "добавь заметку", "заметка", "запомни")) {
            String note = removeCommandPrefix(command,
                    "запиши заметку", "добавь заметку", "заметка", "запомни");
            if (note.isEmpty()) {
                notifyCommand("Не услышала текст заметки.", command);
                return;
            }
            OfflineDataStore store = new OfflineDataStore(this);
            try {
                String created = new SimpleDateFormat("yyyy-MM-dd HH:mm",
                        Locale.US).format(new Date());
                String title = "Заметка " + new SimpleDateFormat("d MMM, HH:mm",
                        new Locale("ru")).format(new Date());
                store.addVoiceNote(title, note, created);
            } catch (RuntimeException exception) {
                Log.e(TAG, "Failed to save a background voice note", exception);
                notifyCommand("Не удалось сохранить заметку.", null);
                return;
            } finally {
                store.close();
            }
            notifyCommand("Голосовая заметка сохранена. Её можно открыть во вкладке «Цели».",
                    null);
            return;
        }

        if (startsWithCommand(lower, "найди", "найти", "поищи", "поиск", "search",
                "search for")) {
            String query = removeCommandPrefix(command, "найди", "найти", "поищи",
                    "поиск", "search for", "search");
            if (query.isEmpty()) {
                notifyCommand("Скажи, что найти в интернете.", null);
                return;
            }
            Intent searchIntent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)));
            searchIntent.addCategory(Intent.CATEGORY_BROWSABLE);
            searchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            launchFromBackgroundOrNotify(searchIntent,
                    "Не удалось открыть поиск. Нажми уведомление, чтобы открыть браузер.");
            return;
        }

        if (startsWithCommand(lower, "открой", "открыть", "запусти", "запустить", "open")) {
            String appName = removeCommandPrefix(command,
                    "открой", "открыть", "запусти", "запустить", "open");
            Intent launchIntent = findAppLaunchIntent(appName);
            if (launchIntent == null) {
                notifyCommand(appName.isEmpty() ? "Назови приложение, которое нужно открыть."
                        : "Приложение «" + appName + "» не найдено.", null);
                return;
            }
            launchFromBackgroundOrNotify(launchIntent,
                    "Android не разрешил открыть приложение «" + appName
                            + "» из фона. Нажми уведомление, чтобы открыть его.");
            return;
        }
        notifyCommand("Пока не умею выполнить эту команду в фоне: " + command, null);
    }

    private boolean startsWithCommand(String text, String... commands) {
        for (String command : commands) {
            if (text.equals(command) || text.startsWith(command + " ")) {
                return true;
            }
        }
        return false;
    }

    private String removeCommandPrefix(String text, String... commands) {
        for (String command : commands) {
            if (text.regionMatches(true, 0, command, 0, command.length())
                    && (text.length() == command.length()
                    || Character.isWhitespace(text.charAt(command.length())))) {
                return text.substring(command.length()).trim();
            }
        }
        return text.trim();
    }

    @Nullable
    private Intent findAppLaunchIntent(String name) {
        if (name.isEmpty()) {
            return null;
        }
        PackageManager packageManager = getPackageManager();
        String query = normalizeAppName(name);
        String transliterated = normalizeAppName(transliterateAppName(name));
        Intent exactPackage = packageManager.getLaunchIntentForPackage(query);
        if (exactPackage != null) {
            exactPackage.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return exactPackage;
        }

        Intent launcher = new Intent(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = packageManager.queryIntentActivities(launcher, 0);
        ResolveInfo bestMatch = null;
        int bestScore = 0;
        for (ResolveInfo app : apps) {
            String label = normalizeAppName(app.loadLabel(packageManager).toString());
            String packageName = normalizeAppName(
                    app.activityInfo.packageName.replace('.', ' '));
            int score = Math.max(appMatchScore(label, query, packageName),
                    appMatchScore(label, transliterated, packageName));
            if (score > bestScore) {
                bestScore = score;
                bestMatch = app;
            }
        }
        if (bestMatch == null) {
            return null;
        }
        Intent launchIntent = packageManager.getLaunchIntentForPackage(
                bestMatch.activityInfo.packageName);
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return launchIntent;
    }

    private String normalizeAppName(String value) {
        String normalized = Normalizer.normalize(value.toLowerCase(Locale.ROOT).trim(),
                        Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("[^\\p{L}\\p{N}.]+", " ")
                .trim();
        Map<String, String> aliases = new HashMap<>();
        aliases.put("яндекс музыка", "yandex music");
        aliases.put("яндекс музыку", "yandex music");
        aliases.put("музыка яндекс", "yandex music");
        aliases.put("телеграм", "telegram");
        aliases.put("телеграмм", "telegram");
        aliases.put("телега", "telegram");
        aliases.put("ватсап", "whatsapp");
        aliases.put("вотсап", "whatsapp");
        aliases.put("вацап", "whatsapp");
        aliases.put("гугл хром", "chrome");
        aliases.put("хром", "chrome");
        aliases.put("яндекс браузер", "yandex browser");
        aliases.put("яндекс", "yandex");
        aliases.put("вконтакте", "vk");
        aliases.put("вк", "vk");
        aliases.put("ютуб", "youtube");
        aliases.put("ю туб", "youtube");
        aliases.put("инстаграм", "instagram");
        aliases.put("фейсбук", "facebook");
        aliases.put("тикток", "tiktok");
        aliases.put("почта", "mail");
        aliases.put("карты", "maps");
        aliases.put("галерея", "gallery");
        aliases.put("музыка", "music");
        aliases.put("сбербанк", "sberbank");
        aliases.put("альфа банк", "alpha bank");
        aliases.put("яндекс карты", "yandex maps");
        aliases.put("яндекс навигатор", "yandex navigator");
        return aliases.getOrDefault(normalized, normalized);
    }

    private int appMatchScore(String label, String query, String packageName) {
        if (query.isEmpty()) {
            return 0;
        }
        if (label.equals(query) || packageName.equals(query)) {
            return 3;
        }
        if (label.startsWith(query) || packageName.startsWith(query)) {
            return 2;
        }
        return label.contains(query) || packageName.contains(query) ? 1 : 0;
    }

    private String transliterateAppName(String value) {
        String alphabet = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя";
        String[] replacements = {"a", "b", "v", "g", "d", "e", "yo", "zh", "z", "i",
                "y", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u", "f", "kh",
                "ts", "ch", "sh", "shch", "", "y", "", "e", "yu", "ya"};
        StringBuilder result = new StringBuilder(value.length() * 2);
        for (int index = 0; index < value.length(); index++) {
            char character = Character.toLowerCase(value.charAt(index));
            int letterIndex = alphabet.indexOf(character);
            result.append(letterIndex < 0 ? character : replacements[letterIndex]);
        }
        return result.toString();
    }

    private void launchFromBackgroundOrNotify(Intent intent, String fallbackMessage) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (android.content.ActivityNotFoundException | SecurityException exception) {
            Log.w(TAG, "Android blocked a background activity launch", exception);
            notifyCommand(fallbackMessage, null, intent);
        }
    }

    private void notifyCommand(String message, @Nullable String command) {
        notifyCommand(message, command, null);
    }

    private void notifyCommand(String message, @Nullable String command,
                               @Nullable Intent actionIntent) {
        PendingIntent pendingIntent = null;
        if (actionIntent != null) {
            pendingIntent = PendingIntent.getActivity(this,
                    command == null ? message.hashCode() : command.hashCode(), actionIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } else if (command != null) {
            Intent openIntent = new Intent(this, MainActivity.class);
            openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            openIntent.putExtra(EXTRA_VOICE_COMMAND, command);
            pendingIntent = PendingIntent.getActivity(this, command.hashCode(), openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
                .setContentTitle("L.I.R.A.")
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_lira_logo)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true);
        if (pendingIntent != null) {
            builder.setContentIntent(pendingIntent);
        }
        Notification notification = builder.build();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(COMMAND_NOTIFICATION_ID, notification);
        }
    }

    private void reportFailure(String message) {
        Log.w(TAG, message);
        notifyCommand(message, null);
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        listening = false;
        AudioRecord recorder = audioRecord;
        if (recorder != null) {
            try {
                if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop();
                }
            } catch (IllegalStateException exception) {
                Log.w(TAG, "The background audio recorder was already stopped.", exception);
            }
        }
        if (audioExecutor != null) {
            audioExecutor.shutdownNow();
            audioExecutor = null;
        } else {
            if (recorder != null) {
                recorder.release();
            }
            if (model != null) {
                model.close();
            }
            model = null;
        }
        audioRecord = null;
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putBoolean("background_voice_enabled", false).apply();
        stopForeground(true);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
