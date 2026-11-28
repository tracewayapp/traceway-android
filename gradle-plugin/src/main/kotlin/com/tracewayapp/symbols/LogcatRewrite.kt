package com.tracewayapp.symbols

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * The `logcat` rewrite (CONTRACT §14.3): every call to `android.util.Log`'s
 * `v`, `d`, `i`, `w`, `e`, `wtf` and `println` in a class becomes a call to
 * the SDK's `com.tracewayapp.traceway.TracewayLogcat`, with the same
 * arguments and result, which calls the real `Log` first and then records
 * the line. Calls of a level below the minimum (`logcat.minLevel`) are left
 * as they are; `println`'s level is known only when it runs, so the
 * minimum goes to the bridge as one more argument.
 *
 * Nothing else changes: no branch, no local, so the stack map frames stay
 * valid; the one extra argument of `println` raises the method's maximum
 * stack by one.
 */
object LogcatRewrite {
    const val LOG = "android/util/Log"
    const val BRIDGE = "com/tracewayapp/traceway/TracewayLogcat"
    const val BRIDGE_CLASS = "com.tracewayapp.traceway.TracewayLogcat"

    private const val TAG_MSG = "(Ljava/lang/String;Ljava/lang/String;)I"
    private const val TAG_MSG_TR = "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I"
    private const val TAG_TR = "(Ljava/lang/String;Ljava/lang/Throwable;)I"
    const val PRINTLN = "(ILjava/lang/String;Ljava/lang/String;)I"
    const val BRIDGE_PRINTLN = "(ILjava/lang/String;Ljava/lang/String;I)I"

    /** `android.util.Log`'s priorities. */
    const val VERBOSE = 2
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6
    const val ASSERT = 7

    /** The methods rewritten, by name: their priority and the descriptors the bridge has. */
    private val METHODS: Map<String, Pair<Int, Set<String>>> = mapOf(
        "v" to (VERBOSE to setOf(TAG_MSG, TAG_MSG_TR)),
        "d" to (DEBUG to setOf(TAG_MSG, TAG_MSG_TR)),
        "i" to (INFO to setOf(TAG_MSG, TAG_MSG_TR)),
        "w" to (WARN to setOf(TAG_MSG, TAG_MSG_TR, TAG_TR)),
        "e" to (ERROR to setOf(TAG_MSG, TAG_MSG_TR)),
        "wtf" to (ASSERT to setOf(TAG_MSG, TAG_TR, TAG_MSG_TR)),
    )

    /**
     * The priority of a `logcat.minLevel` value: `verbose`, `debug`,
     * `info`, `warn`, `error` or `assert` (also the SDK's `trace`, `fatal`
     * and the letters `v`, `d`, `i`, `w`, `e`); null for anything else.
     */
    fun priorityOf(level: String): Int? = when (level.trim().lowercase()) {
        "verbose", "trace", "v" -> VERBOSE
        "debug", "d" -> DEBUG
        "info", "i" -> INFO
        "warn", "warning", "w" -> WARN
        "error", "e" -> ERROR
        "assert", "fatal", "wtf" -> ASSERT
        else -> null
    }

    /** The SDK's packages under `com.tracewayapp.traceway`; an app's own package there (`.example`) is the app's. */
    private val SDK_PACKAGES = setOf(
        "compose", "core", "diagnostics", "interaction", "internal", "metrics", "navigation", "network", "opamp",
        "otel", "persistence", "replay", "session", "wireframe",
    )

    /**
     * Whether a class is rewritten: not the SDK's own (its bridge calls the
     * real `Log`, and the SDK's lines are not the app's): the classes of
     * `com.tracewayapp.traceway` itself and of its [SDK_PACKAGES].
     */
    fun isInstrumentable(className: String): Boolean {
        if (className == "android.util.Log") return false
        val rest = className.removePrefix("com.tracewayapp.traceway.")
        if (rest == className) return true
        val dot = rest.indexOf('.')
        return dot >= 0 && rest.substring(0, dot) !in SDK_PACKAGES
    }

