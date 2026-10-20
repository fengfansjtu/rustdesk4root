package com.carriez.flutter_hbb

import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

class RootInputQueue(capacity: Int = 500) {
    private val commands = LinkedBlockingDeque<String>(capacity)

    @Synchronized
    fun offer(command: String): Boolean {
        // Coordinates are absolute here, so consecutive moves can share one slot.
        if (command.startsWith("M ") && commands.peekLast()?.startsWith("M ") == true) {
            commands.pollLast()
        }
        if (commands.offerLast(command)) return true
        val move = commands.firstOrNull { it.startsWith("M ") } ?: return false
        commands.removeFirstOccurrence(move)
        return commands.offerLast(command)
    }

    fun poll(timeout: Long, unit: TimeUnit): String? = commands.pollFirst(timeout, unit)

    @Synchronized
    fun cancel() {
        commands.clear()
        commands.offerLast("C")
    }

    @Synchronized
    fun clear() = commands.clear()
}
