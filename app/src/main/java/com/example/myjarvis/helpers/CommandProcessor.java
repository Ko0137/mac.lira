package com.example.myjarvis.helpers;

import android.content.Context;

public class CommandProcessor {

    public interface CommandCallback {
        void onResponse(String message);
        void onOpenNotes();
        void onOpenSettings();
        void onShowChat();
    }

    private final AppLauncherHelper appLauncherHelper;
    private final FileManagerHelper fileManagerHelper;
    private final CommandCallback callback;

    public CommandProcessor(Context context, CommandCallback callback) {
        this.callback = callback;
        this.appLauncherHelper = new AppLauncherHelper(context);
        this.fileManagerHelper = new FileManagerHelper();
    }

    public void process(String command) {
        String lowerCmd = command.toLowerCase().trim();

        if (lowerCmd.startsWith("открой ") || lowerCmd.startsWith("запусти ")) {
            String appTarget = lowerCmd.replace("открой ", "").replace("запусти ", "").trim();
            if (appTarget.contains("заметки")) {
                callback.onOpenNotes();
            } else if (appTarget.contains("настройки")) {
                callback.onOpenSettings();
            } else {
                String result = appLauncherHelper.openAppByName(appTarget);
                callback.onResponse(result);
            }
        } else if (lowerCmd.contains("заметки") || lowerCmd.contains("заметка")) {
            callback.onOpenNotes();
        } else if (lowerCmd.contains("настройки")) {
            callback.onOpenSettings();
        } else if (lowerCmd.contains("чат")) {
            callback.onShowChat();
        } else if (lowerCmd.contains("файлы") || lowerCmd.contains("папка")) {
            String files = fileManagerHelper.getDownloadsFileList();
            callback.onResponse(files);
        } else if (lowerCmd.contains("привет")) {
            callback.onResponse("Привет! Я L.I.R.A. Теперь вся моя логика разнесена по независимым модулям.");
        } else {
            callback.onResponse("Принято: \"" + command + "\"");
        }
    }
}