    private val STRIP_LOG = Regex("""-assumenosideeffects\s+class\s+android\.util\.Log\s*\{([^}]*)}""")
    private val STRIPPED_METHOD = Regex("""(.+?\s+)(v|d|i|w|e|wtf|println|\*)\s*\(([^()]*)\)""")
    private val BRIDGED = listOf("v", "d", "i", "w", "e", "wtf", "println")

    /**
     * The R8 rule that strips `TracewayLogcat` calls the way [rules] (the
     * text of a variant's ProGuard files) strip `android.util.Log` calls
     * with `-assumenosideeffects`, for the same methods; null when they do
     * not strip `Log`. A rewritten `Log.d` is a `TracewayLogcat.d` call,
     * which the app's rule would not match.
     */
    fun mirrorStripRules(rules: List<String>): String? {
        val methods = LinkedHashSet<String>()
        for (text in rules) {
            val code = text.lineSequence().joinToString("\n") { it.substringBefore('#') }
            for (block in STRIP_LOG.findAll(code)) {
                for (member in block.groupValues[1].split(';').map { it.trim() }) {
                    if (member == "*") {
                        BRIDGED.forEach { methods.add("public static int $it(...);") }
                        continue
                    }
                    val match = STRIPPED_METHOD.matchEntire(member) ?: continue
                    val (declaration, selector, arguments) = match.destructured
                    // Every bridged android.util.Log method returns an int.
                    val prefix = declaration.trimEnd().replace(Regex("""(?<!\S)\*\*\*$"""), "int")
                    val names = if (selector == "*") BRIDGED else listOf(selector)
                    for (name in names) {
                        val args = arguments.trim()
                        val bridgeArgs = if (name == "println" && args != "...") {
                            if (args.isEmpty()) continue else "$args,int"
                        } else args
                        methods.add("$prefix $name($bridgeArgs);")
                    }
                }
            }
        }
        if (methods.isEmpty()) return null
        val out = StringBuilder()
        out.append("# Generated by the Traceway Gradle plugin: your rules strip android.util.Log calls, so the calls\n")
        out.append("# its logcat rewrite made of them go too.\n")
        out.append("-assumenosideeffects class ").append(BRIDGE_CLASS).append(" {\n")
        for (member in methods) out.append("    ").append(member).append('\n')
        out.append("}\n")
        return out.toString()
    }

    /** Rewrites the `Log` calls of a class as it goes from [next]'s reader to its writer. */
    class Visitor(api: Int, next: ClassVisitor, private val minPriority: Int) : ClassVisitor(api, next) {
        override fun visitMethod(
            access: Int,
            name: String?,
            descriptor: String?,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor? {
            val mv = super.visitMethod(access, name, descriptor, signature, exceptions) ?: return null
            return Method(api, mv, minPriority)
        }
    }

    private class Method(api: Int, next: MethodVisitor, private val minPriority: Int) : MethodVisitor(api, next) {
        private var extraStack = 0

        override fun visitMethodInsn(opcode: Int, owner: String?, name: String?, descriptor: String?, isInterface: Boolean) {
            if (opcode == Opcodes.INVOKESTATIC && owner == LOG && !isInterface && name != null && descriptor != null) {
                if (name == "println" && descriptor == PRINTLN) {
                    super.visitIntInsn(Opcodes.BIPUSH, minPriority)
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "println", BRIDGE_PRINTLN, false)
                    extraStack = 1
                    return
                }
                val method = METHODS[name]
                if (method != null && method.first >= minPriority && descriptor in method.second) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, name, descriptor, false)
                    return
                }
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
        }

        override fun visitMaxs(maxStack: Int, maxLocals: Int) {
            super.visitMaxs(maxStack + extraStack, maxLocals)
        }
    }
}
