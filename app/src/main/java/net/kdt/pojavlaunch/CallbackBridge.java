package net.kdt.pojavlaunch;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.Keep;
import androidx.core.content.FileProvider;

import com.mclauncher.app.MCLauncherApplication;

import java.io.File;

/** Compatibility callback surface expected by the bundled native AWT bridge. */
@Keep
public final class CallbackBridge {
    private CallbackBridge() {}

    @Keep
    public static void openLink(String target) {
        if (target == null || target.trim().isEmpty()) return;
        final Context context = MCLauncherApplication.getInstance();
        try {
            final Intent intent = new Intent(Intent.ACTION_VIEW);
            final Uri uri;
            final String lower = target.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("http://") || lower.startsWith("https://") ||
                    lower.startsWith("mailto:") || lower.startsWith("market:")) {
                uri = Uri.parse(target);
            } else {
                String path = target;
                if (path.startsWith("file://")) path = path.substring(7);
                else if (path.startsWith("file:")) path = path.substring(5);
                File file = new File(path);
                uri = FileProvider.getUriForFile(
                        context,
                        context.getPackageName() + ".files",
                        file
                );
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            intent.setData(uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable ignored) {
            // Opening a link/path is optional; never allow it to crash the game VM.
        }
    }
}
