package net.kdt.pojavlaunch;

import android.content.Context;
import android.os.Process;

import java.io.FileWriter;
import java.io.IOException;

/**
 * Compatibility target for the pinned MojoLauncher JVM exit hook.
 *
 * <p>Minecraft runs inside an embedded HotSpot VM. If it calls System.exit or
 * aborts, MojoLauncher transfers that event back to Android's ART VM and calls
 * this method. The game and launcher share a process, so terminating that
 * process is the only safe response after the embedded VM has requested exit.
 */
public final class ExitActivity {
    private ExitActivity() {
    }

    public static void showExitMessage(Context context, int code, boolean isSignal) {
        String sessionLog = System.getenv("MCLAUNCHER_SESSION_LOG");
        if (sessionLog != null && !sessionLog.isEmpty()) {
            try (FileWriter writer = new FileWriter(sessionLog, true)) {
                writer.write(
                    "NATIVE: Embedded JVM requested process exit "
                        + code
                        + (isSignal ? " after a fatal signal" : "")
                        + "\n"
                );
            } catch (IOException ignored) {
                // The process must still terminate even when the diagnostic file
                // is unavailable.
            }
        }
        Process.killProcess(Process.myPid());
    }
}
