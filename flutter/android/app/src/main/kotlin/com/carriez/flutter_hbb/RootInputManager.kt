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
        rootAvailable?.let { return it }
        synchronized(this) {
            rootAvailable?.let { return it }
            val available = checkSu()
            rootAvailable = available
            return available
        }
    }

    private fun checkSu(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val exitCode = process.waitFor()
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
                    if (dist < 20 && duration < 500L) {
                        execRootCmd("input tap $downX $downY")
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
                    if (dist < 20 && duration < 500L) {
                        execRootCmd("input tap $downX $downY")
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
