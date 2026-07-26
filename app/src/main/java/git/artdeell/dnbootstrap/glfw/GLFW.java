package git.artdeell.dnbootstrap.glfw;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

import com.mclauncher.app.engine.NativeLaunchBridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Android-side contract required by the bundled dnbootstrap GLFW native library.
 *
 * MCLauncher owns this class; the native engine is loaded from MCLauncher's private
 * files and its initializer is invoked by libmclauncher. No second launcher APK is
 * installed or loaded on the device.
 */
@Keep
public final class GLFW {
    public interface GrabListener {
        void onGrabChanged(boolean grabbing);
    }

    public interface CursorListener {
        void onCursorChanged(@Nullable GLFWCursor cursor);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Context applicationContext;
    private static volatile GrabListener grabListener;
    private static volatile CursorListener cursorListener;
    private static volatile boolean grabbing;
    private static volatile GLFWCursor activeCursor;
    public static volatile double cursorX = 0.5;
    public static volatile double cursorY = 0.5;
    public static volatile ByteBuffer gamepadButtonBuffer;
    public static volatile FloatBuffer gamepadAxisBuffer;

    private GLFW() {}

    public static void initializeBridge(Context context) {
        applicationContext = context.getApplicationContext();
    }

    public static void setGrabListener(@Nullable GrabListener listener) {
        grabListener = listener;
        if (listener != null) MAIN.post(() -> listener.onGrabChanged(grabbing));
    }

    public static void setCursorListener(@Nullable CursorListener listener) {
        cursorListener = listener;
        if (listener != null) MAIN.post(() -> listener.onCursorChanged(activeCursor));
    }

    public static boolean isGrabbing() {
        return grabbing;
    }

    public static void compatibilityGrabChanged(boolean value) {
        receiveGrabState(value);
    }

    public static void compatibilityCursorShapeChanged(int ignoredShape) {
        // Legacy engines expose only an integer shape. Reset to Android's default
        // pointer; modern bundled GLFW supplies actual cursor bitmaps through useCursor.
        useCursor(null);
    }

    public static @Nullable GLFWCursor getActiveCursor() {
        return activeCursor;
    }

    @SuppressWarnings("unused") // Called from bundled native GLFW.
    @Keep
    private static void receiveGrabState(boolean value) {
        grabbing = value;
        NativeLaunchBridge.INSTANCE.nativeSyncPointerState(cursorX, cursorY, grabbing);
        GrabListener listener = grabListener;
        if (listener != null) MAIN.post(() -> listener.onGrabChanged(value));
    }

    @SuppressWarnings("unused") // Called from bundled native GLFW.
    @Keep
    private static void receiveCursorPos(double x, double y) {
        cursorX = x;
        cursorY = y;
        NativeLaunchBridge.INSTANCE.nativeSyncPointerState(cursorX, cursorY, grabbing);
    }

    @SuppressWarnings("unused") // Called from bundled native GLFW.
    @Keep
    private static GLFWCursor loadCursor(ByteBuffer pixels, int width, int height, int hotX, int hotY) {
        try {
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            ByteBuffer source = pixels.duplicate();
            source.rewind();
            bitmap.copyPixelsFromBuffer(source);
            return new GLFWCursor(bitmap, hotX, hotY);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("unused") // Called from bundled native GLFW.
    @Keep
    private static void useCursor(@Nullable GLFWCursor cursor) {
        activeCursor = cursor;
        CursorListener listener = cursorListener;
        if (listener != null) MAIN.post(() -> listener.onCursorChanged(cursor));
    }

    @SuppressWarnings("unused") // Called from bundled native GLFW.
    @Keep
    private static String getClipboardString() {
        Context context = applicationContext;
        if (context == null) return "";
        ClipboardManager manager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null || !manager.hasPrimaryClip() || manager.getPrimaryClip() == null || manager.getPrimaryClip().getItemCount() == 0) {
            return "";
        }
        CharSequence text = manager.getPrimaryClip().getItemAt(0).coerceToText(context);
        return text == null ? "" : text.toString();
    }

    @SuppressWarnings("unused") // Called from bundled native GLFW.
    @Keep
    private static void setClipboardString(String value) {
        Context context = applicationContext;
        if (context == null) return;
        ClipboardManager manager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager != null) manager.setPrimaryClip(ClipData.newPlainText("Minecraft", value == null ? "" : value));
    }

    @SuppressWarnings("unused") // Called from bundled native GLFW.
    @Keep
    private static void enableDirectGamepad(ByteBuffer buttons, ByteBuffer axes) {
        if (buttons == null || axes == null) return;
        gamepadButtonBuffer = buttons.order(ByteOrder.nativeOrder());
        gamepadAxisBuffer = axes.order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    // Implemented by the bundled libglfw.so. Declarations are retained so ART can
    // validate the exact Java/native ABI when the library initializes.
    @Keep public static native void initialize();
    @Keep public static native void sendKeyEvent(int glfwCode, int state, int mods);
    @Keep public static native void sendRawKeyEvent(int androidCode, int state, int mods, char codepoint);
    @Keep public static native void sendMouseEvent(int glfwMouseKey, int state, int mods);
    @Keep public static native void sendBulkUnicodeEvent(String input, int mods);
    @Keep public static native void sendScrollEvent(double xOffset, double yOffset);
    @Keep public static native void nativeSurfaceCreated(android.view.Surface surface);
    @Keep public static native void nativeSurfaceDestroyed();
    @Keep public static native void nativeSurfaceUpdated();
    @Keep public static native void nativeNotifyGamepadConnected();
}
