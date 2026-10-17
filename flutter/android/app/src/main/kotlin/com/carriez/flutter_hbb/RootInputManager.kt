package com.carriez.flutter_hbb

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import hbb.KeyEventConverter
import hbb.MessageOuterClass.KeyEvent
import hbb.MessageOuterClass.KeyboardMode
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.max

/**
 * Root-based Input and System Manager.
 * Features a persistent Scrcpy-style RootInputServer running via app_process (UID 0)
 * connected via high-speed anonymous pipe (<0.1ms latency) with queue buffering.
 */
object RootInputManager {
    private const val TAG = "RootInputManager"

    const val MODE_ACCESSIBILITY = "accessibility"
    const val MODE_ROOT = "root"

    // InputService mask constants
    private const val LEFT_MOVE = 8
    private const val LEFT_DOWN = 9
    private const val LEFT_UP = 10
    private const val RIGHT_UP = 18
    private const val WHEEL_BUTTON_UP = 34
    private const val BACK_UP = 66
    private const val WHEEL_DOWN = 523331
    private const val WHEEL_UP = 963

    private const val TOUCH_PAN_START = 4
    private const val TOUCH_PAN_UPDATE = 5
    private const val TOUCH_PAN_END = 6

    @Volatile
    private var rootAvailable: Boolean? = null

    @Volatile
    var userDisabled: Boolean = false

    // App_process daemon management
    @Volatile
    private var isWorkerRunning = false
    @Volatile
    private var isServerReady = false
    private var serverProcess: Process? = null
    private var serverWriter: BufferedWriter? = null

    // Command queue prevents dropping the initial ACTION_DOWN while server starts up
    private val cmdQueue = LinkedBlockingQueue<String>(500)

    // Shell management for system root commands
    private var suProcess: Process? = null
    private var suWriter: BufferedWriter? = null
    private val executor = Executors.newSingleThreadExecutor()

    // Tracking state
    private var downX = 0
    private var downY = 0
    private var downTime = 0L
    private var curX = 0
    private var curY = 0
    private var leftButtonDown = false
    private var isPanDown = false
    private var wasServerMouseDownSent = false
    private var wasServerTouchDownSent = false

    fun getInputControlMode(context: Context): String {
        val prefs = context.getSharedPreferences(KEY_SHARED_PREFERENCES, Context.MODE_PRIVATE)
        return prefs.getString("input_control_mode", MODE_ACCESSIBILITY) ?: MODE_ACCESSIBILITY
    }

    fun setInputControlMode(context: Context, mode: String) {
        val prefs = context.getSharedPreferences(KEY_SHARED_PREFERENCES, Context.MODE_PRIVATE)
        prefs.edit().putString("input_control_mode", mode).apply()
        Log.i(TAG, "Input control mode set to: $mode")
        if (mode == MODE_ROOT && isRootAvailable()) {
            startServer(context)
        }
    }

    /**
     * Check if root (su) access is available on this device.
     */
    fun isRootAvailable(): Boolean {
        if (userDisabled) return false
        return rootAvailable == true
    }

    /**
     * Asynchronously check root availability without blocking UI thread.
     */
    fun checkRootAsync(callback: (Boolean) -> Unit) {
        if (userDisabled) {
            callback(false)
            return
        }
        if (rootAvailable == true) {
            callback(true)
            return
        }
        executor.execute {
            val available = checkSu()
            rootAvailable = available
            callback(available)
        }
    }

    /**
     * Explicitly request root authorization from Magisk / KernelSU / APatch.
     */
    fun requestRoot(context: Context, callback: (Boolean) -> Unit) {
        executor.execute {
            userDisabled = false
            val available = checkSu()
            rootAvailable = available
            if (available) {
                grantMediaProjectionPermission(context)
                wakeAndDismissKeyguard()
                protectProcessAndWhitelist(context)
                startServer(context)
            }
            callback(available)
        }
    }

