package com.carriez.flutter_hbb

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import hbb.KeyEventConverter
import hbb.MessageOuterClass.KeyEvent
import hbb.MessageOuterClass.KeyboardMode
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max

/**
 * Root-based Input and System Manager.
 * Enables remote touch, mouse, key events, and screen wake/dismiss on Android
 * without relying on AccessibilityService, even on secure lock screens (Keyguard).
 */
object RootInputManager {
    private const val TAG = "RootInputManager"

    const val MODE_ACCESSIBILITY = "accessibility"
    const val MODE_ROOT = "root"

    private var suProcess: Process? = null
    private var suWriter: BufferedWriter? = null
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var rootAvailable: Boolean? = null

    @Volatile
    var userDisabled: Boolean = false

    // Touch and mouse tracking state
    private var downX = 0
    private var downY = 0
    private var curX = 0
    private var curY = 0
    private var downTime = 0L
    private var isDown = false

    fun getInputControlMode(context: Context): String {
        val prefs = context.getSharedPreferences(KEY_SHARED_PREFERENCES, Context.MODE_PRIVATE)
        return prefs.getString("input_control_mode", MODE_ACCESSIBILITY) ?: MODE_ACCESSIBILITY
    }

    fun setInputControlMode(context: Context, mode: String) {
        val prefs = context.getSharedPreferences(KEY_SHARED_PREFERENCES, Context.MODE_PRIVATE)
        prefs.edit().putString("input_control_mode", mode).apply()
        Log.i(TAG, "Input control mode set to: $mode")
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
            }
            callback(available)
        }
    }

    /**
     * Silently enable AccessibilityService via secure settings using root privileges.
     * This provides smooth 120Hz native gesture injection without opening settings dialogs.
     */
    fun enableAccessibilitySilently(context: Context) {
        val pkg = context.packageName
        val serviceName = "$pkg/${InputService::class.java.canonicalName}"
        val d = '$'
        val cmd = """
            appops set $pkg ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null || true
            appops set $pkg BIND_ACCESSIBILITY_SERVICE allow 2>/dev/null || true
            current=${d}(settings get secure enabled_accessibility_services)
            if [ "${d}current" = "null" ] || [ -z "${d}current" ]; then
                settings put secure enabled_accessibility_services "$serviceName"
            else
                case ":${d}current:" in
                    *":$serviceName:"*) ;;
                    *) settings put secure enabled_accessibility_services "${d}current:$serviceName" ;;
                esac
            fi
            settings put secure accessibility_enabled 1
        """.trimIndent()
        execRootCmd(cmd)
        Log.i(TAG, "Sent silent accessibility enable command for $serviceName")
    }

    /**
     * Silently disable AccessibilityService via secure settings when user stops input.
     */
    fun disableAccessibilitySilently(context: Context) {
        val pkg = context.packageName
        val serviceName = "$pkg/${InputService::class.java.canonicalName}"
        val d = '$'
        val cmd = """
            current=${d}(settings get secure enabled_accessibility_services)
            if [ "${d}current" != "null" ] && [ -n "${d}current" ]; then
                new_services=${d}(echo "${d}current" | tr ':' '\n' | grep -v "$serviceName" | tr '\n' ':' | sed 's/:${d}//')
                settings put secure enabled_accessibility_services "${d}new_services"
            fi
        """.trimIndent()
        execRootCmd(cmd)
        Log.i(TAG, "Sent silent accessibility disable command for $serviceName")
    }

    /**
     * Prevent system battery saver / OEM killers from terminating RustDesk when screen locks.
     */
    fun protectProcessAndWhitelist(context: Context) {
        val pkg = context.packageName
        val myPid = android.os.Process.myPid()
        val cmd = """
            dumpsys deviceidle whitelist +$pkg 2>/dev/null || true
            pm grant $pkg android.permission.CAPTURE_SECURE_VIDEO_OUTPUT 2>/dev/null || true
            pm grant $pkg android.permission.CAPTURE_VIDEO_OUTPUT 2>/dev/null || true
            pm grant $pkg android.permission.INTERNAL_SYSTEM_WINDOW 2>/dev/null || true
            pm grant $pkg android.permission.SYSTEM_ALERT_WINDOW 2>/dev/null || true
            pm grant $pkg android.permission.READ_FRAME_BUFFER 2>/dev/null || true
            pm grant $pkg android.permission.WAKE_LOCK 2>/dev/null || true
            appops set $pkg PROJECT_MEDIA allow 2>/dev/null || true
            cmd appops set $pkg RUN_IN_BACKGROUND allow 2>/dev/null || true
            cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true
            cmd appops set $pkg AUTO_START allow 2>/dev/null || true
            cmd appops set $pkg BOOT_COMPLETED allow 2>/dev/null || true
            cmd appops set $pkg SYSTEM_ALERT_WINDOW allow 2>/dev/null || true
            cmd appops set $pkg START_FOREGROUND allow 2>/dev/null || true
            cmd appops set $pkg WAKE_LOCK allow 2>/dev/null || true
            cmd appops set $pkg 10008 allow 2>/dev/null || true
            cmd appops set $pkg 10021 allow 2>/dev/null || true
            am set-standby-bucket $pkg active 2>/dev/null || true
            echo -900 > /proc/$myPid/oom_score_adj 2>/dev/null || true
        """.trimIndent()
        execRootCmd(cmd)
        Log.i(TAG, "Applied battery whitelist, secure capture permissions and OOM protection for $pkg (pid $myPid)")
    }

    private fun checkSu(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            Thread {
                try {
                    process.inputStream.bufferedReader().use { r ->
                        while (r.readLine() != null) {}
                    }
                } catch (ignored: Exception) {}
            }.apply { isDaemon = true; start() }
            Thread {
                try {
                    process.errorStream.bufferedReader().use { r ->
                        while (r.readLine() != null) {}
                    }
                } catch (ignored: Exception) {}
            }.apply { isDaemon = true; start() }
            val exitCode = process.waitFor()
            Log.i(TAG, "checkSu exit code: $exitCode")
            exitCode == 0
        } catch (e: Exception) {
            Log.w(TAG, "checkSu error: ${e.message}")
            false
        }
    }

    private var lastWheelTime = 0L

    @Synchronized
    private fun getShellWriter(): BufferedWriter? {
        if (suWriter != null && suProcess != null) {
            try {
                suProcess!!.exitValue()
                // Process is terminated
                suProcess = null
                suWriter = null
            } catch (e: IllegalThreadStateException) {
                // Process is still running
                return suWriter
            }
        }
        try {
            val p = Runtime.getRuntime().exec("su")
            Thread {
                try {
                    p.inputStream.bufferedReader().use { r ->
                        while (r.readLine() != null) {}
                    }
                } catch (ignored: Exception) {}
            }.apply { isDaemon = true; start() }
            Thread {
                try {
                    p.errorStream.bufferedReader().use { r ->
                        while (r.readLine() != null) {}
                    }
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
                Log.e(TAG, "Error executing root command: $cmd", e)
                try {
                    suProcess?.destroy()
                } catch (ignored: Exception) {}
                suProcess = null
                suWriter = null
            }
        }
    }

    /**
     * Wake screen and dismiss lockscreen keyguard if possible.
     */
    fun wakeAndDismissKeyguard() {
        execRootCmd("input keyevent 224; wm dismiss-keyguard; input swipe 500 1500 500 300 150")
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
     * Dispatch mouse input via root without AccessibilityService.
     */
    fun onMouseInput(mask: Int, _x: Int, _y: Int) {
        val x = max(0, _x) * SCREEN_INFO.scale
        val y = max(0, _y) * SCREEN_INFO.scale

        when (mask) {
            0, LEFT_MOVE -> {
                curX = x
                curY = y
            }
            LEFT_DOWN -> {
                downX = x
                downY = y
                curX = x
                curY = y
                downTime = SystemClock.uptimeMillis()
                isDown = true
            }
            LEFT_UP -> {
                val now = SystemClock.uptimeMillis()
                val targetDownX = if (isDown) downX else x
                val targetDownY = if (isDown) downY else y
                val targetCurX = if (x > 0 || y > 0) x else curX
                val targetCurY = if (x > 0 || y > 0) y else curY
                val duration = if (isDown) max(50L, now - downTime) else 50L
                isDown = false

                val dist = hypot((targetCurX - targetDownX).toDouble(), (targetCurY - targetDownY).toDouble())
                if (dist < 25) {
                    if (duration < 400L) {
                        execRootCmd("input tap $targetDownX $targetDownY")
                    } else {
                        execRootCmd("input swipe $targetDownX $targetDownY $targetDownX $targetDownY 600")
                    }
                } else {
                    val swipeDuration = max(100L, kotlin.math.min(400L, duration))
                    execRootCmd("input swipe $targetDownX $targetDownY $targetCurX $targetCurY $swipeDuration")
                }
            }
            RIGHT_UP -> {
                execRootCmd("input keyevent 4") // BACK
            }
            BACK_UP -> {
                execRootCmd("input keyevent 4") // BACK
            }
            WHEEL_BUTTON_UP -> {
                execRootCmd("input keyevent 3") // HOME
            }
            WHEEL_DOWN -> {
                val now = SystemClock.uptimeMillis()
                if (now - lastWheelTime > 120L) {
                    lastWheelTime = now
                    val targetY = max(0, y - 350)
                    execRootCmd("input swipe $x $y $x $targetY 100")
                }
            }
            WHEEL_UP -> {
                val now = SystemClock.uptimeMillis()
                if (now - lastWheelTime > 120L) {
                    lastWheelTime = now
                    val targetY = y + 350
                    execRootCmd("input swipe $x $y $x $targetY 100")
                }
            }
        }
    }

    /**
     * Dispatch touch input via root without AccessibilityService.
     */
    fun onTouchInput(mask: Int, _x: Int, _y: Int) {
        val x = max(0, _x) * SCREEN_INFO.scale
        val y = max(0, _y) * SCREEN_INFO.scale

        when (mask) {
            TOUCH_PAN_START -> {
                downX = x
                downY = y
                curX = x
                curY = y
                downTime = SystemClock.uptimeMillis()
                isDown = true
            }
            TOUCH_PAN_UPDATE -> {
                curX -= _x * SCREEN_INFO.scale
                curY -= _y * SCREEN_INFO.scale
                curX = max(0, curX)
                curY = max(0, curY)
            }
            TOUCH_PAN_END -> {
                val now = SystemClock.uptimeMillis()
                val targetDownX = if (isDown) downX else x
                val targetDownY = if (isDown) downY else y
                val targetEndX = if (_x > 0 || _y > 0) x else curX
                val targetEndY = if (_x > 0 || _y > 0) y else curY
                val duration = if (isDown) max(50L, now - downTime) else 50L
                isDown = false

                val dist = hypot((targetEndX - targetDownX).toDouble(), (targetEndY - targetDownY).toDouble())
                if (dist < 25) {
                    if (duration < 400L) {
                        execRootCmd("input tap $targetDownX $targetDownY")
                    } else {
                        execRootCmd("input swipe $targetDownX $targetDownY $targetDownX $targetDownY 600")
                    }
                } else {
                    val swipeDuration = max(100L, kotlin.math.min(400L, duration))
                    execRootCmd("input swipe $targetDownX $targetDownY $targetEndX $targetEndY $swipeDuration")
                }
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
                execRootCmd("input keyevent ${androidKeyEvent.keyCode}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "onKeyEvent error: ${e.message}")
        }
    }

    /**
     * Release su resources when service destroyed.
     */
    fun destroy() {
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
