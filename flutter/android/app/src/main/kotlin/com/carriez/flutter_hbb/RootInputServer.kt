package com.carriez.flutter_hbb

import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method

/**
 * High-performance Scrcpy-style Root Input Server.
 * Runs under UID 0 via `app_process` to inject MotionEvent and KeyEvent
 * directly via internal `InputManager.injectInputEvent` with ~0ms latency.
 */
object RootInputServer {
    private var injectMethod: Method? = null
    private var inputManager: Any? = null
    private var downTime = 0L

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val imClass = Class.forName("android.hardware.input.InputManager")
            val getInstance = imClass.getDeclaredMethod("getInstance")
            getInstance.isAccessible = true
            inputManager = getInstance.invoke(null)

            // injectInputEvent(InputEvent event, int mode)
            // mode 0 = INJECT_INPUT_EVENT_MODE_ASYNC
            injectMethod = imClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            injectMethod?.isAccessible = true
        } catch (e: Throwable) {
            System.err.println("RootInputServer: failed to initialize InputManager: ${e.message}")
            e.printStackTrace()
            System.exit(1)
        }

        println("READY")
        System.out.flush()

        val reader = BufferedReader(InputStreamReader(System.`in`))
        while (true) {
            val line = reader.readLine() ?: break
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            try {
                processCommand(trimmed)
            } catch (t: Throwable) {
                System.err.println("RootInputServer error on '$trimmed': ${t.message}")
            }
        }
    }

    private fun processCommand(cmd: String) {
        val parts = cmd.split(" ")
        when (parts[0]) {
            "D" -> { // DOWN: D x y
                val x = parts[1].toFloat()
                val y = parts[2].toFloat()
                downTime = SystemClock.uptimeMillis()
                injectMotion(MotionEvent.ACTION_DOWN, x, y, downTime, downTime)
            }
            "M" -> { // MOVE: M x y
                val x = parts[1].toFloat()
                val y = parts[2].toFloat()
                val now = SystemClock.uptimeMillis()
                injectMotion(MotionEvent.ACTION_MOVE, x, y, downTime, now)
            }
            "U" -> { // UP: U x y
                val x = parts[1].toFloat()
                val y = parts[2].toFloat()
                val now = SystemClock.uptimeMillis()
                injectMotion(MotionEvent.ACTION_UP, x, y, downTime, now)
            }
            "K" -> { // KEY: K keyCode action (0=down, 1=up)
                val keyCode = parts[1].toInt()
                val action = if (parts.size > 2) parts[2].toInt() else 0
                injectKey(keyCode, action)
            }
            "KC" -> { // KEY CLICK: KC keyCode
                val keyCode = parts[1].toInt()
                injectKey(keyCode, KeyEvent.ACTION_DOWN)
                injectKey(keyCode, KeyEvent.ACTION_UP)
            }
            "W" -> { // WAKE & DISMISS
                injectKey(KeyEvent.KEYCODE_WAKEUP, KeyEvent.ACTION_DOWN)
                injectKey(KeyEvent.KEYCODE_WAKEUP, KeyEvent.ACTION_UP)
                try {
                    Runtime.getRuntime().exec("wm dismiss-keyguard").waitFor()
                } catch (ignored: Throwable) {}
            }
            "P" -> { // PING
                println("PONG")
                System.out.flush()
            }
            "Q" -> { // QUIT
                System.exit(0)
            }
        }
    }

    private fun injectMotion(action: Int, x: Float, y: Float, down: Long, eventTime: Long) {
        val event = MotionEvent.obtain(down, eventTime, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            injectMethod?.invoke(inputManager, event, 0)
        } finally {
            event.recycle()
        }
    }

    private fun injectKey(keyCode: Int, action: Int) {
        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(now, now, action, keyCode, 0)
        event.source = InputDevice.SOURCE_KEYBOARD
        injectMethod?.invoke(inputManager, event, 0)
    }
}