    /**
     * Launch or verify the Scrcpy-style RootInputServer daemon via app_process under root.
     */
    @Synchronized
    fun startServer(context: Context) {
        if (isWorkerRunning) return
        isWorkerRunning = true

        Thread({
            while (isWorkerRunning) {
                try {
                    launchServerProcess(context)
                    // Consume touch & key commands from queue and stream to app_process stdin pipe (<0.1ms latency)
                    while (isWorkerRunning && isServerReady && serverWriter != null) {
                        val cmd = cmdQueue.take()
                        serverWriter?.write(cmd)
                        serverWriter?.newLine()
                        serverWriter?.flush()
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "RootInputServer disconnected, restarting in 1s...", e)
                    isServerReady = false
                    try { serverWriter?.close() } catch (ignored: Throwable) {}
                    try { serverProcess?.destroy() } catch (ignored: Throwable) {}
                    serverWriter = null
                    serverProcess = null
                    try { Thread.sleep(1000) } catch (ignored: Throwable) {}
                }
            }
        }, "RootInputWorker").apply { isDaemon = true; start() }
    }

    private fun launchServerProcess(context: Context) {
        val apkPath = context.packageCodePath
        val className = "com.carriez.flutter_hbb.RootInputServer"
        Log.i(TAG, "Launching RootInputServer from $apkPath ...")

        val proc = ProcessBuilder("su").redirectErrorStream(false).start()
        serverProcess = proc

        val out = BufferedWriter(OutputStreamWriter(proc.outputStream))
        val inp = BufferedReader(InputStreamReader(proc.inputStream))
        val err = BufferedReader(InputStreamReader(proc.errorStream))

        Thread({
            var errLine: String?
            try {
                while (err.readLine().also { errLine = it } != null) {
                    Log.e(TAG, "[Server-ERR] $errLine")
                }
            } catch (ignored: Throwable) {}
        }, "RootInputErrReader").apply { isDaemon = true; start() }

        out.write("export CLASSPATH=\"$apkPath\"; if [ -x /system/bin/app_process ]; then exec /system/bin/app_process / $className; else exec app_process / $className; fi\n")
        out.flush()

        val readThread = Thread({
            try {
                var line: String?
                while (inp.readLine().also { line = it } != null) {
                    Log.d(TAG, "[Server-OUT] $line")
                    if (line!!.contains("RUSTDESK_ROOT_SERVER_READY")) {
                        serverWriter = out
                        isServerReady = true
                        Log.i(TAG, "RootInputServer is READY via high-speed anonymous pipe!")
                        break
                    }
                }
            } catch (ignored: Throwable) {}
        }, "RootInputOutReader")
        readThread.isDaemon = true
        readThread.start()
        readThread.join(3500)

        if (!isServerReady) {
            try { out.close() } catch (ignored: Throwable) {}
            try { proc.destroy() } catch (ignored: Throwable) {}
            serverWriter = null
            serverProcess = null
            throw RuntimeException("RootInputServer failed to report READY")
        }
    }

    private fun enqueueCmd(cmd: String) {
        if (!cmdQueue.offer(cmd)) {
            cmdQueue.poll() // Discard oldest command if queue is full to preserve real-time responsiveness
            cmdQueue.offer(cmd)
        }
    }

    private fun getScale(): Int {
        val s = SCREEN_INFO.scale
        return if (s > 0) s else 1
    }

    private fun toPhysicalX(x: Int): Int = max(0, x) * getScale()
    private fun toPhysicalY(y: Int): Int = max(0, y) * getScale()

