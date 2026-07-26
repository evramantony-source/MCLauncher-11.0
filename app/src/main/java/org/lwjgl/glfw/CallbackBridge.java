package org.lwjgl.glfw;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import androidx.annotation.Keep;

import com.mclauncher.app.MCLauncherApplication;

/**
 * Legacy Android-side compatibility class for older patched LWJGL engines.
 * The self-contained Alpha 01 engine uses the dnbootstrap GLFW contract, while
 * this class keeps optional developer-imported Pojav-style engines usable.
 */
@Keep
public final class CallbackBridge {
    public static volatile int windowWidth;
    public static volatile int windowHeight;
    public static volatile int physicalWidth;
    public static volatile int physicalHeight;
    public static float mouseX;
    public static float mouseY;

    private CallbackBridge() {}

    @Keep
    private static String accessAndroidClipboard(int type, String copy) {
        Context context = MCLauncherApplication.getInstance();
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return "";
        if (type == 2000) {
            clipboard.setPrimaryClip(ClipData.newPlainText("MCLauncher", copy == null ? "" : copy));
            return "";
        }
        if (type == 2001 && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip() != null) {
            CharSequence text = clipboard.getPrimaryClip().getItemAt(0).coerceToText(context);
            return text == null ? "" : text.toString();
        }
        return "";
    }

    @Keep private static void onGrabStateChanged(boolean grabbing) {
        git.artdeell.dnbootstrap.glfw.GLFW.compatibilityGrabChanged(grabbing);
    }
    @Keep private static void onCursorShapeChanged(int shape) {
        git.artdeell.dnbootstrap.glfw.GLFW.compatibilityCursorShapeChanged(shape);
    }
    @Keep private static void onGraphicOutput() { }

    // Registered only when an optional legacy Pojav-style engine is imported.
    @Keep public static native void nativeSetUseInputStackQueue(boolean enabled);
    @Keep private static native boolean nativeSendChar(char codePoint);
    @Keep private static native boolean nativeSendCharMods(char codePoint, int modifiers);
    @Keep private static native void nativeSendKey(int key, int scanCode, int action, int modifiers);
    @Keep private static native void nativeSendCursorPos(float x, float y);
    @Keep private static native void nativeSendMouseButton(int button, int action, int modifiers);
    @Keep private static native void nativeSendScroll(double xOffset, double yOffset);
    @Keep private static native void nativeSendScreenSize(int width, int height);
    @Keep public static native void nativeSetWindowAttrib(int attribute, int value);
    @Keep public static native int getCurrentFps();
}
