package com.skywing.desktop.automation

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.win32.StdCallLibrary
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** One node of a Java Access Bridge tree, read from another process. */
internal class JabNode(
    val path: String,
    override val name: String,
    override val description: String,
    override val roleName: String,
    val states: Set<String>,
    val actions: List<String>,
    val value: String?,
    val childCount: Int,
    internal val handle: Long,
) : ControlView {
    override val enabled: Boolean get() = "enabled" in states
    val checked: Boolean get() = "checked" in states
    val selected: Boolean get() = "selected" in states
    val focusable: Boolean get() = "focusable" in states

    /** The path of the node that contains this one. */
    val parentPath: String get() = path.substringBeforeLast('/', "")

    override fun toString() = "'$label' $roleName [${states.sorted().joinToString(",")}]" +
        (value?.let { " value=$it" } ?: "") + (if (actions.isEmpty()) "" else " actions=$actions") +
        " (name='$name', description='$description') at $path"
}

/** A snapshot of one window's JAB tree. Its handles stay valid until [AccessBridgeClient.release]. */
internal class JabTree(val hwnd: Long, val vm: Int, val nodes: List<JabNode>) {
    fun dump(): String = nodes.joinToString("\n") { "  ".repeat(it.path.count { c -> c == '/' }) + it }
}

/**
 * Reads and operates SkyWing's Java accessibility tree from outside the app, exactly as screen readers do: through
 * windowsaccessbridge-64.dll from the app image's own runtime. Every call runs on one thread that also pumps Windows
 * messages, which the bridge needs to discover Java VMs. No keyboard or mouse input is used.
 */
internal class AccessBridgeClient private constructor(dll: Path) : AutoCloseable {
    @Suppress("FunctionName")
    private interface Bridge : Library {
        fun Windows_run()
        fun isJavaWindow(hwnd: Pointer): Boolean
        fun getAccessibleContextFromHWND(hwnd: Pointer, vm: IntByReference, context: LongByReference): Boolean
        fun getAccessibleContextInfo(vm: Int, context: Long, info: Pointer): Boolean
        fun getAccessibleChildFromContext(vm: Int, context: Long, index: Int): Long
        fun getAccessibleActions(vm: Int, context: Long, actions: Pointer): Boolean
        fun doAccessibleActions(vm: Int, context: Long, actions: Pointer, failure: IntByReference): Boolean
        fun getCurrentAccessibleValueFromContext(vm: Int, context: Long, value: Pointer, length: Short): Boolean
        fun releaseJavaObject(vm: Int, context: Long)
    }

    @Suppress("FunctionName")
    private interface User32 : StdCallLibrary {
        fun PeekMessageW(msg: Msg, hwnd: Pointer?, min: Int, max: Int, remove: Int): Boolean
        fun TranslateMessage(msg: Msg): Boolean
        fun DispatchMessageW(msg: Msg): Long
    }

    @Structure.FieldOrder("hwnd", "message", "wParam", "lParam", "time", "x", "y", "private")
    class Msg : Structure() {
        @JvmField var hwnd: Pointer? = null
        @JvmField var message = 0
        @JvmField var wParam: Long = 0
        @JvmField var lParam: Long = 0
        @JvmField var time = 0
        @JvmField var x = 0
        @JvmField var y = 0
        @JvmField var private = 0
    }

    private val queue = LinkedBlockingQueue<FutureTask<*>>()
    @Volatile private var running = true
    private lateinit var bridge: Bridge
    private lateinit var user32: User32
    private val thread = Thread({
        user32 = Native.load("user32", User32::class.java)
        bridge = Native.load(dll.toString(), Bridge::class.java)
        bridge.Windows_run()
        val msg = Msg()
        while (running) {
            while (user32.PeekMessageW(msg, null, 0, 0, 1)) { user32.TranslateMessage(msg); user32.DispatchMessageW(msg) }
            queue.poll(20, TimeUnit.MILLISECONDS)?.run()
        }
    }, "skywing-access-bridge").apply { isDaemon = true; start() }

    private fun <T> call(block: () -> T): T {
        val task = FutureTask(Callable(block))
        queue.put(task)
        return try { task.get(30, TimeUnit.SECONDS) } catch (e: ExecutionException) { throw e.cause ?: e }
    }

