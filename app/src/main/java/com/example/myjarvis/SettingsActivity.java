package com.example.myjarvis;

import android.Manifest;
import android.app.Dialog;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Window;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.text.InputType;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends AppCompatActivity {

    private SharedPreferences prefs;
    private Switch backgroundVoiceSwitch;
    private Switch floatingWidgetSwitch;
    private ActivityResultLauncher<String> microphonePermissionLauncher;
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private boolean overlayPermissionPending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Switch themeSwitch = findViewById(R.id.themeSwitch);
        Switch voiceSwitch = findViewById(R.id.voiceSwitch);
        Switch quietSwitch = findViewById(R.id.quietSwitch);
        Switch speakResponsesSwitch = findViewById(R.id.speakResponsesSwitch);
        backgroundVoiceSwitch = findViewById(R.id.backgroundVoiceSwitch);
        floatingWidgetSwitch = findViewById(R.id.floatingWidgetSwitch);
        TextView privacyPolicyLink = findViewById(R.id.privacyPolicyLink);
        Button backButton = findViewById(R.id.backButton);
        Button syncButton = findViewById(R.id.syncWifiButton);

        privacyPolicyLink.setPaintFlags(
                privacyPolicyLink.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);

        prefs = getSharedPreferences("LiraPrefs", MODE_PRIVATE);
        themeSwitch.setChecked(prefs.getBoolean("theme_dark", true));
        voiceSwitch.setChecked(prefs.getBoolean("voice_female", true));
        quietSwitch.setChecked(prefs.getBoolean("quiet_mode", false));
        speakResponsesSwitch.setChecked(prefs.getBoolean("speak_responses", false));
        backgroundVoiceSwitch.setChecked(
                prefs.getBoolean("background_voice_enabled", false));
        floatingWidgetSwitch.setChecked(prefs.getBoolean("floating_widget_enabled", false));

        microphonePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        requestNotificationPermission();
                    } else {
                        disableBackgroundVoice("Для фонового прослушивания нужен доступ к микрофону.");
                    }
                });
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        startBackgroundVoice();
                    } else {
                        disableBackgroundVoice(
                                "Без разрешения на уведомления фоновый режим не включён.");
                    }
                });

        themeSwitch.setOnCheckedChangeListener((button, enabled) -> {
            prefs.edit().putBoolean("theme_dark", enabled).apply();
            AppCompatDelegate.setDefaultNightMode(enabled
                    ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
        });
        voiceSwitch.setOnCheckedChangeListener((button, enabled) ->
                prefs.edit().putBoolean("voice_female", enabled).apply());
        quietSwitch.setOnCheckedChangeListener((button, enabled) ->
                prefs.edit().putBoolean("quiet_mode", enabled).apply());
        speakResponsesSwitch.setOnCheckedChangeListener((button, enabled) ->
                prefs.edit().putBoolean("speak_responses", enabled).apply());

        backgroundVoiceSwitch.setOnCheckedChangeListener((button, enabled) -> {
            prefs.edit().putBoolean("background_voice_enabled", enabled).apply();
            if (enabled) {
                enableBackgroundVoice();
            } else {
                stopService(new Intent(this, LiraBackgroundService.class));
            }
        });
        floatingWidgetSwitch.setOnCheckedChangeListener((button, enabled) -> {
            prefs.edit().putBoolean("floating_widget_enabled", enabled).apply();
            if (enabled) {
                startFloatingWidgetIfPermitted();
            } else {
                stopService(new Intent(this, FloatingWidgetService.class));
            }
        });

        privacyPolicyLink.setOnClickListener(view -> showPrivacyPolicyDialog());
        syncButton.setOnClickListener(view -> showWifiSyncDialog());
        backButton.setOnClickListener(view -> {
            Toast.makeText(this, "Настройки сохранены!", Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    private void showWifiSyncDialog() {
        EditText address = new EditText(this);
        address.setSingleLine(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setHint("IP компьютера, например 192.168.1.20");
        EditText token = new EditText(this);
        token.setSingleLine(true);
        token.setInputType(InputType.TYPE_CLASS_NUMBER);
        token.setHint("8-значный код пары с ПК");
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        fields.setPadding(padding, padding, padding, 0);
        fields.addView(address);
        fields.addView(token);
        new AlertDialog.Builder(this)
                .setTitle("Синхронизация по локальному Wi-Fi")
                .setMessage("На ПК включи синхронизацию в Настройках и введи его IP и код. "
                        + "Используй только доверенную сеть: соединение локальное, но не шифруется.")
                .setView(fields)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Синхронизировать", (dialog, which) ->
                        synchronizeWithPc(address.getText().toString().trim(),
                                token.getText().toString().trim()))
                .show();
    }

    private void synchronizeWithPc(String host, String token) {
        if (!isPrivateLanAddress(host) || !token.matches("\\d{8}")) {
            Toast.makeText(this, "Укажи локальный IP компьютера и 8-значный код.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Синхронизирую данные…", Toast.LENGTH_SHORT).show();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            HttpURLConnection connection = null;
            OfflineDataStore store = new OfflineDataStore(this);
            try {
                URL url = new URL("http://" + host + ":48522/lira/sync");
                connection = (HttpURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(20000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("X-Lira-Token", token);
                byte[] payload = store.exportSyncSnapshot().toString()
                        .getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(payload);
                }
                int status = connection.getResponseCode();
                InputStream response = status >= 200 && status < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                if (response == null) {
                    throw new IllegalStateException("ПК вернул пустой ответ.");
                }
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        body.append(line);
                    }
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw new IllegalStateException("ПК вернул ответ " + status + ".");
                }
                store.mergeSyncSnapshot(new JSONObject(body.toString()));
                runOnUiThread(() -> Toast.makeText(this,
                        "Синхронизация завершена.", Toast.LENGTH_LONG).show());
            } catch (Exception exception) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Синхронизация не удалась: " + exception.getMessage(),
                        Toast.LENGTH_LONG).show());
            } finally {
                store.close();
                if (connection != null) {
                    connection.disconnect();
                }
                executor.shutdown();
            }
        });
    }

    private boolean isPrivateLanAddress(String host) {
        try {
            if (!host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                return false;
            }
            InetAddress address = InetAddress.getByName(host);
            byte[] bytes = address.getAddress();
            if (bytes.length != 4 || address.isLoopbackAddress() || address.isMulticastAddress()) {
                return false;
            }
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first == 10 || first == 192 && second == 168
                    || first == 172 && second >= 16 && second <= 31
                    || first == 169 && second == 254;
        } catch (Exception exception) {
            return false;
        }
    }

    private void enableBackgroundVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }
        requestNotificationPermission();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        startBackgroundVoice();
    }

    private void startBackgroundVoice() {
        prefs.edit().putBoolean("background_voice_enabled", true).apply();
        backgroundVoiceSwitch.setChecked(true);
        Intent service = new Intent(this, LiraBackgroundService.class);
        service.setAction(LiraBackgroundService.ACTION_ENABLE);
        ContextCompat.startForegroundService(this, service);
        Toast.makeText(this, "Фоновый микрофон включён. Скажи «Лира» и команду.",
                Toast.LENGTH_LONG).show();
    }

    private void disableBackgroundVoice(String message) {
        prefs.edit().putBoolean("background_voice_enabled", false).apply();
        backgroundVoiceSwitch.setChecked(false);
        stopService(new Intent(this, LiraBackgroundService.class));
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void startFloatingWidgetIfPermitted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            overlayPermissionPending = true;
            Intent permission = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(permission);
            Toast.makeText(this, "Разреши L.I.R.A. показывать плавающую кнопку.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        startService(new Intent(this, FloatingWidgetService.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs == null) {
            return;
        }
        if (overlayPermissionPending) {
            overlayPermissionPending = false;
            boolean granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || Settings.canDrawOverlays(this);
            prefs.edit().putBoolean("floating_widget_enabled", granted).apply();
            floatingWidgetSwitch.setChecked(granted);
            if (granted) {
                startService(new Intent(this, FloatingWidgetService.class));
            } else {
                stopService(new Intent(this, FloatingWidgetService.class));
            }
        } else {
            boolean widgetEnabled = prefs.getBoolean("floating_widget_enabled", false);
            boolean overlayGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || Settings.canDrawOverlays(this);
            if (widgetEnabled && overlayGranted) {
                startService(new Intent(this, FloatingWidgetService.class));
            } else if (widgetEnabled) {
                prefs.edit().putBoolean("floating_widget_enabled", false).apply();
                floatingWidgetSwitch.setChecked(false);
                stopService(new Intent(this, FloatingWidgetService.class));
            }
        }
        if (prefs.getBoolean("background_voice_enabled", false)) {
            boolean microphoneGranted = ContextCompat.checkSelfPermission(this,
                    Manifest.permission.RECORD_AUDIO)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            boolean notificationsGranted = Build.VERSION.SDK_INT < 33
                    || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (!microphoneGranted || !notificationsGranted) {
                disableBackgroundVoice("Для фонового режима нужно разрешить микрофон и уведомления.");
                return;
            }
            Intent service = new Intent(this, LiraBackgroundService.class);
            service.setAction(LiraBackgroundService.ACTION_ENABLE);
            ContextCompat.startForegroundService(this, service);
        }
    }

    private void showPrivacyPolicyDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_privacy_policy);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    new ColorDrawable(android.graphics.Color.TRANSPARENT));
        }
        TextView close = dialog.findViewById(R.id.tvClose);
        Button ok = dialog.findViewById(R.id.btnOk);
        close.setOnClickListener(view -> dialog.dismiss());
        ok.setOnClickListener(view -> dialog.dismiss());
        dialog.show();
    }
}
