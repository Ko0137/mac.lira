package com.example.myjarvis;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AppScanner {
    private final Context context;
    private final Map<String, String> installedApps = new HashMap<>();

    public AppScanner(Context context) {
        this.context = context;
        scanApps();
    }

    public void scanApps() {
        installedApps.clear();
        PackageManager pm = context.getPackageManager();
        List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        for (ApplicationInfo packageInfo : packages) {
            try {
                String appName = pm.getApplicationLabel(packageInfo).toString().toLowerCase();
                String packageName = packageInfo.packageName;
                installedApps.put(appName, packageName);
            } catch (Exception ignored) {}
        }
    }

    public boolean launchAppByName(String query) {
        String lowerQuery = query.toLowerCase().trim();
        for (Map.Entry<String, String> entry : installedApps.entrySet()) {
            if (entry.getKey().contains(lowerQuery) || lowerQuery.contains(entry.getKey())) {
                Intent intent = context.getPackageManager().getLaunchIntentForPackage(entry.getValue());
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                    return true;
                }
            }
        }
        return false;
    }
}
