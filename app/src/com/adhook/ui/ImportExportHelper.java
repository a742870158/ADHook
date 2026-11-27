package com.adhook.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;

import com.adhook.core.AdHookLog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 规则导出与本地文件读写辅助工具。
 *
 * 导出策略（minSdk 31，不申请存储权限）：
 * 1. 优先写 MediaStore.Downloads，文件落在「下载/ADHook」下；
 * 2. MediaStore 不可用时兜底应用私有外部目录（getExternalFilesDir("export")），
 *    该目录无需任何存储权限，返回绝对路径供 UI 提示用户。
 */
public final class ImportExportHelper {

    private ImportExportHelper() {
    }

    /**
     * 复制文本到系统剪贴板。
     */
    public static void copyToClipboard(Context context, String label, String text) {
        if (context == null || text == null) {
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(label, text));
                Toast.makeText(context, "✅ 已复制到剪贴板", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            AdHookLog.w("[ImportExportHelper] copyToClipboard failed: " + t.getMessage());
        }
    }

    /**
     * 从剪贴板读取文本（任何异常都降级返回空串，不影响 UI）。
     */
    public static String getFromClipboard(Context context) {
        if (context == null) {
            return "";
        }
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip()) {
                ClipData clip = cm.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence cs = clip.getItemAt(0).getText();
                    if (cs != null) {
                        return cs.toString().trim();
                    }
                }
            }
        } catch (Throwable t) {
            AdHookLog.w("[ImportExportHelper] getFromClipboard failed: " + t.getMessage());
        }
        return "";
    }

    /**
     * 将 JSON 规则内容导出到本地存储。
     *
     * @param context     上下文
     * @param baseName    文件名前缀（如 adhook_full_config、adhook_local_rules）
     * @param jsonContent 格式化好的 JSON 文本
     * @return 导出成功时返回文件路径或描述，失败返回 null
     */
    public static String exportToJsonFile(Context context, String baseName, String jsonContent) {
        if (context == null || jsonContent == null || jsonContent.isEmpty()) {
            return null;
        }

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String prefix = (baseName != null && !baseName.trim().isEmpty()) ? baseName.trim() : "adhook_config";
        String fileName = prefix + "_" + timeStamp + ".json";
        byte[] bytes = jsonContent.getBytes(StandardCharsets.UTF_8);

        // 1) 支持下限为 Android 12：优先 MediaStore.Downloads，无需存储权限。
        String mediaPath = exportViaMediaStore(context, fileName, bytes);
        if (mediaPath != null) {
            return mediaPath;
        }

        // 2) MediaStore 不可用时兜底应用私有目录，无需额外存储权限。
        String privatePath = exportToPrivateDir(context, fileName, bytes);
        if (privatePath != null) {
            return privatePath;
        }

        AdHookLog.w("[ImportExportHelper] 导出失败：MediaStore 与应用私有目录均不可写");
        return null;
    }

    /**
     * Android 10+：通过 MediaStore.Downloads 写入公共下载目录。
     */
    private static String exportViaMediaStore(Context context, String fileName, byte[] bytes) {
        Uri uri = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ADHook");
            uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                return null;
            }
            try (OutputStream os = context.getContentResolver().openOutputStream(uri)) {
                if (os == null) {
                    AdHookLog.w("[ImportExportHelper] MediaStore openOutputStream returned null, cleanup inserted uri");
                    deleteMediaStoreUri(context, uri);
                    return null;
                }
                os.write(bytes);
                os.flush();
            }
            String displayPath = "Download/ADHook/" + fileName;
            AdHookLog.i("[ImportExportHelper] File exported via MediaStore: " + displayPath);
            return displayPath;
        } catch (Throwable t) {
            AdHookLog.w("[ImportExportHelper] MediaStore export failed: " + t.getMessage());
            // 写入失败时清理掉插入的空条目，避免下载目录出现无法打开的 0 字节文件
            deleteMediaStoreUri(context, uri);
            return null;
        }
    }

    /**
     * 删除 MediaStore 中刚插入但写入失败的空文件条目（失败忽略）。
     */
    private static void deleteMediaStoreUri(Context context, Uri uri) {
        if (uri == null) {
            return;
        }
        try {
            context.getContentResolver().delete(uri, null, null);
        } catch (Throwable ignored) {
            // 清理失败不影响导出降级
        }
    }

    /**
     * 兜底：写入应用私有外部目录 getExternalFilesDir("export")，无需任何存储权限。
     * 若外部目录不可用（极少数设备），退化为应用内部 files/export。
     */
    private static String exportToPrivateDir(Context context, String fileName, byte[] bytes) {
        try {
            File dir = context.getExternalFilesDir("export");
            if (dir == null) {
                dir = new File(context.getFilesDir(), "export");
            }
            if (!dir.exists() && !dir.mkdirs()) {
                AdHookLog.w("[ImportExportHelper] 无法创建导出目录: " + dir.getAbsolutePath());
                return null;
            }
            File targetFile = new File(dir, fileName);
            try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                fos.write(bytes);
                fos.flush();
            }
            AdHookLog.i("[ImportExportHelper] File exported to private dir: " + targetFile.getAbsolutePath());
            return targetFile.getAbsolutePath();
        } catch (Throwable t) {
            AdHookLog.e("[ImportExportHelper] 私有目录导出失败", t);
            return null;
        }
    }

    /**
     * 从 ContentProvider Uri (系统文件选择器选中的文件) 中读取 UTF-8 文本内容。
     */
    public static String readTextFromUri(Context context, Uri uri) {
        if (context == null || uri == null) {
            return null;
        }
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) {
                AdHookLog.w("[ImportExportHelper] openInputStream returned null: " + uri);
                return null;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                char[] buffer = new char[8192];
                int count;
                while ((count = reader.read(buffer)) != -1) {
                    if (sb.length() + count > 16 * 1024 * 1024) {
                        AdHookLog.w("[ImportExportHelper] Import exceeds 16 MiB text limit");
                        return null;
                    }
                    sb.append(buffer, 0, count);
                }
                return sb.toString().trim();
            }
        } catch (Throwable t) {
            AdHookLog.e("[ImportExportHelper] Failed to read from URI: " + uri, t);
            return null;
        }
    }
}
