package com.example.myjarvis;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.camera2.CameraManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.provider.MediaStore;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.navigation.NavigationView;

import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private DrawerLayout drawerLayout;
    private NavigationView navView;
    private ImageButton btnMenu, btnSend, btnVoice;
    private RecyclerView recyclerView;
    private ChatAdapter chatAdapter;
    private List<ChatMessage> messageList;
    private EditText etMessage;
    private TextView connectionStatus;
    private android.view.View chatScreen;
    private android.view.View moduleContainer;
    private android.view.View btnTabChat;
    private android.view.View btnTabFinance;
    private android.view.View btnTabGoals;
    private OfflineDataStore dataStore;
    private static final int REQUEST_CODE_CAMERA = 101;
    private static final int REQUEST_CODE_MICROPHONE = 102;
    private static final String STATE_SELECTED_TAB = "selected_tab";
    private OfflineVoiceRecognizer voiceRecognizer;
    private TextToSpeech textToSpeech;
    private boolean speechReady;
    private boolean speechFailureReported;
    private int voiceRequestId;
    private boolean isFlashlightOn = false;
    private String selectedTab = "chat";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dataStore = new OfflineDataStore(this);
        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int languageStatus = textToSpeech.setLanguage(new Locale("ru", "RU"));
                boolean female = getSharedPreferences("LiraPrefs", MODE_PRIVATE)
                        .getBoolean("voice_female", true);
                textToSpeech.setPitch(female ? 1.12f : 0.88f);
                textToSpeech.setSpeechRate(1.0f);
                speechReady = languageStatus != TextToSpeech.LANG_MISSING_DATA
                        && languageStatus != TextToSpeech.LANG_NOT_SUPPORTED;
            }
        });
        drawerLayout = findViewById(R.id.drawerLayout);
        navView = findViewById(R.id.navView);
        btnMenu = findViewById(R.id.btnMenu);
        recyclerView = findViewById(R.id.recyclerView);
        etMessage = findViewById(R.id.etMessage);
        btnSend = findViewById(R.id.btnSend);
        btnVoice = findViewById(R.id.btnVoice);
        connectionStatus = findViewById(R.id.connectionStatus);
        chatScreen = findViewById(R.id.chatScreen);
        moduleContainer = findViewById(R.id.moduleContainer);
        btnTabChat = findViewById(R.id.btnTabChat);
        btnTabFinance = findViewById(R.id.btnTabFinance);
        btnTabGoals = findViewById(R.id.btnTabGoals);

        messageList = new ArrayList<>();
        chatAdapter = new ChatAdapter(this, messageList);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(chatAdapter);
        messageList.addAll(dataStore.getChatMessages());
        if (messageList.isEmpty()) {
            addMessage("Привет! Я L.I.R.A. Открываю приложения и сайты по названию, а историю чата и планы сохраняю на устройстве.", false);
        } else {
            chatAdapter.notifyDataSetChanged();
            recyclerView.scrollToPosition(messageList.size() - 1);
        }
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
        }
        updateConnectionStatus();

        btnTabChat.setOnClickListener(v -> showTab("chat"));
        btnTabFinance.setOnClickListener(v -> showTab("finance"));
        btnTabGoals.setOnClickListener(v -> showTab("goals"));
        showTab(savedInstanceState == null ? "chat"
                : savedInstanceState.getString(STATE_SELECTED_TAB, "chat"));

        // Открытие бокового меню по кнопке
        btnMenu.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));

        // Обработка выбора разделов в боковом меню
        navView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == android.R.id.home || id == R.id.nav_chat) {
                showTab("chat");
                drawerLayout.closeDrawer(GravityCompat.START);
            } else if (id == R.id.nav_apps) {
                drawerLayout.closeDrawer(GravityCompat.START);
                showTab("chat");
                addMessage("Напиши 'открой [название]', и я найду любое приложение на телефоне!", false);
            } else if (id == R.id.nav_tools) {
                drawerLayout.closeDrawer(GravityCompat.START);
                showTab("chat");
                toggleFlashlight();
            } else if (id == R.id.nav_web) {
                drawerLayout.closeDrawer(GravityCompat.START);
                showTab("chat");
                addMessage("Напиши 'найди [запрос]', чтобы выполнить поиск в интернете.", false);
            } else if (id == R.id.nav_settings) {
                drawerLayout.closeDrawer(GravityCompat.START);
                startActivity(new Intent(this, SettingsActivity.class));
            }
            return true;
        });

        btnSend.setOnClickListener(v -> {
            String text = etMessage.getText().toString().trim();
            if (!text.isEmpty()) {
                processUserMessage(text);
                etMessage.setText("");
            }
        });

        btnVoice.setOnClickListener(v -> startVoiceRecognition());
        handleActivityIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleActivityIntent(intent);
    }

    private void handleActivityIntent(Intent intent) {
        String command = intent.getStringExtra(LiraBackgroundService.EXTRA_VOICE_COMMAND);
        if (command != null && !command.trim().isEmpty()) {
            intent.removeExtra(LiraBackgroundService.EXTRA_VOICE_COMMAND);
            processUserMessage(command);
            return;
        }
        if (intent.getBooleanExtra(LiraBackgroundService.EXTRA_START_VOICE, false)) {
            intent.removeExtra(LiraBackgroundService.EXTRA_START_VOICE);
            if (getSharedPreferences("LiraPrefs", MODE_PRIVATE)
                    .getBoolean("background_voice_enabled", false)) {
                getSharedPreferences("LiraPrefs", MODE_PRIVATE).edit()
                        .putBoolean("background_voice_enabled", false).apply();
                stopService(new Intent(this, LiraBackgroundService.class));
                Toast.makeText(this,
                        "Фоновое прослушивание остановлено для голосового ввода. Его можно снова включить в настройках.",
                        Toast.LENGTH_LONG).show();
            }
            btnVoice.post(this::startVoiceRecognition);
        }
    }

    private void showTab(String tab) {
        boolean changed = !tab.equals(selectedTab);
        selectedTab = tab;
        boolean isChat = "chat".equals(tab);
        chatScreen.setVisibility(isChat ? android.view.View.VISIBLE : android.view.View.GONE);
        moduleContainer.setVisibility(isChat ? android.view.View.GONE : android.view.View.VISIBLE);
        setTabSelected(btnTabChat, isChat);
        setTabSelected(btnTabFinance, "finance".equals(tab));
        setTabSelected(btnTabGoals, "goals".equals(tab));

        if (!isChat && changed) {
            androidx.fragment.app.Fragment fragment = "finance".equals(tab)
                    ? new FinanceFragment() : new GoalsFragment();
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.moduleContainer, fragment, tab)
                    .commit();
        }
    }

    private void setTabSelected(android.view.View tab, boolean selected) {
        if (tab instanceof TextView) {
            ((TextView) tab).setTextColor(ContextCompat.getColor(this,
                    selected ? android.R.color.holo_blue_light : android.R.color.darker_gray));
        }
    }

    private void updateConnectionStatus() {
        connectionStatus.setText(hasInternetConnection()
                ? "Онлайн · локальные данные доступны"
                : "Офлайн · локальные данные доступны");
    }

    private boolean hasInternetConnection() {
        ConnectivityManager connectivityManager =
                (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            return false;
        }
        Network network = connectivityManager.getActiveNetwork();
        NetworkCapabilities capabilities = network == null
                ? null : connectivityManager.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    @Override
    protected void onSaveInstanceState(@androidx.annotation.NonNull Bundle outState) {
        outState.putString(STATE_SELECTED_TAB, selectedTab);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (connectionStatus != null) {
            updateConnectionStatus();
        }
        if (textToSpeech != null) {
            boolean female = getSharedPreferences("LiraPrefs", MODE_PRIVATE)
                    .getBoolean("voice_female", true);
            textToSpeech.setPitch(female ? 1.12f : 0.88f);
        }
    }

    private void addMessage(String text, boolean isUser) {
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        ChatMessage message = new ChatMessage(text, isUser, time);
        dataStore.saveChatMessage(message);
        messageList.add(message);
        chatAdapter.notifyItemInserted(messageList.size() - 1);
        recyclerView.scrollToPosition(messageList.size() - 1);
        if (!isUser) {
            speakAssistantResponse(text);
        }
    }

    private void speakAssistantResponse(String text) {
        android.content.SharedPreferences prefs = getSharedPreferences("LiraPrefs", MODE_PRIVATE);
        if (!prefs.getBoolean("speak_responses", false)
                || prefs.getBoolean("quiet_mode", false)) {
            return;
        }
        if (textToSpeech == null || !speechReady) {
            if (!speechFailureReported) {
                speechFailureReported = true;
                Toast.makeText(this,
                        "Русский синтез речи недоступен на устройстве. Установи голосовые данные в настройках Android.",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (textToSpeech.speak(text, TextToSpeech.QUEUE_ADD, null, "lira-response")
                == TextToSpeech.ERROR && !speechFailureReported) {
            speechFailureReported = true;
            Toast.makeText(this, "Не удалось озвучить ответ.", Toast.LENGTH_LONG).show();
        }
    }

    private void startVoiceRecognition() {
        if (voiceRecognizer != null) {
            voiceRecognizer.stop();
            voiceRecognizer = null;
            btnVoice.setImageResource(android.R.drawable.ic_btn_speak_now);
            return;
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_CODE_MICROPHONE);
            return;
        }
        int requestId = ++voiceRequestId;
        voiceRecognizer = new OfflineVoiceRecognizer(this, new OfflineVoiceRecognizer.Listener() {
            @Override
            public void onResult(String text) {
                if (requestId == voiceRequestId) {
                    voiceRecognizer = null;
                    btnVoice.setImageResource(android.R.drawable.ic_btn_speak_now);
                    processUserMessage(text);
                }
            }

            @Override
            public void onError(String message) {
                if (requestId == voiceRequestId) {
                    voiceRecognizer = null;
                    btnVoice.setImageResource(android.R.drawable.ic_btn_speak_now);
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                }
            }
        });
        btnVoice.setImageResource(android.R.drawable.ic_media_pause);
        Toast.makeText(this, "Загружаю офлайн-распознавание. Нажмите ещё раз для отмены.",
                Toast.LENGTH_SHORT).show();
        voiceRecognizer.start();
    }

    private void processUserMessage(String text) {
        showTab("chat");
        addMessage(text, true);
        String lower = text.toLowerCase(Locale.ROOT);

        List<FinanceVoiceParser.Entry> financeEntries = FinanceVoiceParser.parse(text);
        if (!financeEntries.isEmpty()) {
            String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            for (FinanceVoiceParser.Entry entry : financeEntries) {
                dataStore.addTransaction(entry.title, entry.amountCents, entry.income,
                        date, entry.category);
            }
            addMessage("Записала финансовые операции: " + financeEntries.size()
                    + ". Их можно проверить во вкладке «Финансы».", false);
        } else if (startsWithCommand(lower, "запиши заметку", "добавь заметку",
                "заметка", "запомни")) {
            String note = removeCommandPrefix(text, "запиши заметку", "добавь заметку",
                    "заметка", "запомни");
            if (note.isEmpty()) {
                addMessage("Произнеси текст заметки после команды «запиши заметку».", false);
            } else {
                String createdAt = new SimpleDateFormat("yyyy-MM-dd HH:mm",
                        Locale.US).format(new Date());
                dataStore.addVoiceNote("Заметка " + new SimpleDateFormat("d MMM, HH:mm",
                        new Locale("ru")).format(new Date()), note, createdAt);
                addMessage("Сохранила заметку на устройстве. Её можно прочитать здесь или во вкладке «Цели».",
                        false);
            }
        } else if (startsWithCommand(lower, "прочитай заметки", "покажи заметки",
                "мои заметки", "прочитай заметку")) {
            List<OfflineDataStore.VoiceNote> notes = dataStore.getVoiceNotes();
            if (notes.isEmpty()) {
                addMessage("Сохранённых заметок пока нет.", false);
            } else {
                StringBuilder result = new StringBuilder("Твои заметки:\n");
                for (int index = 0; index < notes.size(); index++) {
                    OfflineDataStore.VoiceNote note = notes.get(index);
                    result.append(index + 1).append(". ").append(note.title)
                            .append(": ").append(note.content).append('\n');
                }
                addMessage(result.toString().trim(), false);
            }
        } else if (lower.contains("фонарик") || lower.contains("torch")) {
            toggleFlashlight();
        } else if (lower.contains("камера") || lower.contains("фото")) {
            openCamera();
        } else if (startsWithCommand(lower, "найди", "найти", "поищи", "поиск", "search", "search for")) {
            String query = removeCommandPrefix(text, "найди", "найти", "поищи", "поиск",
                    "search for", "search");
            performWebSearch(query);
        } else if (startsWithCommand(lower, "открой", "открыть", "запусти", "запустить", "open")) {
            String appName = removeCommandPrefix(text, "открой", "открыть", "запусти",
                    "запустить", "open");
            if (appName.isEmpty()) {
                addMessage("Назови приложение: например, «открой YouTube» или «открой WhatsApp».", false);
            } else {
                openAppByName(appName);
            }
        } else {
            addMessage("Команда принята. Используй меню слева или попроси меня открыть приложение/найти информацию!", false);
        }
    }

    private boolean startsWithCommand(String message, String... commands) {
        for (String command : commands) {
            if (message.equals(command) || message.startsWith(command + " ")) {
                return true;
            }
        }
        return false;
    }

    private String removeCommandPrefix(String message, String... commands) {
        for (String command : commands) {
            if (message.regionMatches(true, 0, command, 0, command.length())
                    && (message.length() == command.length()
                    || Character.isWhitespace(message.charAt(command.length())))) {
                return message.substring(command.length()).trim();
            }
        }
        return message.trim();
    }

    private void toggleFlashlight() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.CAMERA}, REQUEST_CODE_CAMERA);
            return;
        }
        try {
            CameraManager cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
            if (cameraManager == null || cameraManager.getCameraIdList().length == 0) {
                addMessage("Фонарик недоступен на этом устройстве", false);
                return;
            }
            String cameraId = cameraManager.getCameraIdList()[0];
            isFlashlightOn = !isFlashlightOn;
            cameraManager.setTorchMode(cameraId, isFlashlightOn);
            addMessage(isFlashlightOn ? "Фонарик включен 🔦" : "Фонарик выключен", false);
        } catch (Exception e) {
            addMessage("Не удалось переключить фонарик", false);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @androidx.annotation.NonNull String[] permissions,
                                           @androidx.annotation.NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toggleFlashlight();
            } else {
                addMessage("Для управления фонариком нужно разрешение на камеру", false);
            }
        } else if (requestCode == REQUEST_CODE_MICROPHONE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startVoiceRecognition();
            } else {
                Toast.makeText(this, "Для голосового ввода нужен доступ к микрофону",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        voiceRequestId++;
        if (voiceRecognizer != null) {
            voiceRecognizer.stop();
            voiceRecognizer = null;
        }
        dataStore.close();
        super.onDestroy();
    }

    private void openCamera() {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivity(intent);
            addMessage("Открываю камеру 📷", false);
        } else {
            addMessage("Камера недоступна", false);
        }
    }

    private void performWebSearch(String query) {
        if (query.isEmpty()) {
            addMessage("Напиши, что найти: например, «найди прогноз погоды».", false);
            return;
        }
        if (!hasInternetConnection()) {
            addMessage("Веб-поиск требует подключения к интернету. Остальные данные и функции приложения доступны офлайн.", false);
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(intent);
            addMessage("Ищу в интернете: " + query, false);
        } catch (android.content.ActivityNotFoundException exception) {
            addMessage("Не удалось открыть браузер для поиска. Проверь, установлен ли браузер.", false);
        }
    }

    private void openAppByName(String name) {
        PackageManager pm = getPackageManager();
        String query = normalizeAppName(name);
        String transliteratedQuery = normalizeAppName(transliterateAppName(name));
        Intent exactPackageIntent = pm.getLaunchIntentForPackage(query);
        if (exactPackageIntent != null) {
            launchApp(pm, exactPackageIntent, query);
            return;
        }

        Intent launcher = new Intent(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(launcher, 0);
        ResolveInfo bestMatch = null;
        int bestScore = 0;
        for (ResolveInfo app : apps) {
            String label = app.loadLabel(pm).toString();
            String normalizedLabel = normalizeAppName(label);
            String packageName = normalizeAppName(app.activityInfo.packageName.replace('.', ' '));
            int score = appNameMatchScore(normalizedLabel, query, packageName);
            score = Math.max(score,
                    appNameMatchScore(normalizedLabel, transliteratedQuery, packageName));
            if (score > bestScore) {
                bestScore = score;
                bestMatch = app;
            }
        }
        if (bestMatch != null) {
            Intent launchIntent = pm.getLaunchIntentForPackage(bestMatch.activityInfo.packageName);
            if (launchIntent != null) {
                launchApp(pm, launchIntent, bestMatch.activityInfo.packageName);
                return;
            }
        }
        addMessage("Приложение \"" + name + "\" не найдено", false);
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
        aliases.put("яндекс музыки", "yandex music");
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

    private int appNameMatchScore(String label, String query, String packageName) {
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
        StringBuilder result = new StringBuilder(value.length() * 2);
        for (int index = 0; index < value.length(); index++) {
            char letter = Character.toLowerCase(value.charAt(index));
            switch (letter) {
                case 'а': result.append("a"); break;
                case 'б': result.append("b"); break;
                case 'в': result.append("v"); break;
                case 'г': result.append("g"); break;
                case 'д': result.append("d"); break;
                case 'е': result.append("e"); break;
                case 'ё': result.append("yo"); break;
                case 'ж': result.append("zh"); break;
                case 'з': result.append("z"); break;
                case 'и': result.append("i"); break;
                case 'й': result.append("y"); break;
                case 'к': result.append("k"); break;
                case 'л': result.append("l"); break;
                case 'м': result.append("m"); break;
                case 'н': result.append("n"); break;
                case 'о': result.append("o"); break;
                case 'п': result.append("p"); break;
                case 'р': result.append("r"); break;
                case 'с': result.append("s"); break;
                case 'т': result.append("t"); break;
                case 'у': result.append("u"); break;
                case 'ф': result.append("f"); break;
                case 'х': result.append("kh"); break;
                case 'ц': result.append("ts"); break;
                case 'ч': result.append("ch"); break;
                case 'ш': result.append("sh"); break;
                case 'щ': result.append("shch"); break;
                case 'ы': result.append("y"); break;
                case 'э': result.append("e"); break;
                case 'ю': result.append("yu"); break;
                case 'я': result.append("ya"); break;
                default:
                    if (letter != 'ъ' && letter != 'ь') {
                        result.append(letter);
                    }
                    break;
            }
        }
        return result.toString();
    }

    private void launchApp(PackageManager packageManager, Intent launchIntent, String packageName) {
        try {
            startActivity(launchIntent);
            addMessage("Запускаю " + packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(packageName, 0)) + " 🚀", false);
        } catch (PackageManager.NameNotFoundException | android.content.ActivityNotFoundException exception) {
            addMessage("Не удалось запустить выбранное приложение. Возможно, оно было удалено.",
                    false);
        }
    }
}
