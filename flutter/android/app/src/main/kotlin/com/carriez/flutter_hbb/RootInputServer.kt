package com.carriez.flutter_hbb

import android.net.LocalServerSocket
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
 * Runs under UID 0 via `app_process` to inject MotionEvent and KeyEvent
 * directly via internal `InputManager.injectInputEvent` with ~0ms latency.
 *
 * Supports:
 * 1. Ultra-fast Unix Domain LocalSocket ("rustdesk_root_input") + stdin fallback
 * 2. Explicit setDisplayId(0) for Android 10 - 15 InputDispatcher display routing
 * 3. Scrcpy-grade PointerProperties & PointerCoords touch synthesis
 * 4. Resilient multi-tier InputManager reflection (Android 7 - 15)
 */
object RootInputServer {
    const val SOCKET_NAME = "rustdesk_root_input"

    private var injectMethod: Method? = null
    private var setDisplayIdMethod: Method? = null
    private var inputManager: Any? = null
    private var downTime = 0L

    private val pointerProperties = arrayOf(MotionEvent.PointerProperties().apply {
        id = 0
        toolType = MotionEvent.TOOL_TYPE_FINGER
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

        initInputManager()

        // Start LocalServerSocket thread for ultra-low latency direct socket IPC
        Thread {
            try {
                val serverSocket = LocalServerSocket(SOCKET_NAME)
                System.err.println("RootInputServer: LocalServerSocket listening on '$SOCKET_NAME'")
                while (true) {
                    try {
                        val client = serverSocket.accept()
                        Thread {
                            try {
                                val reader = BufferedReader(InputStreamReader(client.inputStream))
                                var line: String?
                                while (reader.readLine().also { line = it } != null) {
                                    val trimmed = line!!.trim()
                                    if (trimmed.isNotEmpty()) {
                                        processCommand(trimmed)
                                    }
                                }
                            } catch (ignored: Throwable) {} finally {
                                try { client.close() } catch (ignored: Throwable) {}
                            }
                        }.apply { isDaemon = true; start() }
                    } catch (ignored: Throwable) {}
                }
            } catch (t: Throwable) {
                System.err.println("RootInputServer: LocalServerSocket error: ${t.message}")
            }
        }.apply { isDaemon = true; start() }

        // Always notify parent process ready via stdout
        println("READY")
        System.out.flush()

        // Also process stdin as fallback channel
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

    private fun initInputManager() {
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

        // Strategy 2: InputManager.getInstance() (Android 7-13)
        if (inputManager == null) {
            try {
                val imClass = Class.forName("android.hardware.input.InputManager")
                val getInstance = imClass.getDeclaredMethod("getInstance")
                getInstance.isAccessible = true
                inputManager = getInstance.invoke(null)
            } catch (t: Throwable) {
                System.err.println("Strategy 2 error: ${t.message}")
            }
        }

        // Strategy 3: InputManagerGlobal.getInstance() (Android 14+)
        if (inputManager == null) {
            try {
                val imgClass = Class.forName("android.hardware.input.InputManagerGlobal")
                val getInstance = imgClass.getDeclaredMethod("getInstance")
                getInstance.isAccessible = true
                inputManager = getInstance.invoke(null)
            } catch (t: Throwable) {
                System.err.println("Strategy 3 error: ${t.message}")
            }
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
            } catch (t: Throwable) {
                System.err.println("Strategy 4 error: ${t.message}")
            }
        }

        // Find injectInputEvent method on inputManager
        if (inputManager != null) {
            val imClass = inputManager!!.javaClass
            for (m in imClass.methods) {
                if (m.name == "injectInputEvent" && m.parameterTypes.isNotEmpty() &&
                    InputEvent::class.java.isAssignableFrom(m.parameterTypes[0])) {
                    m.isAccessible = true
                    injectMethod = m
                    break
                }
            }
            if (injectMethod != null) {
                System.err.println("RootInputServer: initialized injectMethod successfully (${imClass.name})")
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

    @Synchronized
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
            "S" -> { // SWIPE: S x1 y1 x2 y2 duration
                val x1 = parts[1].toFloat()
                val y1 = parts[2].toFloat()
                val x2 = parts[3].toFloat()
                val y2 = parts[4].toFloat()
                val duration = if (parts.size > 5) parts[5].toLong() else 250L
                val startTime = SystemClock.uptimeMillis()
                injectMotion(MotionEvent.ACTION_DOWN, x1, y1, startTime, startTime)
                val steps = (duration / 16).toInt().coerceAtLeast(6)
                for (i in 1..steps) {
                    val progress = i.toFloat() / steps
                    val cx = x1 + (x2 - x1) * progress
                    val cy = y1 + (y2 - y1) * progress
                    val now = startTime + (duration * progress).toLong()
                    injectMotion(MotionEvent.ACTION_MOVE, cx, cy, startTime, now)
                    try { Thread.sleep(duration / steps) } catch (ignored: Throwable) {}
                }
                val endTime = startTime + duration
                injectMotion(MotionEvent.ACTION_UP, x2, y2, startTime, endTime)
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
                    Runtime.getRuntime().exec("wm dismiss-keyguard")
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
        pointerCoords[0].x = x
        pointerCoords[0].y = y
        pointerCoords[0].pressure = if (action == MotionEvent.ACTION_UP) 0.0f else 1.0f
        pointerCoords[0].size = 1.0f

        val event = MotionEvent.obtain(
            down,
            eventTime,
            action,
            1,
            pointerProperties,
            pointerCoords,
            0,
            0,
            1.0f,
            1.0f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )

        try {
            if (setDisplayIdMethod != null && setDisplayIdMethod!!.declaringClass.isAssignableFrom(event.javaClass)) {
                setDisplayIdMethod!!.invoke(event, 0)
            }
        } catch (ignored: Throwable) {}

        try {
            if (injectMethod != null && inputManager != null) {
                val paramCount = injectMethod!!.parameterTypes.size
                when (paramCount) {
                    1 -> injectMethod?.invoke(inputManager, event)
                    2 -> injectMethod?.invoke(inputManager, event, 0)
                    3 -> injectMethod?.invoke(inputManager, event, 0, 0)
                    else -> injectMethod?.invoke(inputManager, event, 0)
                }
            }
        } catch (t: Throwable) {
            System.err.println("injectMotion failed: ${t.message}")
        } finally {
            event.recycle()
        }
    }

    private fun injectKey(keyCode: Int, action: Int) {
        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(now, now, action, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)

        try {
            if (setDisplayIdMethod != null && setDisplayIdMethod!!.declaringClass.isAssignableFrom(event.javaClass)) {
                setDisplayIdMethod!!.invoke(event, 0)
            }
        } catch (ignored: Throwable) {}

        try {
            if (injectMethod != null && inputManager != null) {
                val paramCount = injectMethod!!.parameterTypes.size
                when (paramCount) {
                    1 -> injectMethod?.invoke(inputManager, event)
                    2 -> injectMethod?.invoke(inputManager, event, 0)
                    3 -> injectMethod?.invoke(inputManager, event, 0, 0)
                    else -> injectMethod?.invoke(inputManager, event, 0)
                }
            }
        } catch (t: Throwable) {
            System.err.println("injectKey failed: ${t.message}")
        }
    }
}
