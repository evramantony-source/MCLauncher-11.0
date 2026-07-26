package net.kdt.pojavlaunch;

import androidx.annotation.Keep;

/**
 * Minimal Android-side ABI used by the bundled Caciocavallo/AWT bridge.
 * MCLauncher owns this class; no third-party launcher APK is required.
 */
@Keep
public final class AWTInputBridge {
    public static final int EVENT_TYPE_CHAR = 1000;
    public static final int EVENT_TYPE_CURSOR_POS = 1003;
    public static final int EVENT_TYPE_KEY = 1005;
    public static final int EVENT_TYPE_MOUSE_BUTTON = 1006;

    private AWTInputBridge() {}

    public static void sendKey(char keyChar, int keyCode) {
        nativeSendData(EVENT_TYPE_KEY, keyChar, keyCode, 1, 0);
        nativeSendData(EVENT_TYPE_KEY, keyChar, keyCode, 0, 0);
    }

    public static void sendKey(char keyChar, int keyCode, int state) {
        nativeSendData(EVENT_TYPE_KEY, keyChar, keyCode, state, 0);
    }

    public static void sendChar(char keyChar) {
        nativeSendData(EVENT_TYPE_CHAR, keyChar, 0, 0, 0);
    }

    public static void sendMousePress(int awtButtons, boolean down) {
        nativeSendData(EVENT_TYPE_MOUSE_BUTTON, awtButtons, down ? 1 : 0, 0, 0);
    }

    public static void sendMousePress(int awtButtons) {
        sendMousePress(awtButtons, true);
        sendMousePress(awtButtons, false);
    }

    public static void sendMousePos(int x, int y) {
        nativeSendData(EVENT_TYPE_CURSOR_POS, x, y, 0, 0);
    }

    public static native void nativeSendData(int type, int i1, int i2, int i3, int i4);
    public static native void nativeClipboardReceived(String data, String mimeTypeSub);
    public static native void nativeMoveWindow(int xOffset, int yOffset);
}
