package com.example.myjarvis.helpers;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.util.List;

public class AppLauncherHelper {

    private final Context context;

    public AppLauncherHelper(Context context) {
        this.context = context;
    }

    public String openAppByName(String appName) {
        PackageManager pm = context.getPackageManager();
        List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);

        for (ApplicationInfo appInfo : packages) {
            String label = pm.getApplicationLabel(appInfo).toString().toLowerCase();
            if (label.contains(appName.toLowerCase())) {
                Intent launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName);
                if (launchIntent != null) {
                    context.startActivity(launchIntent);
                    return "Запускаю " + pm.getApplicationLabel(appInfo) + "...";
                }
            }
        }
        return "Приложение \"" + appName + "\" не найдено на устройстве.";
    }
}