    /**
     * Dispatch mouse input via root without AccessibilityService.
     * Aligned with InputService.kt mask definitions.
     */
    fun onMouseInput(mask: Int, _x: Int, _y: Int) {
        val x = toPhysicalX(_x)
        val y = toPhysicalY(_y)

        // mask == 0: hover/move without left button pressed
        // mask == LEFT_MOVE (8): move while left button is held
        if (mask == 0 || mask == LEFT_MOVE) {
            curX = x
            curY = y
            // CRITICAL: Only send MOVE when left button is pressed (dragging)!
            // Moving mouse without left button NEVER injects events, completely eliminating system mouse cursors!
            if (leftButtonDown) {
                if (isServerReady) {
                    enqueueCmd("M $curX $curY")
                }
            }
            return
        }

        if (mask == LEFT_DOWN) {
            downX = x
            downY = y
            curX = x
            curY = y
            downTime = SystemClock.uptimeMillis()
            leftButtonDown = true
            if (isServerReady) {
                enqueueCmd("D $curX $curY")
                wasServerMouseDownSent = true
            } else {
                wasServerMouseDownSent = false
            }
            return
        }

        if (mask == LEFT_UP) {
            curX = x
            curY = y
            if (leftButtonDown) {
                leftButtonDown = false
                if (isServerReady && wasServerMouseDownSent) {
                    enqueueCmd("U $curX $curY")
                    wasServerMouseDownSent = false
                    return
                }
                wasServerMouseDownSent = false
                // Fallback to direct root input if server is not ready or down event wasn't sent to server!
                val duration = SystemClock.uptimeMillis() - downTime
                val dist = kotlin.math.hypot((curX - downX).toDouble(), (curY - downY).toDouble())
                if (dist < 25) {
                    if (duration < 400L) {
                        execRootCmd("input tap $downX $downY")
                    } else {
                        execRootCmd("input swipe $downX $downY $downX $downY 600")
                    }
                } else {
                    val swipeDuration = kotlin.math.max(100L, kotlin.math.min(400L, duration))
                    execRootCmd("input swipe $downX $downY $curX $curY $swipeDuration")
                }
            }
            return
        }

        if (mask == RIGHT_UP || mask == BACK_UP) {
            if (isServerReady) {
                enqueueCmd("K 4") // KEYCODE_BACK
            } else {
                execRootCmd("input keyevent 4")
            }
            return
        }

        if (mask == WHEEL_BUTTON_UP) {
            if (isServerReady) {
                enqueueCmd("K 3") // KEYCODE_HOME
            } else {
                execRootCmd("input keyevent 3")
            }
            return
        }

        if (mask == WHEEL_DOWN) {
            val targetY = max(0, curY - 350)
            if (isServerReady) {
                enqueueCmd("S $curX $curY $curX $targetY 100")
            } else {
                execRootCmd("input swipe $curX $curY $curX $targetY 100")
            }
            return
        }

        if (mask == WHEEL_UP) {
            val targetY = curY + 350
            if (isServerReady) {
                enqueueCmd("S $curX $curY $curX $targetY 100")
            } else {
                execRootCmd("input swipe $curX $curY $curX $targetY 100")
            }
            return
        }
    }

    /**
     * Dispatch touch input via root without AccessibilityService.
     * Aligned with InputService.kt mask definitions.
     */
    fun onTouchInput(mask: Int, _x: Int, _y: Int) {
        val scale = getScale()
        when (mask) {
            TOUCH_PAN_START -> {
                curX = max(0, _x) * scale
                curY = max(0, _y) * scale
                downX = curX
                downY = curY
                downTime = SystemClock.uptimeMillis()
                isPanDown = true
                if (isServerReady) {
                    enqueueCmd("D $curX $curY")
                    wasServerTouchDownSent = true
                } else {
                    wasServerTouchDownSent = false
                }
            }
            TOUCH_PAN_UPDATE -> {
                curX = max(0, curX - _x * scale)
                curY = max(0, curY - _y * scale)
                if (isServerReady && wasServerTouchDownSent) {
                    enqueueCmd("M $curX $curY")
                }
            }
            TOUCH_PAN_END -> {
                if (isPanDown) {
                    isPanDown = false
                    if (isServerReady && wasServerTouchDownSent) {
                        enqueueCmd("U $curX $curY")
                        wasServerTouchDownSent = false
                    } else {
                        wasServerTouchDownSent = false
                        val duration = SystemClock.uptimeMillis() - downTime
                        val dist = kotlin.math.hypot((curX - downX).toDouble(), (curY - downY).toDouble())
                        if (dist < 25) {
                            execRootCmd("input tap $downX $downY")
                        } else {
                            val swipeDuration = kotlin.math.max(100L, kotlin.math.min(400L, duration))
                            execRootCmd("input swipe $downX $downY $curX $curY $swipeDuration")
                        }
                    }
                }
                curX = max(0, _x) * scale
                curY = max(0, _y) * scale
            }
        }
    }

