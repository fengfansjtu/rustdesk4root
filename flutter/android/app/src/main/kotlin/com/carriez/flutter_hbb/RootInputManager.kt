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
import kotlin.math.hypot
import kotlin.math.max

/**
 * Root-based Input and System Manager.
 * Features a persistent Scrcpy-style RootInputServer running via app_process (UID 0)
 * for sub-millisecond, low-latency touch and key injection into Android's InputManager.
 */
object RootInputManager {
    private const val TAG = "RootInputManager"

    const val MODE_ACCESSIBILITY = "accessibility"
    const val MODE_ROOT = "root"

    private var suProcess: Process? = null
    private var suWriter: BufferedWriter? = null

    // Scrcpy-style daemon server
    private var serverProcess: Process? = null
    private var serverWriter: BufferedWriter? = null
    @Volatile
    private var isServerReady = false
    @Volatile
    private var isStartingServer = false

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
        if (isServerReady && serverWriter != null) return
        if (isStartingServer) return
        isStartingServer = true

        Thread {
            var p: Process? = null
            var writer: BufferedWriter? = null
            var reader: BufferedReader? = null
            try {
                val apkPath = context.packageCodePath
                Log.i(TAG, "Launching RootInputServer from $apkPath ...")
                p = Runtime.getRuntime().exec("su")
                writer = BufferedWriter(OutputStreamWriter(p.outputStream))
                reader = BufferedReader(InputStreamReader(p.inputStream))

                val errReader = p.errorStream.bufferedReader()
                Thread {
                    try {
                        var line: String?
                        while (errReader.readLine().also { line = it } != null) {
                            Log.d(TAG, "RootInputServer ERR: $line")
                        }
                    } catch (ignored: Throwable) {}
                }.apply { isDaemon = true; start() }

                writer.write("export CLASSPATH=$apkPath\n")
                writer.write("exec app_process / com.carriez.flutter_hbb.RootInputServer\n")
                writer.flush()

                var line: String? = null
                val readThread = Thread {
                    try {
                        line = reader.readLine()
                    } catch (ignored: Throwable) {}
                }
                readThread.start()
                readThread.join(3000)

                Log.i(TAG, "RootInputServer response: $line")
                if (line == "READY") {
                    serverProcess = p
                    serverWriter = writer
                    isServerReady = true
                    Log.i(TAG, "RootInputServer connected and ready for low-latency injection.")
                } else {
                    Log.w(TAG, "RootInputServer unexpected response: $line, will fallback to su shell")
                    isServerReady = false
                    try { writer.close() } catch (ignored: Throwable) {}
                    try { p.destroy() } catch (ignored: Throwable) {}
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to start RootInputServer", e)
                isServerReady = false
                try { writer?.close() } catch (ignored: Throwable) {}
                try { p?.destroy() } catch (ignored: Throwable) {}
            } finally {
                isStartingServer = false
            }
        }.apply { isDaemon = true; start() }
    }

    private fun sendServerCommand(cmd: String): Boolean {
        if (isServerReady && serverWriter != null) {
            try {
                serverWriter?.write(cmd)
                serverWriter?.write("\n")
                serverWriter?.flush()
                return true
            } catch (e: Exception) {
                Log.w(TAG, "RootInputServer write failed: ${e.message}, falling back")
                isServerReady = false
                try {
                    serverWriter?.close()
                } catch (ignored: Exception) {}
                try {
                    serverProcess?.destroy()
                } catch (ignored: Exception) {}
                serverWriter = null
                serverProcess = null
            }
        }
        return false
    }

