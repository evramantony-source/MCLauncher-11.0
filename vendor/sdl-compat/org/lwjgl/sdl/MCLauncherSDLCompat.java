package org.lwjgl.sdl;

import org.lwjgl.opengl.GL;
import org.lwjgl.system.FunctionProvider;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryUtil;

/**
 * Snapshot-only bridge for Minecraft's strict SDL/LWJGL OpenGL identity check.
 *
 * The property is set only for Minecraft 26.3 Snapshot 6 and later. Every
 * function except glGetError continues through SDL's original native pointer.
 */
public final class MCLauncherSDLCompat {
    private static final String PROPERTY = "mclauncher.sdlOpenGLProcIdentity";

    private MCLauncherSDLCompat() {
    }

    public static long getProcAddress(long nameAddress) {
        if (nameAddress != 0L && Boolean.getBoolean(PROPERTY)) {
            try {
                String name = MemoryUtil.memASCII(nameAddress);
                if ("glGetError".equals(name)) {
                    FunctionProvider provider = GL.getFunctionProvider();
                    if (provider != null) {
                        long address = provider.getFunctionAddress(
                            MemoryUtil.memByteBufferNT1(nameAddress)
                        );
                        if (address != 0L) {
                            System.err.println(
                                "MCLauncher SDL/OpenGL proc identity: glGetError -> 0x"
                                    + Long.toHexString(address)
                            );
                            return address;
                        }
                    }
                }
            } catch (Throwable ignored) {
                // Preserve SDL's native behavior if LWJGL has not initialized yet.
            }
        }
        return JNI.invokePP(nameAddress, SDLVideo.Functions.GL_GetProcAddress);
    }
}