    /**
     * Dispatch key event via root without AccessibilityService.
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun onKeyEvent(data: ByteArray) {
        try {
            val keyEvent = KeyEvent.parseFrom(data)
            val keyboardMode = keyEvent.mode

            var textToCommit: String? = null
            if (keyEvent.hasSeq()) {
                textToCommit = keyEvent.seq
            } else if (keyboardMode == KeyboardMode.Legacy) {
                if (keyEvent.hasChr() && (keyEvent.down || keyEvent.press)) {
                    val chr = keyEvent.chr
                    if (chr != null) {
                        textToCommit = String(Character.toChars(chr))
                    }
                }
            }

            if (!textToCommit.isNullOrEmpty()) {
                val escaped = textToCommit.replace("'", "'\\''")
                execRootCmd("input text '$escaped'")
                return
            }

            val androidKeyEvent = KeyEventConverter.toAndroidKeyEvent(keyEvent)
            if (androidKeyEvent != null) {
                if (isServerReady) {
                    if (keyEvent.down) {
                        enqueueCmd("KD ${androidKeyEvent.keyCode}")
                    } else {
                        enqueueCmd("KU ${androidKeyEvent.keyCode}")
                    }
                } else {
                    if (keyEvent.down) {
                        execRootCmd("input keyevent ${androidKeyEvent.keyCode}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onKeyEvent error: ${e.message}")
        }
    }

    /**
     * Wake screen and dismiss lockscreen keyguard if possible.
     */
    fun wakeAndDismissKeyguard() {
        enqueueCmd("W")
        execRootCmd("input keyevent 224; input keyevent 82; wm dismiss-keyguard")
    }

