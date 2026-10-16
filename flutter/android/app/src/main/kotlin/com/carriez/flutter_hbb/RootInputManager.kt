package com.carriez.flutter_hbb

import android.content.Context
import android.os.Build
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
                enableAccessibilitySilently(context)
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
            cmd appops set $pkg RUN_IN_BACKGROUND allow 2>/dev/null || true
            cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true
            cmd appops set $pkg AUTO_START allow 2>/dev/null || true
            cmd appops set $pkg BOOT_COMPLETED allow 2>/dev/null || true
            cmd appops set $pkg SYSTEM_ALERT_WINDOW allow 2>/dev/null || true
            cmd appops set $pkg START_FOREGROUND allow 2>/dev/null || true
            cmd appops set $pkg WAKE_LOCK allow 2>/dev/null || true
            am set-standby-bucket $pkg active 2>/dev/null || true
            echo -900 > /proc/$myPid/oom_score_adj 2>/dev/null || true
        """.trimIndent()
        execRootCmd(cmd)
        Log.i(TAG, "Applied battery whitelist and OOM protection for $pkg (pid $myPid)")
    }

    private fun checkSu(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val exitCode = process.waitFor()
            Log.i(TAG, "checkSu exit code: $exitCode")
            exitCode == 0
        } catch (e: Exception) {
            Log.w(TAG, "checkSu error: ${e.message}")
            false
        }
    }

    @Synchronized
    private fun getShellWriter(): BufferedWriter? {
        if (suWriter != null && suProcess != null) {
            return suWriter
        }
        try {
            val p = Runtime.getRuntime().exec("su")
            suProcess = p
            suWriter = BufferedWriter(OutputStreamWriter(p.outputStream))
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
                    Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
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
        execRootCmd("input keyevent 224; wm dismiss-keyguard")
    }

    /**
     * Grant MediaProjection permission silently via root appops to avoid capture prompt.
     */
    fun grantMediaProjectionPermission(context: Context) {
        val pkg = context.packageName
        execRootCmd("appops set $pkg PROJECT_MEDIA allow")
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
                downTime = System.currentTimeMillis()
                isDown = true
            }
            LEFT_UP -> {
                if (isDown) {
                    isDown = false
                    val duration = max(50L, System.currentTimeMillis() - downTime)
                    val dist = hypot((curX - downX).toDouble(), (curY - downY).toDouble())
                    if (dist < 30) {
                        if (duration < 500L) {
                            execRootCmd("input tap $downX $downY")
                        } else {
                            execRootCmd("input swipe $downX $downY $downX $downY $duration")
                        }
                    } else {
                        execRootCmd("input swipe $downX $downY $curX $curY $duration")
                    }
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
                // Scroll down (finger swipe up)
                val targetY = max(0, y - 300)
                execRootCmd("input swipe $x $y $x $targetY 150")
            }
            WHEEL_UP -> {
                // Scroll up (finger swipe down)
                val targetY = y + 300
                execRootCmd("input swipe $x $y $x $targetY 150")
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
                downTime = System.currentTimeMillis()
                isDown = true
            }
            TOUCH_PAN_UPDATE -> {
                curX -= _x * SCREEN_INFO.scale
                curY -= _y * SCREEN_INFO.scale
                curX = max(0, curX)
                curY = max(0, curY)
            }
            TOUCH_PAN_END -> {
                if (isDown) {
                    isDown = false
                    val duration = max(50L, System.currentTimeMillis() - downTime)
                    val dist = hypot((curX - downX).toDouble(), (curY - downY).toDouble())
                    if (dist < 30) {
                        if (duration < 500L) {
                            execRootCmd("input tap $downX $downY")
                        } else {
                            execRootCmd("input swipe $downX $downY $downX $downY $duration")
                        }
                    } else {
                        execRootCmd("input swipe $downX $downY $curX $curY $duration")
                    }
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
