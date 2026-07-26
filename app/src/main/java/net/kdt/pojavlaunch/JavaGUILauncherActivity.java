package net.kdt.pojavlaunch;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import androidx.annotation.Keep;

import com.mclauncher.app.MCLauncherApplication;

/**
 * Static clipboard ABI expected by the bundled AWT bridge. This deliberately is
 * not an Activity; the upstream native code only resolves these static methods.
 */
@Keep
public final class JavaGUILauncherActivity {
    private JavaGUILauncherActivity() {}

    private static ClipboardManager clipboard() {
        Context context = MCLauncherApplication.getInstance();
        return (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
    }

    @Keep
    public static void querySystemClipboard() {
        String text = "";
        try {
            ClipboardManager manager = clipboard();
            if (manager != null && manager.hasPrimaryClip() && manager.getPrimaryClip() != null &&
                    manager.getPrimaryClip().getItemCount() > 0) {
                CharSequence value = manager.getPrimaryClip().getItemAt(0)
                        .coerceToText(MCLauncherApplication.getInstance());
                if (value != null) text = value.toString();
            }
        } catch (Throwable ignored) {
        }
        try {
            AWTInputBridge.nativeClipboardReceived(text, "text/plain");
        } catch (UnsatisfiedLinkError ignored) {
            // The AWT bridge may not be loaded for a vanilla-only launch.
        }
    }

    @Keep
    public static void putClipboardData(String data, String mimeType) {
        try {
            ClipboardManager manager = clipboard();
            if (manager != null) {
                manager.setPrimaryClip(ClipData.newPlainText("Minecraft", data == null ? "" : data));
            }
        } catch (Throwable ignored) {
        }
    }
}