    /**
     * Check if su binary exists and is executable.
     */
    private fun checkSu(): Boolean {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val line = reader.readLine()
            val isRoot = line?.contains("uid=0") == true
            process.waitFor()
            isRoot
        } catch (e: Exception) {
            false
        } finally {
            try {
                process?.destroy()
            } catch (ignored: Exception) {}
        }
    }

    private fun isProcAlive(p: Process?): Boolean {
        if (p == null) return false
        return try {
            p.exitValue()
            false
        } catch (e: IllegalThreadStateException) {
            true
        }
    }

    @Synchronized
    private fun getShellWriter(): BufferedWriter? {
        if (suWriter != null && isProcAlive(suProcess)) {
            return suWriter
        }
        try {
            suProcess?.destroy()
        } catch (ignored: Exception) {}
        try {
            val p = Runtime.getRuntime().exec("su")
            Thread {
                try {
                    p.inputStream.bufferedReader().use { r -> while (r.readLine() != null) {} }
                } catch (ignored: Exception) {}
            }.apply { isDaemon = true; start() }
            Thread {
                try {
                    p.errorStream.bufferedReader().use { r -> while (r.readLine() != null) {} }
                } catch (ignored: Exception) {}
            }.apply { isDaemon = true; start() }
            val writer = BufferedWriter(OutputStreamWriter(p.outputStream))
            writer.write("exec 1>/dev/null 2>&1\n")
            writer.flush()
            suProcess = p
            suWriter = writer
            Log.i(TAG, "Persistent su shell process initiated successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start persistent su shell", e)
            suProcess = null
            suWriter = null
        }
        return suWriter
    }

    /**
     * Execute a shell command with root privilege asynchronously via persistent shell.
     */
    fun execRootCmd(cmd: String) {
        executor.execute {
            try {
                val writer = getShellWriter()
                if (writer != null) {
                    writer.write(cmd)
                    writer.write("\n")
                    writer.flush()
                } else {
                    val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                    Thread {
                        try {
                            p.inputStream.bufferedReader().use { r -> while (r.readLine() != null) {} }
                        } catch (ignored: Exception) {}
                    }.apply { isDaemon = true; start() }
                    Thread {
                        try {
                            p.errorStream.bufferedReader().use { r -> while (r.readLine() != null) {} }
                        } catch (ignored: Exception) {}
                    }.apply { isDaemon = true; start() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing root command via persistent shell: $cmd, retrying one-shot su", e)
                try {
                    suProcess?.destroy()
                } catch (ignored: Exception) {}
                suProcess = null
                suWriter = null
                try {
                    val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                    p.waitFor()
                } catch (ignored: Exception) {}
            }
        }
    }

    /**
     * Grant MediaProjection permission silently via root appops to avoid capture prompt.
     */
    fun grantMediaProjectionPermission(context: Context) {
        val pkg = context.packageName
        execRootCmd("""
            appops set $pkg PROJECT_MEDIA allow 2>/dev/null || true
            pm grant $pkg android.permission.CAPTURE_SECURE_VIDEO_OUTPUT 2>/dev/null || true
            pm grant $pkg android.permission.CAPTURE_VIDEO_OUTPUT 2>/dev/null || true
            pm grant $pkg android.permission.READ_FRAME_BUFFER 2>/dev/null || true
            pm grant $pkg android.permission.INTERNAL_SYSTEM_WINDOW 2>/dev/null || true
        """.trimIndent())
    }

    /**
     * Install Magisk / KernelSU module to convert RustDesk into a /system/priv-app
     * so that CAPTURE_SECURE_VIDEO_OUTPUT is granted natively by Android, enabling
     * native 60fps hardware-accelerated lockscreen capture.
     */
    fun installMagiskPrivilegedModule(context: Context): Boolean {
        return try {
            val apkPath = context.packageCodePath
            val pkg = context.packageName
            val modDir = "/data/adb/modules/rustdesk_system_priv"
            val cmd = """
                mkdir -p $modDir/system/priv-app/rustdesk
                mkdir -p $modDir/system/etc/permissions
                cat << 'EOF' > $modDir/module.prop
id=rustdesk_system_priv
name=RustDesk System Privileged App
version=1.0
versionCode=1
author=RustDesk
description=Grants CAPTURE_SECURE_VIDEO_OUTPUT permission to RustDesk to enable native 60fps lock screen hardware capture.
EOF
                cp "$apkPath" $modDir/system/priv-app/rustdesk/rustdesk.apk
                chmod 644 $modDir/system/priv-app/rustdesk/rustdesk.apk
                cat << 'EOF' > $modDir/system/etc/permissions/privapp-permissions-rustdesk.xml
<?xml version="1.0" encoding="utf-8"?>
<permissions>
    <privapp-permissions package="$pkg">
        <permission name="android.permission.CAPTURE_SECURE_VIDEO_OUTPUT"/>
        <permission name="android.permission.INTERNAL_SYSTEM_WINDOW"/>
        <permission name="android.permission.READ_FRAME_BUFFER"/>
    </privapp-permissions>
</permissions>
EOF
                chmod 644 $modDir/system/etc/permissions/privapp-permissions-rustdesk.xml
                touch $modDir/auto_mount
            """.trimIndent()
            execRootCmd(cmd)
            Log.i(TAG, "Magisk/KernelSU privileged module written to $modDir")
            true
        } catch (e: Exception) {
            Log.e(TAG, "installMagiskPrivilegedModule error", e)
            false
        }
    }

    /**
     * Silently enable RustDesk's Accessibility Service using root.
     */
    fun enableAccessibilitySilently(context: Context) {
        val serviceName = "${context.packageName}/com.carriez.flutter_hbb.InputService"
        val d = '$'
        execRootCmd("""
            current_services=${d}(settings get secure enabled_accessibility_services)
            if [ -z "${d}current_services" ] || [ "${d}current_services" = "null" ]; then
                new_services="$serviceName"
            else
                case ":${d}current_services:" in
                    *":$serviceName:"*)
                        new_services="${d}current_services"
                        ;;
                    *)
                        new_services="${d}current_services:$serviceName"
                        ;;
                esac
            fi
            settings put secure enabled_accessibility_services "${d}new_services"
            settings put secure accessibility_enabled 1
        """.trimIndent())
    }

    /**
     * Disable battery optimization, set oom_adj and protect the process.
     */
    fun protectProcessAndWhitelist(context: Context) {
        val pkg = context.packageName
        val d = '$'
        execRootCmd("""
            dumpsys deviceidle whitelist +$pkg 2>/dev/null || true
            cmd deviceidle whitelist +$pkg 2>/dev/null || true
            appops set $pkg RUN_IN_BACKGROUND allow 2>/dev/null || true
            appops set $pkg RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true
            appops set $pkg SYSTEM_ALERT_WINDOW allow 2>/dev/null || true
            appops set $pkg START_FOREGROUND_SERVICES_FROM_BACKGROUND allow 2>/dev/null || true
            pid=${d}(pidof $pkg)
            if [ -n "${d}pid" ]; then
                echo -1000 > /proc/${d}pid/oom_score_adj 2>/dev/null || true
            fi
        """.trimIndent())
    }

    /**
     * Release su and server resources when service destroyed.
     */
    fun destroy() {
        enqueueCmd("EXIT")
        isWorkerRunning = false

        try {
            serverWriter?.close()
        } catch (ignored: Exception) {}
        try {
            serverProcess?.destroy()
        } catch (ignored: Exception) {}
        serverWriter = null
        serverProcess = null
        isServerReady = false

        try {
            suWriter?.close()
        } catch (ignored: Exception) {}
        try {
            suProcess?.destroy()
        } catch (ignored: Exception) {}
        suWriter = null
        suProcess = null
    }
}
