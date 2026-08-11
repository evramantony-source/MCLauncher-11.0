package com.mclauncher.app.creation

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.mclauncher.app.engine.NativeEngineCoordinator
import com.mclauncher.app.engine.NativeLaunchBridge
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Executes one user-requested Gradle build in a disposable Android process.
 * The explicit Build button is the trust boundary: project Gradle scripts are
 * never run merely because a ZIP was imported or a file was edited. */
class LocalBuildActivity : Activity() {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildProgressView())
        val planPath = intent.getStringExtra(EXTRA_PLAN_PATH)
        val statusPath = intent.getStringExtra(EXTRA_STATUS_PATH)
        if (planPath.isNullOrBlank() || statusPath.isNullOrBlank()) {
            finishBuilder(null, null, "Local build request was incomplete")
            return
        }
        Thread({ runBuild(File(planPath), File(statusPath)) }, "mcl-local-gradle").start()
    }

    private fun runBuild(planFile: File, statusFile: File) {
        val logFile = File(statusFile.parentFile, "${statusFile.nameWithoutExtension}.log")
        val result = runCatching {
            require(NativeLaunchBridge.isAvailable) { NativeLaunchBridge.unavailableReason }
            val architecture = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val exitCode = runBlocking {
                NativeEngineCoordinator(applicationContext).runTool(planFile, architecture)
            }
            val logs = NativeLaunchBridge.nativeDrainLogs().orEmpty()
            logFile.parentFile?.mkdirs()
            if (!logFile.isFile || logFile.length() == 0L) {
                logFile.writeText(logs)
            } else if (logs.isNotBlank()) {
                logFile.appendText("\n--- MCLauncher log tail ---\n$logs\n")
            }
            require(exitCode == 0) {
                "Local Gradle exited with code $exitCode. Open ${logFile.name} in Android Files for details."
            }
            exitCode
        }
        finishBuilder(
            statusFile = statusFile,
            exitCode = result.getOrNull(),
            error = result.exceptionOrNull()?.message,
            logFile = logFile.takeIf(File::isFile)
        )
    }

    private fun finishBuilder(
        statusFile: File?,
        exitCode: Int?,
        error: String?,
        logFile: File? = null
    ) {
        statusFile?.let { file ->
            runCatching {
                file.parentFile?.mkdirs()
                val temporary = File(file.parentFile, "${file.name}.part")
                temporary.writeText(
                    json.encodeToString(
                        LocalBuildStatus(
                            finished = true,
                            exitCode = exitCode,
                            error = error,
                            logPath = logFile?.absolutePath
                        )
                    )
                )
                if (file.exists()) file.delete()
                check(temporary.renameTo(file)) { "Could not publish local build result" }
            }
        }
        Handler(Looper.getMainLooper()).post {
            finish()
            Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 350L)
        }
    }

    private fun buildProgressView(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(48, 48, 48, 48)
        addView(ProgressBar(this@LocalBuildActivity))
        addView(TextView(this@LocalBuildActivity).apply {
            text = "Building your project locally…\nThe first build downloads Gradle and project dependencies."
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 0)
        })
    }

    companion object {
        private const val EXTRA_PLAN_PATH = "local-plan-path"
        private const val EXTRA_STATUS_PATH = "local-status-path"

        fun start(context: Context, planFile: File, statusFile: File) {
            context.startActivity(
                Intent(context, LocalBuildActivity::class.java).apply {
                    putExtra(EXTRA_PLAN_PATH, planFile.absolutePath)
                    putExtra(EXTRA_STATUS_PATH, statusFile.absolutePath)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }
}
