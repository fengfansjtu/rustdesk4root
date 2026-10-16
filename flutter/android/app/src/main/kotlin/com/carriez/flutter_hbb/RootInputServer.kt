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

    // Crucial: A single gesture sequence (DOWN -> MOVE... -> UP) MUST share the exact same downTime!
    private var currentDownTime: Long = 0L
    private var isTouching: Boolean = false

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

            // Notify parent process via stdout that RootInputServer is ready
            println("RUSTDESK_ROOT_SERVER_READY")
            System.out.flush()

            val reader = BufferedReader(InputStreamReader(System.`in`))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val cmd = line!!.trim()
                if (cmd.isEmpty()) continue
                if (cmd == "EXIT" || cmd == "Q") break
                handleCommand(cmd)
            }
        } catch (e: Throwable) {
            System.err.println("RootInputServer fatal error: ${e.message}")
            e.printStackTrace(System.err)
        }
    }

    private fun initReflection() {
        // Strategy 1: ActivityThread systemContext -> getSystemService(INPUT_SERVICE)
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            var at = try {
                val sm = atClass.getDeclaredMethod("systemMain")
                sm.isAccessible = true
                sm.invoke(null)
            } catch (t: Throwable) { null }

            if (at == null) {
                try {
                    val atCtor = atClass.getDeclaredConstructor()
                    atCtor.isAccessible = true
                    at = atCtor.newInstance()
                    val sCurrentField = atClass.getDeclaredField("sCurrentActivityThread")
                    sCurrentField.isAccessible = true
                    sCurrentField.set(null, at)
                    val mSystemThread = atClass.getDeclaredField("mSystemThread")
                    mSystemThread.isAccessible = true
                    mSystemThread.setBoolean(at, true)
                } catch (ignored: Throwable) {}
            }

            if (at != null) {
                val getSysCtx = atClass.getDeclaredMethod("getSystemContext")
                getSysCtx.isAccessible = true
                val sysCtx = getSysCtx.invoke(at) as? android.content.Context
                if (sysCtx != null) {
                    inputManager = sysCtx.getSystemService(android.content.Context.INPUT_SERVICE)
                }
            }
        } catch (t: Throwable) {
            System.err.println("Strategy 1 error: ${t.message}")
        }

        // Strategy 2: InputManagerGlobal (Android 14+)
        if (inputManager == null) {
            try {
                val imgClass = Class.forName("android.hardware.input.InputManagerGlobal")
                val getInstance = imgClass.getDeclaredMethod("getInstance")
                getInstance.isAccessible = true
                inputManager = getInstance.invoke(null)
            } catch (ignored: Throwable) {}
        }

        // Strategy 3: InputManager.getInstance() (Android 7~13)
        if (inputManager == null) {
            try {
                val imClass = InputManager::class.java
                val getInstance = imClass.getDeclaredMethod("getInstance")
                getInstance.isAccessible = true
                inputManager = getInstance.invoke(null)
            } catch (ignored: Throwable) {}
        }

        // Strategy 4: ServiceManager.getService("input") -> IInputManager.Stub.asInterface
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
            val imClass = inputManager!!.javaClass
            for (m in imClass.methods) {
                if (m.name == "injectInputEvent" && m.parameterTypes.isNotEmpty() &&
                    InputEvent::class.java.isAssignableFrom(m.parameterTypes[0])) {
                    m.isAccessible = true
                    injectInputEventMethod = m
                    break
                }
            }
            if (injectInputEventMethod != null) {
                System.err.println("RootInputServer: initialized injectInputEvent successfully (${imClass.name})")
            } else {
                System.err.println("RootInputServer: found inputManager (${imClass.name}) but no injectInputEvent method")
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
    }

    private fun handleCommand(cmd: String) {
        try {
            val parts = cmd.split(" ")
            when (parts[0]) {
                "D" -> { // Down: D x y
                    val x = parts[1].toFloat()
                    val y = parts[2].toFloat()
                    currentDownTime = SystemClock.uptimeMillis()
                    isTouching = true
                    injectMotion(MotionEvent.ACTION_DOWN, currentDownTime, currentDownTime, x, y)
                }
                "M" -> { // Move: M x y
                    val x = parts[1].toFloat()
                    val y = parts[2].toFloat()
                    val now = SystemClock.uptimeMillis()
                    // Defensive guard: if DOWN was missed, synthesize DOWN to establish a valid gesture sequence
                    if (!isTouching) {
                        currentDownTime = now
                        isTouching = true
                        injectMotion(MotionEvent.ACTION_DOWN, currentDownTime, now, x, y)
                    } else {
                        injectMotion(MotionEvent.ACTION_MOVE, currentDownTime, now, x, y)
                    }
                }
                "U" -> { // Up: U x y
                    val x = parts[1].toFloat()
                    val y = parts[2].toFloat()
                    val now = SystemClock.uptimeMillis()
                    if (isTouching) {
                        injectMotion(MotionEvent.ACTION_UP, currentDownTime, now, x, y)
                        isTouching = false
                    }
                }
                "S" -> { // Swipe: S x1 y1 x2 y2 durationMs
                    val x1 = parts[1].toFloat()
                    val y1 = parts[2].toFloat()
                    val x2 = parts[3].toFloat()
                    val y2 = parts[4].toFloat()
                    val duration = parts.getOrNull(5)?.toLongOrNull() ?: 150L
                    injectSwipe(x1, y1, x2, y2, duration)
                }
                "K" -> { // Key Click: K keyCode
                    val keyCode = parts[1].toInt()
                    injectKeyClick(keyCode)
                }
                "KD" -> { // Key Down: KD keyCode
                    val keyCode = parts[1].toInt()
                    injectKeyEvent(keyCode, KeyEvent.ACTION_DOWN)
                }
                "KU" -> { // Key Up: KU keyCode
                    val keyCode = parts[1].toInt()
                    injectKeyEvent(keyCode, KeyEvent.ACTION_UP)
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

    private fun injectMotion(action: Int, downTime: Long, eventTime: Long, x: Float, y: Float) {
        pointerCoords[0].x = x
        pointerCoords[0].y = y
        pointerCoords[0].pressure = if (action == MotionEvent.ACTION_UP) 0f else 1.0f
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
            0, // deviceId (0 = Virtual / System)
            0, // edgeFlags
            InputDevice.SOURCE_TOUCHSCREEN, // CRITICAL: Always declare as TOUCHSCREEN, NEVER MOUSE!
            0 // flags
        )

        try {
            if (setDisplayIdMethod != null && setDisplayIdMethod!!.declaringClass.isAssignableFrom(event.javaClass)) {
                setDisplayIdMethod!!.invoke(event, 0)
            }
        } catch (ignored: Throwable) {}

        try {
            if (injectInputEventMethod != null && inputManager != null) {
                val paramCount = injectInputEventMethod!!.parameterTypes.size
                when (paramCount) {
                    1 -> injectInputEventMethod?.invoke(inputManager, event)
                    2 -> injectInputEventMethod?.invoke(inputManager, event, INJECT_INPUT_EVENT_MODE_ASYNC)
                    3 -> injectInputEventMethod?.invoke(inputManager, event, INJECT_INPUT_EVENT_MODE_ASYNC, 0)
                    else -> injectInputEventMethod?.invoke(inputManager, event, INJECT_INPUT_EVENT_MODE_ASYNC)
                }
            }
        } catch (t: Throwable) {
            System.err.println("injectMotion failed: ${t.message}")
        } finally {
            event.recycle()
        }
    }

    private fun injectSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
        val startTime = SystemClock.uptimeMillis()
        injectMotion(MotionEvent.ACTION_DOWN, startTime, startTime, x1, y1)
        val steps = (duration / 16).toInt().coerceAtLeast(4)
        for (i in 1..steps) {
            try { Thread.sleep(duration / steps) } catch (ignored: Throwable) {}
            val progress = i.toFloat() / steps
            val cx = x1 + (x2 - x1) * progress
            val cy = y1 + (y2 - y1) * progress
            val now = startTime + (duration * progress).toLong()
            injectMotion(MotionEvent.ACTION_MOVE, startTime, now, cx, cy)
        }
        val endTime = startTime + duration
        injectMotion(MotionEvent.ACTION_UP, startTime, endTime, x2, y2)
    }

    private fun injectKeyClick(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)

        try {
            if (setDisplayIdMethod != null && setDisplayIdMethod!!.declaringClass.isAssignableFrom(down.javaClass)) {
                setDisplayIdMethod!!.invoke(down, 0)
                setDisplayIdMethod!!.invoke(up, 0)
            }
        } catch (ignored: Throwable) {}

        try {
            if (injectInputEventMethod != null && inputManager != null) {
                val paramCount = injectInputEventMethod!!.parameterTypes.size
                when (paramCount) {
                    1 -> {
                        injectInputEventMethod?.invoke(inputManager, down)
                        injectInputEventMethod?.invoke(inputManager, up)
                    }
                    2 -> {
                        injectInputEventMethod?.invoke(inputManager, down, INJECT_INPUT_EVENT_MODE_ASYNC)
                        injectInputEventMethod?.invoke(inputManager, up, INJECT_INPUT_EVENT_MODE_ASYNC)
                    }
                    3 -> {
                        injectInputEventMethod?.invoke(inputManager, down, INJECT_INPUT_EVENT_MODE_ASYNC, 0)
                        injectInputEventMethod?.invoke(inputManager, up, INJECT_INPUT_EVENT_MODE_ASYNC, 0)
                    }
                    else -> {
                        injectInputEventMethod?.invoke(inputManager, down, INJECT_INPUT_EVENT_MODE_ASYNC)
                        injectInputEventMethod?.invoke(inputManager, up, INJECT_INPUT_EVENT_MODE_ASYNC)
                    }
                }
            }
        } catch (t: Throwable) {
            System.err.println("injectKeyClick failed: ${t.message}")
        }
    }

    private fun injectKeyEvent(keyCode: Int, action: Int) {
        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(now, now, action, keyCode, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)

        try {
            if (setDisplayIdMethod != null && setDisplayIdMethod!!.declaringClass.isAssignableFrom(event.javaClass)) {
                setDisplayIdMethod!!.invoke(event, 0)
            }
        } catch (ignored: Throwable) {}

        try {
            if (injectInputEventMethod != null && inputManager != null) {
                val paramCount = injectInputEventMethod!!.parameterTypes.size
                when (paramCount) {
                    1 -> injectInputEventMethod?.invoke(inputManager, event)
                    2 -> injectInputEventMethod?.invoke(inputManager, event, INJECT_INPUT_EVENT_MODE_ASYNC)
                    3 -> injectInputEventMethod?.invoke(inputManager, event, INJECT_INPUT_EVENT_MODE_ASYNC, 0)
                    else -> injectInputEventMethod?.invoke(inputManager, event, INJECT_INPUT_EVENT_MODE_ASYNC)
                }
            }
        } catch (t: Throwable) {
            System.err.println("injectKeyEvent failed: ${t.message}")
        }
    }
}