    fun isJavaWindow(hwnd: Long): Boolean = call { bridge.isJavaWindow(Pointer(hwnd)) }

    /** Reads the whole tree of a Java window. Release it with [release] once its handles are no longer needed. */
    fun snapshot(hwnd: Long): JabTree = call {
        val vm = IntByReference()
        val root = LongByReference()
        check(bridge.getAccessibleContextFromHWND(Pointer(hwnd), vm, root)) { "No Java accessible context for window $hwnd" }
        val nodes = mutableListOf<JabNode>()
        val info = Memory(INFO_SIZE)
        val actions = Memory(ACTIONS_SIZE)
        val value = Memory(512)
        fun visit(context: Long, path: String, depth: Int) {
            info.clear()
            if (!bridge.getAccessibleContextInfo(vm.value, context, info)) return
            val children = info.getInt(INFO_CHILDREN)
            var names = emptyList<String>()
            if (info.getInt(INFO_ACTION) != 0) {
                actions.clear()
                if (bridge.getAccessibleActions(vm.value, context, actions)) {
                    names = (0 until actions.getInt(0).coerceIn(0, 256)).map { actions.getWideString(4L + it * 512L) }
                }
            }
            var current: String? = null
            if (info.getInt(INFO_INTERFACES) and 1 != 0) {
                value.clear()
                if (bridge.getCurrentAccessibleValueFromContext(vm.value, context, value, 256)) current = value.getWideString(0)
            }
            nodes += JabNode(
                path = path,
                name = info.getWideString(0),
                description = info.getWideString(2048),
                roleName = info.getWideString(4096 + 512),
                states = info.getWideString(4096 + 1536).split(',').filter { it.isNotEmpty() }.toSet(),
                actions = names,
                value = current,
                childCount = children,
                handle = context,
            )
            if (depth < 60 && nodes.size < 3000) {
                for (i in 0 until children.coerceAtLeast(0)) {
                    val child = bridge.getAccessibleChildFromContext(vm.value, context, i)
                    if (child != 0L) visit(child, "$path/$i", depth + 1)
                }
            }
        }
        visit(root.value, "0", 0)
        JabTree(hwnd, vm.value, nodes)
    }

    fun release(tree: JabTree) = call { tree.nodes.forEach { bridge.releaseJavaObject(tree.vm, it.handle) } }

    /** Performs the node's first accessible action ("click"). The node must come from an unreleased [tree]. */
    fun doFirstAction(tree: JabTree, node: JabNode): Unit = call {
        val todo = Memory(TODO_SIZE).apply { clear() }
        todo.setInt(0, 1)
        todo.setWideString(4, node.actions.first())
        val failure = IntByReference(-1)
        check(bridge.doAccessibleActions(tree.vm, node.handle, todo, failure)) { "Accessible action '${node.actions.first()}' failed on $node" }
    }

    override fun close() { running = false }

    companion object {
        // AccessibleContextInfo (JDK 17 AccessBridgePackages.h): wchar name[1024], description[1024], role[256],
        // role_en_US[256], states[256], states_en_US[256], then jints.
        private const val INFO_SIZE = 4096L + 4 * 512 + 11 * 4
        private const val INFO_CHILDREN = 4096L + 2048 + 4
        private const val INFO_ACTION = 4096L + 2048 + 7 * 4
        private const val INFO_INTERFACES = 4096L + 2048 + 10 * 4
        private const val ACTIONS_SIZE = 4L + 256 * 512
        private const val TODO_SIZE = 4L + 32 * 512

        private val clients = mutableMapOf<Path, AccessBridgeClient>()

        /** One client per bridge DLL for the test JVM; the DLL is loaded once and kept for later sessions. */
        @Synchronized
        fun forImage(image: Path): AccessBridgeClient {
            val dll = image.resolve("runtime/bin/windowsaccessbridge-64.dll").toAbsolutePath().normalize()
            check(Files.isRegularFile(dll)) { "The test image has no Java Access Bridge ($dll); it must include jdk.accessibility" }
            return clients.getOrPut(dll) { AccessBridgeClient(dll) }
        }
    }
}
