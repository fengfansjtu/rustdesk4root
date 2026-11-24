package com.carriez.flutter_hbb

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.media.MediaCodec
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * High-performance Scrcpy-style Root Input & Privileged Video Server.
 * Runs under UID 0 via `app_process` with an anonymous stdin/stdout pipe inherited from `su`
 * plus an authenticated loopback TCP server for privileged VirtualDisplay video streaming.
 * Injects MotionEvent (forced as SOURCE_TOUCHSCREEN + TOOL_TYPE_FINGER) and KeyEvent
 * directly via internal `InputManager.injectInputEvent` with ~0ms latency.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi")
object RootInputServer {
    private var inputManager: Any? = null
    private var injectInputEventMethod: Method? = null
    private var setDisplayIdMethod: Method? = null
    private var touchDeviceId: Int = 0
    private var systemContextCache: Context? = null

    // Crucial: A single gesture sequence (DOWN -> MOVE... -> UP) MUST share the exact same downTime!
    private var currentDownTime: Long = 0L
    private var isTouching: Boolean = false
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var lastInjectOk: Boolean = true

    @Volatile private var displayTurnedOff: Boolean = false
    @Volatile private var savedBrightness: Int? = null
    private val reportedSecurityBlock = AtomicBoolean(false)

    private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
    private const val POWER_MODE_OFF = 0
    private const val POWER_MODE_NORMAL = 2
    private const val VIRTUAL_DISPLAY_FLAG_PUBLIC = 1
    private val PACKAGE_REGEX = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

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
        var token: String? = null
        var pkg: String? = null
        for (arg in args) {
            when {
                arg.startsWith("--token=") -> token = arg.removePrefix("--token=").trim()
                    .takeIf { it.length in 32..64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
                arg.startsWith("--pkg=") -> pkg = arg.removePrefix("--pkg=").trim()
                    .takeIf { PACKAGE_REGEX.matches(it) }
            }
        }

        try {
            Looper.prepareMainLooper()
        } catch (ignored: Throwable) {}

        var videoServer: ServerSocket? = null
        try {
            initReflection()

            if (inputManager == null || injectInputEventMethod == null) {
                System.err.println("RootInputServer: could not find inputManager or injectInputEventMethod, will use shell input fallback in daemon.")
            }

            if (pkg != null) {
                grantAppOps(pkg)
            }

            if (token != null) {
                videoServer = startVideoServer(token)
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
            if (displayTurnedOff) {
                setDisplayPower(true)
            }
            runCatching { videoServer?.close() }
        }
    }

    private fun grantAppOps(pkg: String) {
        Thread({
            val cmds = listOf(
                arrayOf("appops", "set", pkg, "PROJECT_MEDIA", "allow"),
                arrayOf("appops", "set", pkg, "READ_CLIPBOARD_IN_BACKGROUND", "allow"),
            )
            for (c in cmds) {
                runCatching { Runtime.getRuntime().exec(c).waitFor() }
            }
        }, "RootAppOpsInit").apply { isDaemon = true; start() }
    }

    private fun fillConfigurationController(atClass: Class<*>, at: Any) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        runCatching {
            val controllerType = Class.forName("android.app.ConfigurationController")
            val internal = Class.forName("android.app.ActivityThreadInternal")
            val controller = controllerType.getDeclaredConstructor(internal)
                .apply { isAccessible = true }
                .newInstance(at)
            runCatching {
                controllerType.getDeclaredField("mConfiguration").apply { isAccessible = true }
                    .set(controller, Configuration().apply { setToDefaults() })
            }
            atClass.getDeclaredField("mConfigurationController").apply { isAccessible = true }.set(at, controller)
        }.onFailure { System.err.println("RootInputServer: ConfigurationController init skipped: ${it.message}") }
    }

