package com.carriez.flutter_hbb

import android.hardware.input.InputManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method

/**
 * High-performance Scrcpy-style Root Input Server.
 * Runs under UID 0 via `app_process` with an anonymous stdin/stdout pipe inherited from `su`.
 * Injects MotionEvent (forced as SOURCE_TOUCHSCREEN + TOOL_TYPE_FINGER) and KeyEvent
 * directly via internal `InputManager.injectInputEvent` with ~0ms latency.
 */
object RootInputServer {
    private var inputManager: Any? = null
    private var injectInputEventMethod: Method? = null
    private var setDisplayIdMethod: Method? = null
    private var touchDeviceId: Int = 0

    // Crucial: A single gesture sequence (DOWN -> MOVE... -> UP) MUST share the exact same downTime!
    private var currentDownTime: Long = 0L
    private var isTouching: Boolean = false
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var lastInjectOk: Boolean = true

    private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0

    private val pointerProps = arrayOf(MotionEvent.PointerProperties().apply {
        id = 0
        toolType = MotionEvent.TOOL_TYPE_FINGER // NEVER TOOL_TYPE_MOUSE!
    })

    private val pointerCoords = arrayOf(MotionEvent.PointerCoords().apply {
        pressure = 1.0f
        size = 1.0f
    })

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            Looper.prepareMainLooper()
        } catch (ignored: Throwable) {}

        try {
            initReflection()

            if (inputManager == null || injectInputEventMethod == null) {
                System.err.println("RootInputServer: could not find inputManager or injectInputEventMethod, will use shell input fallback in daemon.")
            }

            // Notify parent process via stdout that RootInputServer is ready
            println("RUSTDESK_ROOT_SERVER_READY")
            System.out.flush()

            val reader = BufferedReader(InputStreamReader(System.`in`))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                var cmd = line!!.trim()
                while (cmd.startsWith("M ") && reader.ready()) {
                    reader.mark(128)
                    val next = reader.readLine() ?: break
                    if (!next.startsWith("M ")) {
                        reader.reset()
                        break
                    }
                    cmd = next
                }
                if (cmd.isEmpty()) continue
                if (cmd == "EXIT" || cmd == "Q") break
                handleCommand(cmd, reader)
            }
        } catch (e: Throwable) {
            System.err.println("RootInputServer fatal error: ${e.message}")
            e.printStackTrace(System.err)
            System.exit(1)
        } finally {
            cancelTouch()
        }
    }

    private fun initReflection() {
        // Initialize ActivityThread system context workaround (Scrcpy Workarounds style) for Android 12-15
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAtMethod = atClass.getDeclaredMethod("currentActivityThread")
            currentAtMethod.isAccessible = true
            if (currentAtMethod.invoke(null) == null) {
                val atCtor = atClass.getDeclaredConstructor()
                atCtor.isAccessible = true
                val at = atCtor.newInstance()
                val sCurrentField = atClass.getDeclaredField("sCurrentActivityThread")
                sCurrentField.isAccessible = true
                sCurrentField.set(null, at)
                try {
                    val mSystemThread = atClass.getDeclaredField("mSystemThread")
                    mSystemThread.isAccessible = true
                    mSystemThread.setBoolean(at, true)
                } catch (ignored: Throwable) {}
            }
        } catch (ignored: Throwable) {}

        // Strategy 1: InputManagerGlobal.getInstance() (Android 14+)
        if (inputManager == null) {
            try {
                val imgClass = Class.forName("android.hardware.input.InputManagerGlobal")
                val getInstance = imgClass.getDeclaredMethod("getInstance")
                getInstance.isAccessible = true
                inputManager = getInstance.invoke(null)
            } catch (ignored: Throwable) {}
        }

        // Strategy 2: InputManager.getInstance() (Android 7~13)
        if (inputManager == null) {
            try {
                val imClass = InputManager::class.java
                val getInstance = imClass.getDeclaredMethod("getInstance")
                getInstance.isAccessible = true
                inputManager = getInstance.invoke(null)
            } catch (ignored: Throwable) {}
        }

        // Strategy 3: ServiceManager.getService("input") -> IInputManager.Stub.asInterface
        if (inputManager == null) {
            try {
                val smClass = Class.forName("android.os.ServiceManager")
                val getService = smClass.getMethod("getService", String::class.java)
                val binder = getService.invoke(null, "input") as android.os.IBinder
                val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
                val asInterface = stubClass.getMethod("asInterface", android.os.IBinder::class.java)
                inputManager = asInterface.invoke(null, binder)
            } catch (ignored: Throwable) {}
        }

        // Locate injectInputEvent method
        if (inputManager != null) {
            var curr: Class<*>? = inputManager!!.javaClass
            while (curr != null && curr != Any::class.java) {
                for (m in curr.declaredMethods.sortedBy { it.parameterTypes.size }) {
                    if (m.name == "injectInputEvent" && m.parameterTypes.isNotEmpty() &&
                        m.parameterTypes[0] == InputEvent::class.java &&
                        m.parameterTypes.size in 2..3 &&
                        m.parameterTypes.drop(1).all { it == Int::class.javaPrimitiveType }) {
                        m.isAccessible = true
                        injectInputEventMethod = m
                        break
                    }
                }
                if (injectInputEventMethod != null) break
                curr = curr.superclass
            }
            if (injectInputEventMethod != null) {
                System.err.println("RootInputServer: initialized injectInputEvent successfully (${inputManager!!.javaClass.name})")
            } else {
                System.err.println("RootInputServer: found inputManager (${inputManager!!.javaClass.name}) but no injectInputEvent method")
            }
        } else {
            System.err.println("RootInputServer: could not initialize inputManager from any strategy")
        }

        // Locate setDisplayId method on InputEvent or MotionEvent
        try {
            setDisplayIdMethod = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).apply {
                isAccessible = true
            }
            System.err.println("RootInputServer: initialized setDisplayId on InputEvent")
        } catch (t: Throwable) {
            try {
                setDisplayIdMethod = MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).apply {
                    isAccessible = true
                }
                System.err.println("RootInputServer: initialized setDisplayId on MotionEvent")
            } catch (e: Throwable) {
                System.err.println("RootInputServer: setDisplayId method not found: ${e.message}")
            }
        }

        touchDeviceId = getTouchDeviceId()
        System.err.println("RootInputServer: resolved touchDeviceId=$touchDeviceId")
    }

    /**
     * Matches AOSP InputShellCommand.getInputDeviceId(InputDevice.SOURCE_TOUCHSCREEN)
     */
    private fun getTouchDeviceId(): Int {
        try {
            val devIds = InputDevice.getDeviceIds()
            for (devId in devIds) {
                val inputDev = InputDevice.getDevice(devId)
                if (inputDev != null && inputDev.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)) {
                    return devId
                }
            }
        } catch (ignored: Throwable) {}
        return 0
    }

    private fun handleCommand(cmd: String, reader: BufferedReader) {
        try {
            val parts = cmd.split(" ")
            when (parts[0]) {
                "D" -> { // Down: D x y
                    cancelTouch()
                    val x = parts[1].toFloat()
                    val y = parts[2].toFloat()
                    downX = x
                    downY = y
                    currentDownTime = SystemClock.uptimeMillis()
                    isTouching = true
                    lastInjectOk = injectMotion(MotionEvent.ACTION_DOWN, currentDownTime, currentDownTime, x, y)
                }
                "M" -> { // Move: M x y
                    val x = parts[1].toFloat()
                    val y = parts[2].toFloat()
                    val now = SystemClock.uptimeMillis()
                    if (isTouching && lastInjectOk) {
                        injectMotion(MotionEvent.ACTION_MOVE, currentDownTime, now, x, y)
                    }
                }
                "U" -> { // Up: U x y
                    val x = parts[1].toFloat()
                    val y = parts[2].toFloat()
                    if (isTouching) {
                        isTouching = false
                        val now = SystemClock.uptimeMillis()
                        val ok = if (lastInjectOk) {
                            injectMotion(MotionEvent.ACTION_UP, currentDownTime, now, x, y)
                        } else {
                            false
                        }
                        if (!ok) {
                            if (lastInjectOk) {
                                injectMotion(MotionEvent.ACTION_CANCEL, currentDownTime, now, x, y)
                            }
                            val duration = (now - currentDownTime).coerceIn(50L, 600L)
                            val dist = kotlin.math.hypot((x - downX).toDouble(), (y - downY).toDouble())
                            if (dist < 25.0 && duration < 400L) {
                                execInputFallback("input", "tap", downX.toInt().toString(), downY.toInt().toString())
                            } else {
                                execInputFallback("input", "swipe", downX.toInt().toString(), downY.toInt().toString(), x.toInt().toString(), y.toInt().toString(), duration.toString())
                            }
                        }
                    }
                }
                "S" -> { // Swipe: S x1 y1 x2 y2 durationMs
                    if (isTouching) return
                    val x1 = parts[1].toFloat()
                    val y1 = parts[2].toFloat()
                    val x2 = parts[3].toFloat()
                    val y2 = parts[4].toFloat()
                    val duration = parts.getOrNull(5)?.toLongOrNull() ?: 150L
                    injectSwipe(x1, y1, x2, y2, duration, reader)
                }
                "C" -> cancelTouch()
                "K" -> { // Key Click: K keyCode
                    val keyCode = parts[1].toInt()
                    injectKeyClick(keyCode, parts.getOrNull(2)?.toIntOrNull() ?: 0)
                }
                "KD" -> { // Key Down: KD keyCode
                    val keyCode = parts[1].toInt()
                    if (!injectKeyEvent(keyCode, KeyEvent.ACTION_DOWN, parts.getOrNull(2)?.toIntOrNull() ?: 0)) {
                        execInputFallback("input", "keyevent", keyCode.toString())
                    }
                }
                "KU" -> { // Key Up: KeyEvent.ACTION_UP
                    val keyCode = parts[1].toInt()
                    injectKeyEvent(keyCode, KeyEvent.ACTION_UP, parts.getOrNull(2)?.toIntOrNull() ?: 0)
                }
                "W" -> { // Wake & Unlock
                    injectKeyClick(KeyEvent.KEYCODE_WAKEUP) // 224
                    injectKeyClick(82) // KEYCODE_MENU (brings up PIN/password entry)
                    try {
                        Runtime.getRuntime().exec(arrayOf("wm", "dismiss-keyguard"))
                    } catch (ignored: Throwable) {}
                }
                "P" -> { // Ping
                    println("PONG")
                    System.out.flush()
                }
            }
        } catch (e: Throwable) {
            System.err.println("RootInputServer command error [$cmd]: ${e.message}")
        }
    }

    private fun execInputFallback(vararg args: String) {
        try {
            val process = ProcessBuilder(*args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                System.err.println("Root input fallback exited with $exitCode: $output")
            }
        } catch (t: Throwable) {
            System.err.println("execInputFallback failed: ${t.message}")
        }
    }

    private fun injectMotion(action: Int, downTime: Long, eventTime: Long, x: Float, y: Float): Boolean {
        if (injectInputEventMethod == null || inputManager == null) return false

        pointerCoords[0].x = x
        pointerCoords[0].y = y
        pointerCoords[0].pressure = if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) 0f else 1.0f
        pointerCoords[0].size = 1.0f

        val event = MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1, // pointerCount
            pointerProps,
            pointerCoords,
            0, // metaState
            0, // buttonState
            1.0f, // xPrecision
            1.0f, // yPrecision
            touchDeviceId, // resolved touchscreen deviceId
            0, // edgeFlags
            InputDevice.SOURCE_TOUCHSCREEN, // CRITICAL: Always declare as TOUCHSCREEN, NEVER MOUSE!
            0 // flags
        )

        try {
            if (setDisplayIdMethod != null && setDisplayIdMethod!!.declaringClass.isAssignableFrom(event.javaClass)) {
                setDisplayIdMethod!!.invoke(event, 0)
            }
        } catch (ignored: Throwable) {}

        return try {
            val paramCount = injectInputEventMethod!!.parameterTypes.size
            val res = when (paramCount) {
                1 -> injectInputEventMethod?.invoke(inputManager, event)
                2 -> injectInputEventMethod?.invoke(inputManager, event, INJECT_INPUT_EVENT_MODE_ASYNC)
                3 -> injectInputEventMethod?.invoke(inputManager, event, INJECT_INPUT_EVENT_MODE_ASYNC, -1)
                else -> injectInputEventMethod?.invoke(inputManager, event, INJECT_INPUT_EVENT_MODE_ASYNC)
            }
            (res as? Boolean) ?: true
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            System.err.println("injectMotion failed: ${cause.javaClass.simpleName}: ${cause.message}")
            false
        } finally {
            event.recycle()
        }
    }

    private fun cancelTouch() {
        if (!isTouching) return
        isTouching = false
        if (lastInjectOk) {
            injectMotion(MotionEvent.ACTION_CANCEL, currentDownTime, SystemClock.uptimeMillis(),
                pointerCoords[0].x, pointerCoords[0].y)
        }
    }

    private fun injectSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long, reader: BufferedReader) {
        val startTime = SystemClock.uptimeMillis()
        if (!injectMotion(MotionEvent.ACTION_DOWN, startTime, startTime, x1, y1)) {
            execInputFallback("input", "swipe", x1.toInt().toString(), y1.toInt().toString(), x2.toInt().toString(), y2.toInt().toString(), duration.toString())
            return
        }
        val steps = (duration / 16).toInt().coerceAtLeast(4)
        for (i in 1..steps) {
            if (!reader.ready()) {
                Thread.sleep(duration / steps)
            }
            val progress = i.toFloat() / steps
            val cx = x1 + (x2 - x1) * progress
            val cy = y1 + (y2 - y1) * progress
            val now = SystemClock.uptimeMillis()
            injectMotion(MotionEvent.ACTION_MOVE, startTime, now, cx, cy)
        }
        val endTime = SystemClock.uptimeMillis()
        injectMotion(MotionEvent.ACTION_UP, startTime, endTime, x2, y2)
    }

    private fun injectKeyClick(keyCode: Int, metaState: Int = 0) {
        val downOk = injectKeyEvent(keyCode, KeyEvent.ACTION_DOWN, metaState)
        val upOk = injectKeyEvent(keyCode, KeyEvent.ACTION_UP, metaState)
        if (!downOk || !upOk) {
            execInputFallback("input", "keyevent", keyCode.toString())
        }
    }

    private fun injectKeyEvent(keyCode: Int, action: Int, metaState: Int = 0): Boolean {
        if (injectInputEventMethod == null || inputManager == null) return false

        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(now, now, action, keyCode, 0, metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)

        try {
            if (setDisplayIdMethod != null && setDisplayIdMethod!!.declaringClass.isAssignableFrom(event.javaClass)) {
                setDisplayIdMethod!!.invoke(event, 0)
            }
        } catch (ignored: Throwable) {}

        return try {
            val paramCount = injectInputEventMethod!!.parameterTypes.size
            val res = when (paramCount) {
                1 -> injectInputEventMethod?.invoke(inputManager, event)
                2 -> injectInputEventMethod?.invoke(inputManager, event, INJECT_INPUT_EVENT_MODE_ASYNC)
                3 -> injectInputEventMethod?.invoke(inputManager, event, INJECT_INPUT_EVENT_MODE_ASYNC, -1)
                else -> injectInputEventMethod?.invoke(inputManager, event, INJECT_INPUT_EVENT_MODE_ASYNC)
            }
            (res as? Boolean) ?: true
        } catch (t: Throwable) {
            System.err.println("injectKeyEvent failed: ${t.message}")
            false
        }
    }
}
