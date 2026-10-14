package com.example.myjarvis.helpers;

import android.os.Environment;
import java.io.File;

public class FileManagerHelper {

    public String getDownloadsFileList() {
        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (downloadDir.exists() && downloadDir.isDirectory()) {
            File[] files = downloadDir.listFiles();
            if (files != null && files.length > 0) {
                StringBuilder sb = new StringBuilder("Файлы в Загрузках (" + files.length + "):\n");
                for (int i = 0; i < Math.min(files.length, 5); i++) {
                    sb.append("- ").append(files[i].getName()).append("\n");
                }
                return sb.toString().trim();
            } else {
                return "Папка Загрузки пуста.";
            }
        } else {
            return "Нет доступа к файловой системе.";
        }
    }
}
