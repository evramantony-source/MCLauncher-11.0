package git.artdeell.dnbootstrap.glfw;

import android.graphics.Bitmap;

/** Android cursor image used by the bundled dnbootstrap GLFW bridge. */
public final class GLFWCursor {
    public final Bitmap bitmap;
    public final int hotX;
    public final int hotY;

    public GLFWCursor(Bitmap bitmap, int hotX, int hotY) {
        this.bitmap = bitmap;
        this.hotX = hotX;
        this.hotY = hotY;
    }
}