    @Synchronized
    private fun getSystemContext(): Context? {
        systemContextCache?.let { return it }
        return runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            var at = atClass.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null)
            if (at == null) {
                val ctor = atClass.getDeclaredConstructor().apply { isAccessible = true }
                at = ctor.newInstance()
                atClass.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, at)
                runCatching { atClass.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(at, true) }
                fillConfigurationController(atClass, at)
            }
            val ctx = atClass.getDeclaredMethod("getSystemContext").apply { isAccessible = true }.invoke(at) as Context
            systemContextCache = ctx
            ctx
        }.onFailure {
            System.err.println("RootInputServer: getSystemContext failed: ${it.message}")
        }.getOrNull()
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
                fillConfigurationController(atClass, at)
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
                "P_OFF" -> setDisplayPower(false)
                "P_ON" -> setDisplayPower(true)
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

    private fun checkSecurityBlocked(t: Throwable) {
        val cause = t.cause ?: t
        if ((t is SecurityException || cause is SecurityException) && reportedSecurityBlock.compareAndSet(false, true)) {
            System.err.println("RUSTDESK_ROOT_INPUT_BLOCKED")
            System.err.flush()
        }
    }

    private fun invokeInjectInputEvent(event: InputEvent): Boolean {
        val paramCount = injectInputEventMethod!!.parameterTypes.size
        val res = when (paramCount) {
            1 -> injectInputEventMethod?.invoke(inputManager, event)
            2 -> injectInputEventMethod?.invoke(inputManager, event, INJECT_INPUT_EVENT_MODE_ASYNC)
            3 -> injectInputEventMethod?.invoke(inputManager, event, INJECT_INPUT_EVENT_MODE_ASYNC, -1)
            else -> injectInputEventMethod?.invoke(inputManager, event, INJECT_INPUT_EVENT_MODE_ASYNC)
        }
        return (res as? Boolean) ?: true
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
            invokeInjectInputEvent(event)
        } catch (t: Throwable) {
            checkSecurityBlocked(t)
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
            invokeInjectInputEvent(event)
        } catch (t: Throwable) {
            checkSecurityBlocked(t)
            val cause = t.cause ?: t
            System.err.println("injectKeyEvent failed: ${cause.javaClass.simpleName}: ${cause.message}")
            false
        }
    }

    @Synchronized
    private fun setDisplayPower(on: Boolean) {
        displayTurnedOff = !on
        val how = when {
            requestDisplayPower(on) -> "requestDisplayPower"
            setPanelPowerMode(on) -> "setDisplayPowerMode"
            else -> null
        }
        System.err.println(
            if (how != null) "RootInputServer: screen ${if (on) "on" else "off"} via $how"
            else "RootInputServer: panel power control unavailable, brightness fallback=${if (on) "restore" else "dim"}"
        )
        if (!on) {
            if (how == null) dimScreen()
        } else {
            val saved = savedBrightness
            if (saved != null) {
                savedBrightness = null
                runCatching {
                    Runtime.getRuntime().exec(arrayOf("settings", "put", "system", "screen_brightness", saved.toString())).waitFor()
                }
            }
        }
    }

    private fun requestDisplayPower(on: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < 35) return false
        return runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "display") as IBinder
            val manager = Class.forName("android.hardware.display.IDisplayManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            val method = manager.javaClass.methods.first {
                it.name == "requestDisplayPower" && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            val power: Any = if (method.parameterTypes[1] == Boolean::class.javaPrimitiveType) on else if (on) 2 else 1
            (method.invoke(manager, Display.DEFAULT_DISPLAY, power) as? Boolean) != false
        }.onFailure {
            System.err.println("RootInputServer: requestDisplayPower failed: ${it.message}")
        }.getOrDefault(false)
    }

    private fun setPanelPowerMode(on: Boolean): Boolean = runCatching {
        val sc = Class.forName("android.view.SurfaceControl")
        val tokens = runCatching {
            listOfNotNull(sc.getMethod("getInternalDisplayToken").invoke(null) as? IBinder)
        }.getOrNull().orEmpty().ifEmpty {
            val dc = loadDisplayControl()
            val ids = dc.getMethod("getPhysicalDisplayIds").invoke(null) as? LongArray ?: LongArray(0)
            val getToken = dc.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
            buildList { for (id in ids) (getToken.invoke(null, id) as? IBinder)?.let(::add) }
        }
        check(tokens.isNotEmpty()) { "no display token" }
        val setMode = sc.getMethod("setDisplayPowerMode", IBinder::class.java, Int::class.javaPrimitiveType)
        tokens.forEach { setMode.invoke(null, it, if (on) POWER_MODE_NORMAL else POWER_MODE_OFF) }
        true
    }.onFailure {
        System.err.println("RootInputServer: setDisplayPowerMode failed: ${it.message}")
    }.getOrDefault(false)

    private fun loadDisplayControl(): Class<*> {
        val factory = Class.forName("com.android.internal.os.ClassLoaderFactory")
        val create = factory.getDeclaredMethod(
            "createClassLoader",
            String::class.java, String::class.java, String::class.java, ClassLoader::class.java,
            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java,
        )
        val loader = create.invoke(
            null, "/system/framework/services.jar", null, null, ClassLoader.getSystemClassLoader(), 0, true, null,
        ) as ClassLoader
        val dc = loader.loadClass("com.android.server.display.DisplayControl")
        Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(Runtime.getRuntime(), dc, "android_servers")
        return dc
    }

    private fun dimScreen() {
        if (savedBrightness == null) {
            savedBrightness = runCatching {
                val proc = Runtime.getRuntime().exec(arrayOf("settings", "get", "system", "screen_brightness"))
                val value = proc.inputStream.bufferedReader().use { it.readText().trim().toIntOrNull() }
                proc.waitFor()
                value
            }.getOrNull()
        }
        runCatching {
            Runtime.getRuntime().exec(arrayOf("settings", "put", "system", "screen_brightness", "0")).waitFor()
        }
    }

    private fun startVideoServer(token: String): ServerSocket {
        val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        val pool = Executors.newCachedThreadPool { r ->
            Thread(r, "RootVideoSession").apply { isDaemon = true }
        }
        Thread({
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                pool.execute { serveVideoClient(client, token) }
            }
        }, "RootVideoAccept").apply { isDaemon = true; start() }
        println("RUSTDESK_ROOT_VIDEO_PORT ${server.localPort}")
        System.out.flush()
        return server
    }

    private fun createMirrorDisplay(width: Int, height: Int, dpi: Int, surface: Surface): VirtualDisplay {
        // 1. Android 11+ static helper: DisplayManager.createVirtualDisplay(name, w, h, displayIdToMirror, surface)
        runCatching {
            val m = DisplayManager::class.java.getMethod(
                "createVirtualDisplay",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Surface::class.java,
            )
            val vd = m.invoke(null, "RustDeskRootScreen", width, height, 0, surface) as? VirtualDisplay
            if (vd != null) return vd
        }

        // 2. DisplayManager instance backed by system context
        val ctx = checkNotNull(getSystemContext()) { "System context unavailable" }
        val ctor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        val dm = ctor.newInstance(ctx)
        val flagSets = intArrayOf(
            VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE,
            VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        )
        var lastError: Throwable? = null
        for (flags in flagSets) {
            try {
                val vd = dm.createVirtualDisplay("RustDeskRootScreen", width, height, dpi, surface, flags)
                if (vd != null) return vd
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw IllegalStateException("Failed to create privileged VirtualDisplay", lastError)
    }

    @Suppress("NewApi")
    private fun serveVideoClient(socket: Socket, expectedToken: String) {
        var opened: OpenedSurfaceCodec? = null
        var virtualDisplay: VirtualDisplay? = null
        val running = AtomicBoolean(true)
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 2000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val tokenLine = reader.readLine()?.takeIf { it.length <= 128 } ?: return
            if (!MessageDigest.isEqual(
                    tokenLine.toByteArray(Charsets.UTF_8),
                    expectedToken.toByteArray(Charsets.UTF_8),
                )
            ) {
                return
            }
            val reqLine = reader.readLine()?.takeIf { it.length <= 256 } ?: return
            val parts = reqLine.split(' ')
            if (parts.size < 9 || parts[0] != "V") return
            val codecType = parts[2].toIntOrNull() ?: return
            val width = parts[3].toIntOrNull() ?: return
            val height = parts[4].toIntOrNull() ?: return
            val dpi = parts[5].toIntOrNull() ?: return
            val bitrate = parts[6].toIntOrNull() ?: return
            val fps = parts[7].toIntOrNull() ?: return
            val hardware = (parts[8].toIntOrNull() ?: 1) != 0
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                codecType !in 0..1 ||
                width !in 16..4096 ||
                height !in 16..4096 ||
                dpi !in 72..1000 ||
                bitrate <= 0 ||
                fps !in 1..120
            ) {
                return
            }
            socket.soTimeout = 0

            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 65536))
            opened = try {
                SurfaceCodecFactory.open(codecType, width, height, bitrate, fps, hardware)
            } catch (t: Throwable) {
                System.err.println("RootInputServer: privileged encoder open failed: ${t.message}")
                runCatching { out.writeInt(-2); out.flush() }
                return
            }
            virtualDisplay = try {
                createMirrorDisplay(width, height, dpi, opened.inputSurface)
            } catch (t: Throwable) {
                System.err.println("RootInputServer: privileged VirtualDisplay failed: ${t.message}")
                runCatching { out.writeInt(-1); out.flush() }
                return
            }

            // Signal handshake success before draining frames
            out.writeInt(0)
            out.flush()

            val requestedBitrate = AtomicInteger(opened.bitrate)
            val requestSync = AtomicBoolean(false)
            Thread({
                try {
                    while (running.get()) {
                        val line = reader.readLine() ?: break
                        when {
                            line == "K" -> requestSync.set(true)
                            line.startsWith("B ") -> {
                                val nextRate = line.substring(2).trim().toIntOrNull()
                                if (nextRate != null && nextRate > 0) {
                                    requestedBitrate.set(opened.bitrateRange.clamp(nextRate))
                                }
                            }
                        }
                    }
                } catch (ignored: Throwable) {
                } finally {
                    running.set(false)
                }
            }, "RootVideoCmdReader").apply { isDaemon = true; start() }

            var appliedBitrate = opened.bitrate
            val packets = SurfaceVideoPacketizer()
            val info = MediaCodec.BufferInfo()
            val codec = opened.codec
            while (running.get()) {
                if (requestSync.compareAndSet(true, false)) {
                    runCatching {
                        codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                    }
                }
                val nextBitrate = requestedBitrate.get()
                if (appliedBitrate != nextBitrate) {
                    runCatching {
                        codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, nextBitrate) })
                    }
                    appliedBitrate = nextBitrate
                }
                when (val index = codec.dequeueOutputBuffer(info, 10_000L)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        var config = byteArrayOf()
                        for (key in arrayOf("csd-0", "csd-1", "csd-2")) {
                            val buffer = format.getByteBuffer(key)?.duplicate() ?: continue
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            config += bytes
                        }
                        if (config.isNotEmpty()) packets.setConfig(config)
                    }
                    else -> if (index >= 0) {
                        var payloadToSend: ByteArray? = null
                        var isKey = false
                        var ptsUs = 0L
                        var eos = false
                        try {
                            check(info.flags and MediaCodec.BUFFER_FLAG_PARTIAL_FRAME == 0) { "Partial video packet" }
                            eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            if (!eos && info.size > 0) {
                                check(info.size <= 8 * 1024 * 1024) { "Video packet too large" }
                                val buffer = checkNotNull(codec.getOutputBuffer(index)).duplicate()
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size)
                                buffer.get(bytes)
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                    packets.setConfig(bytes)
                                } else {
                                    isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                    payloadToSend = packets.frame(bytes, isKey)
                                    ptsUs = info.presentationTimeUs
                                }
                            }
                        } finally {
                            codec.releaseOutputBuffer(index, false)
                        }
                        if (eos) break
                        if (payloadToSend != null) {
                            out.writeInt(payloadToSend.size)
                            out.writeByte(if (isKey) 1 else 0)
                            out.writeLong(ptsUs)
                            out.write(payloadToSend)
                            out.flush()
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            if (running.get()) {
                System.err.println("RootInputServer video session error: ${t.message}")
            }
        } finally {
            running.set(false)
            runCatching { virtualDisplay?.setSurface(null) }
            runCatching { virtualDisplay?.release() }
            runCatching { opened?.codec?.stop() }
            runCatching { opened?.codec?.release() }
            runCatching { opened?.inputSurface?.release() }
            runCatching { socket.close() }
        }
    }
}
