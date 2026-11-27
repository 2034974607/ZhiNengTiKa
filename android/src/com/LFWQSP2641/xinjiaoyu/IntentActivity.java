package com.LFWQSP2641.xinjiaoyu;

import org.qtproject.qt.android.bindings.QtActivity;
import java.io.File;
import java.util.Locale;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import androidx.core.content.FileProvider;

public class IntentActivity extends org.qtproject.qt.android.bindings.QtActivity
{
    private static final String TAG = "ZhiNengTiKaIntent";

    private interface UiAction
    {
        void run() throws Exception;
    }

    private static void showToast(QtActivity activity, String message)
    {
        try {
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
        } catch (RuntimeException exception) {
            // A closing Activity can also prevent a Toast; never propagate it to JNI.
            Log.w(TAG, "Unable to show intent failure message", exception);
        }
    }

    // true acknowledges UI dispatch only. Android can reject the action later,
    // and an installer result is not an acknowledgement of installation.
    private static boolean runUiAction(final QtActivity activity,
                                       final String failureMessage,
                                       final UiAction action)
    {
        if (activity == null) {
            Log.w(TAG, "No QtActivity for intent request");
            return false;
        }
        try {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (activity.isFinishing() || activity.isDestroyed()) {
                            throw new IllegalStateException("QtActivity is closing");
                        }
                        action.run();
                    } catch (Exception exception) {
                        Log.w(TAG, failureMessage, exception);
                        showToast(activity, failureMessage);
                    }
                }
            });
            return true;
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to dispatch intent to UI thread", exception);
            try {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showToast(activity, failureMessage);
                    }
                });
            } catch (RuntimeException toastException) {
                Log.w(TAG, "Unable to dispatch intent failure message", toastException);
            }
            return false;
        }
    }

    public static boolean installApk(final String filePath, final QtActivity activity)
    {
        return runUiAction(activity, "无法打开安装器或安装权限设置，请稍后重试", new UiAction() {
            @Override
            public void run() throws Exception {
                if (filePath == null || filePath.isEmpty()) {
                    showToast(activity, "安装包路径为空，请重新下载");
                    return;
                }
                File apkFile = new File(filePath).getCanonicalFile();
                File updatesDirectory = new File(activity.getCacheDir(), "updates").getCanonicalFile();
                String updatesPrefix = updatesDirectory.getPath() + File.separator;
                if (!apkFile.getPath().startsWith(updatesPrefix)
                        || !apkFile.getName().toLowerCase(Locale.ROOT).endsWith(".apk")
                        || !apkFile.isFile() || !apkFile.canRead() || apkFile.length() == 0) {
                    showToast(activity, "安装包不可用，请重新下载");
                    return;
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        && !activity.getPackageManager().canRequestPackageInstalls()) {
                    showToast(activity, "请允许此应用安装未知应用，然后返回应用，再次点击安装");
                    Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivity(settings);
                    return;
                }

                Uri contentUri = FileProvider.getUriForFile(activity,
                        activity.getPackageName() + ".permissiontest.fileprovider", apkFile);
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(contentUri, "application/vnd.android.package-archive");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setClipData(ClipData.newRawUri("APK", contentUri));
                activity.startActivity(intent);
            }
        });
    }

    public static boolean openUrl(final String url, final QtActivity activity)
    {
        return runUiAction(activity, "无法打开浏览器，请检查链接或安装浏览器", new UiAction() {
            @Override
            public void run() {
                Uri uri = url == null ? null : Uri.parse(url.trim());
                String scheme = uri == null ? null : uri.getScheme();
                if (uri == null || !uri.isHierarchical() || uri.getHost() == null
                        || uri.getHost().isEmpty()
                        || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                    showToast(activity, "仅支持有效的 HTTP 或 HTTPS 网页链接");
                    return;
                }
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
                activity.startActivity(intent);
            }
        });
    }
}