    /**
     * Silently enable AccessibilityService via secure settings using root privileges.
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
        Log.i(TAG, "Applied battery whitelist and OOM protection for $pkg (pid $myPid)")
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
                suProcess = null
                suWriter = null
            } catch (e: IllegalThreadStateException) {
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
        if (sendServerCommand("W")) {
            // Server wakes up and calls dismiss-keyguard
        }
        val realW = if (SCREEN_INFO.width > 0) SCREEN_INFO.width * SCREEN_INFO.scale else 1080
        val realH = if (SCREEN_INFO.height > 0) SCREEN_INFO.height * SCREEN_INFO.scale else 2400
        val midX = realW / 2
        val startY = (realH * 0.85).toInt()
        val endY = (realH * 0.20).toInt()
        execRootCmd("input keyevent 224; sleep 0.15; wm dismiss-keyguard; input swipe $midX $startY $midX $endY 250")
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
     * Dispatch mouse input via root without AccessibilityService.
     */
    fun onMouseInput(mask: Int, _x: Int, _y: Int) {
        val x = max(0, _x) * SCREEN_INFO.scale
        val y = max(0, _y) * SCREEN_INFO.scale

        if (mask == 0 || mask == LEFT_MOVE) {
            curX = x
            curY = y
            if (isDown) {
                if (sendServerCommand("M $curX $curY")) {
                    return
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
            isDown = true
            if (sendServerCommand("D $curX $curY")) {
                return
            }
            return
        }

        if (mask == LEFT_UP) {
            isDown = false
            curX = x
            curY = y
            if (sendServerCommand("U $curX $curY")) {
                return
            }
            // Fallback if server is not ready
            val now = SystemClock.uptimeMillis()
            val duration = max(50L, now - downTime)
            val dist = hypot((curX - downX).toDouble(), (curY - downY).toDouble())
            if (dist < 25) {
                if (duration < 400L) {
                    execRootCmd("input tap $downX $downY")
                } else {
                    execRootCmd("input swipe $downX $downY $downX $downY 600")
                }
            } else {
                val swipeDuration = max(100L, kotlin.math.min(400L, duration))
                execRootCmd("input swipe $downX $downY $curX $curY $swipeDuration")
            }
            return
        }

        if (mask == RIGHT_UP || mask == BACK_UP) {
            if (sendServerCommand("KC 4")) return
            execRootCmd("input keyevent 4") // BACK
            return
        }

        if (mask == WHEEL_BUTTON_UP) {
            if (sendServerCommand("KC 3")) return
            execRootCmd("input keyevent 3") // HOME
            return
        }

        if (mask == WHEEL_DOWN) {
            val now = SystemClock.uptimeMillis()
            if (now - lastWheelTime > 80L) {
                lastWheelTime = now
                val targetY = max(0, y - 350)
                if (sendServerCommand("D $x $y") && sendServerCommand("M $x $targetY") && sendServerCommand("U $x $targetY")) {
                    return
                }
                execRootCmd("input swipe $x $y $x $targetY 100")
            }
            return
        }

        if (mask == WHEEL_UP) {
            val now = SystemClock.uptimeMillis()
            if (now - lastWheelTime > 80L) {
                lastWheelTime = now
                val targetY = y + 350
                if (sendServerCommand("D $x $y") && sendServerCommand("M $x $targetY") && sendServerCommand("U $x $targetY")) {
                    return
                }
                execRootCmd("input swipe $x $y $x $targetY 100")
            }
            return
        }
    }

    /**
     * Dispatch touch input via root without AccessibilityService.
     */
    fun onTouchInput(mask: Int, _x: Int, _y: Int) {
        when (mask) {
            TOUCH_PAN_START -> {
                curX = max(0, _x) * SCREEN_INFO.scale
                curY = max(0, _y) * SCREEN_INFO.scale
                downX = curX
                downY = curY
                downTime = SystemClock.uptimeMillis()
                isDown = true
                if (sendServerCommand("D $curX $curY")) {
                    return
                }
            }
            TOUCH_PAN_UPDATE -> {
                curX -= _x * SCREEN_INFO.scale
                curY -= _y * SCREEN_INFO.scale
                curX = max(0, curX)
                curY = max(0, curY)
                if (sendServerCommand("M $curX $curY")) {
                    return
                }
            }
            TOUCH_PAN_END -> {
                isDown = false
                if (sendServerCommand("U $curX $curY")) {
                    curX = max(0, _x) * SCREEN_INFO.scale
                    curY = max(0, _y) * SCREEN_INFO.scale
                    return
                }
                // Fallback if server is not ready
                val now = SystemClock.uptimeMillis()
                val duration = max(50L, now - downTime)
                val dist = hypot((curX - downX).toDouble(), (curY - downY).toDouble())
                if (dist < 25) {
                    if (duration < 400L) {
                        execRootCmd("input tap $downX $downY")
                    } else {
                        execRootCmd("input swipe $downX $downY $downX $downY 600")
                    }
                } else {
                    val swipeDuration = max(100L, kotlin.math.min(400L, duration))
                    execRootCmd("input swipe $downX $downY $curX $curY $swipeDuration")
                }
                curX = max(0, _x) * SCREEN_INFO.scale
                curY = max(0, _y) * SCREEN_INFO.scale
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
                val action = if (keyEvent.down) 0 else 1
                if (sendServerCommand("K ${androidKeyEvent.keyCode} $action")) {
                    return
                }
                execRootCmd("input keyevent ${androidKeyEvent.keyCode}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "onKeyEvent error: ${e.message}")
        }
    }

    /**
     * Release su and server resources when service destroyed.
     */
    fun destroy() {
        sendServerCommand("Q")
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
