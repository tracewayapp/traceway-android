package com.tracewayapp.symbols

import android.util.Log
import com.tracewayapp.traceway.TracewayLogcat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.util.CheckClassAdapter
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.InvocationTargetException

/**
 * The `logcat` rewrite on a class javac compiled (`samples.LogCalls`,
 * against stand-ins for `android.util.Log` and the SDK's `TracewayLogcat`):
 * the call sites it leaves, and the rewritten class loaded and run.
 */
class LogcatRewriteTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun reset() {
        Log.calls.clear()
        TracewayLogcat.recorded.clear()
    }

    private fun original(): ByteArray =
        javaClass.classLoader.getResourceAsStream("samples/LogCalls.class")!!.use { it.readBytes() }

    /** Through the rewrite as AGP runs it by default (`COPY_FRAMES`): frames and maximums written as visited. */
    private fun rewritten(minPriority: Int = LogcatRewrite.INFO): ByteArray {
        val writer = ClassWriter(0)
        ClassReader(original()).accept(LogcatRewrite.Visitor(Opcodes.ASM9, writer, minPriority), 0)
        return writer.toByteArray()
    }

    /** `owner.name` of every static call in [method], in order. */
    private fun calls(bytes: ByteArray, method: String): List<String> {
        val out = ArrayList<String>()
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(access: Int, name: String?, d: String?, s: String?, e: Array<out String>?): MethodVisitor? {
                if (name != method) return null
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitMethodInsn(opcode: Int, owner: String, n: String, desc: String, itf: Boolean) {
                        if (opcode == Opcodes.INVOKESTATIC && (owner == LogcatRewrite.LOG || owner == LogcatRewrite.BRIDGE)) {
                            out.add("${owner.substringAfterLast('/')}.$n")
                        }
                    }
                }
            }
        }, 0)
        return out
    }

    private class Loader(parent: ClassLoader) : ClassLoader(parent) {
        fun define(bytes: ByteArray): Class<*> = defineClass("samples.LogCalls", bytes, 0, bytes.size)
    }

    /** The rewritten class in a loader of its own (so the JVM verifies it), calling the shared stand-ins. */
    private fun load(bytes: ByteArray): Class<*> = Loader(javaClass.classLoader).define(bytes)

    private fun call(type: Class<*>, name: String, vararg args: Any?): Any? {
        val method = type.declaredMethods.single { it.name == name }
        val target = if (java.lang.reflect.Modifier.isStatic(method.modifiers)) null else type.getDeclaredConstructor().newInstance()
        return try {
            method.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    @Test
    fun everyLogCallAtOrAboveTheMinimumGoesThroughTheBridge() {
        val bytes = rewritten(LogcatRewrite.INFO)
        assertEquals(
            listOf(
                "Log.v", "Log.v", "Log.d", "Log.d",
                "TracewayLogcat.i", "TracewayLogcat.i",
                "TracewayLogcat.w", "TracewayLogcat.w", "TracewayLogcat.w",
                "TracewayLogcat.e", "TracewayLogcat.e",
                "TracewayLogcat.wtf", "TracewayLogcat.wtf", "TracewayLogcat.wtf",
            ),
            calls(bytes, "everyOverload"),
        )
        assertEquals("the original is left as it was", 14, calls(original(), "everyOverload").count { it.startsWith("Log.") })
    }

    @Test
    fun theBridgeLogsFirstAndReturnsWhatLogReturned() {
        val plain = load(original())
        val expected = call(plain, "everyOverload") as Int
        val logged = ArrayList(Log.calls)
        reset()

        val sum = call(load(rewritten(LogcatRewrite.VERBOSE)), "everyOverload") as Int
        assertEquals("the same results", expected, sum)
        assertEquals("Log saw the same calls, in order", logged, Log.calls)
        assertEquals(
            listOf("v|T", "v|T", "d|T", "d|T", "i|T", "i|T", "w|T", "w|T", "w|T", "e|T", "e|T", "wtf|T", "wtf|T", "wtf|T"),
            TracewayLogcat.recorded,
        )
    }

    @Test
    fun printlnCarriesTheMinimumLevelAndFitsItsStack() {
        val bytes = rewritten(LogcatRewrite.WARN)
        assertEquals(listOf("TracewayLogcat.println", "TracewayLogcat.println"), calls(bytes, "timberLike"))
        assertEquals(listOf("TracewayLogcat.println"), calls(bytes, "withLongs"))
        val type = load(bytes)
        // Below the minimum: logged, not recorded.
        val r = call(type, "timberLike", LogcatRewrite.INFO, "Timber", "hello") as Int
        assertEquals(listOf("println4|Timber|hello|-", "println4|Timber|he|-"), Log.calls)
        assertEquals(11 + 12, r)
        assertTrue(TracewayLogcat.recorded.isEmpty())
        call(type, "timberLike", LogcatRewrite.ERROR, "Timber", "oops")
        assertEquals(listOf("println6|Timber", "println6|Timber"), TracewayLogcat.recorded)
        @Suppress("UNCHECKED_CAST")
        val longs = call(type, "withLongs", 3L, 4L) as LongArray
        assertEquals(listOf(3L, 4L, 12L), longs.toList())
        assertEquals("println5|L", TracewayLogcat.recorded.last())
    }

    @Test
    fun theRewrittenClassVerifies() {
        for (min in listOf(LogcatRewrite.VERBOSE, LogcatRewrite.INFO, LogcatRewrite.ASSERT)) {
            val out = StringWriter()
            CheckClassAdapter.verify(ClassReader(rewritten(min)), javaClass.classLoader, false, PrintWriter(out))
            assertEquals("min $min", "", out.toString())
        }
    }

    @Test
    fun otherLogMethodsAreLeftAlone() {
        assertEquals(listOf("Log.isLoggable", "Log.getStackTraceString"), calls(rewritten(LogcatRewrite.VERBOSE), "untouched"))
        assertEquals(true, call(load(rewritten(LogcatRewrite.VERBOSE)), "untouched"))
    }

    @Test
    fun theSdksOwnClassesAreNeverRewritten() {
        assertFalse(LogcatRewrite.isInstrumentable("com.tracewayapp.traceway.TracewayLogcat"))
        assertFalse(LogcatRewrite.isInstrumentable("com.tracewayapp.traceway.internal.LogCapture"))
        assertFalse(LogcatRewrite.isInstrumentable("android.util.Log"))
        assertTrue(LogcatRewrite.isInstrumentable("com.example.app.MainActivity"))
        assertTrue("Timber is the app's dependency, rewritten", LogcatRewrite.isInstrumentable("timber.log.Timber\$DebugTree"))
        assertTrue(LogcatRewrite.isInstrumentable("com.tracewayapp.tracewayother.X"))
        assertFalse(LogcatRewrite.isInstrumentable("com.tracewayapp.traceway.TracewayClient\$Companion"))
        assertFalse(LogcatRewrite.isInstrumentable("com.tracewayapp.traceway.navigation.TracewayNavigation"))
        assertTrue("an app in a package of its own under the SDK's", LogcatRewrite.isInstrumentable("com.tracewayapp.traceway.example.MainActivity"))
    }

    /** A Maven repository on disk holding `com.tracewayapp:traceway` at [versions] and a library that depends on 1.0.1. */
    private fun repository(vararg versions: String): java.io.File {
        val repo = tmp.newFolder("repo")
        fun pom(group: String, artifact: String, version: String, dependency: String? = null) {
            val dir = java.io.File(repo, "${group.replace('.', '/')}/$artifact/$version").apply { mkdirs() }
            val deps = dependency?.split(':')?.let { (g, a, v) ->
                "<dependencies><dependency><groupId>$g</groupId><artifactId>$a</artifactId><version>$v</version></dependency></dependencies>"
            } ?: ""
            java.io.File(dir, "$artifact-$version.pom").writeText(
                "<project><modelVersion>4.0.0</modelVersion><groupId>$group</groupId><artifactId>$artifact</artifactId>" +
                    "<version>$version</version><packaging>pom</packaging>$deps</project>",
            )
        }
        for (v in versions) pom("com.tracewayapp", "traceway", v)
        pom("com.example", "shop-core", "1.0", "com.tracewayapp:traceway:1.0.1")
        return repo
    }

    /** What the plugin finds of the SDK on a classpath that declares [dependency]. */
    private fun sdkOn(repo: java.io.File, dependency: String): TracewaySdk.Found? {
        val project = ProjectBuilder.builder().withProjectDir(tmp.newFolder()).build()
        project.repositories.maven { it.url = repo.toURI() }
        val runtime = project.configurations.create("runtime")
        project.dependencies.add("runtime", dependency)
        return TracewaySdk.find(runtime.incoming.resolutionResult.root)
    }

    /** AND2-1: an app on an SDK without `TracewayLogcat` must not have its `Log` calls rewritten (NoClassDefFoundError). */
    @Test
    fun onlyAnSdkOf210OrNewerHasItsLogCallsRewritten() {
        val repo = repository("1.0.1", "2.0.0-beta.1", "2.1.0-beta.1", "2.1.0", "3.0.0")
        for ((dependency, rewritten) in listOf(
            "com.tracewayapp:traceway:1.0.1" to false,
            "com.tracewayapp:traceway:2.0.0-beta.1" to false,
            "com.tracewayapp:traceway:2.1.0-beta.1" to true,
            "com.tracewayapp:traceway:2.1.0" to true,
            "com.tracewayapp:traceway:3.0.0" to true,
            "com.example:shop-core:1.0" to false,
        )) {
            val found = sdkOn(repo, dependency)
            assertEquals(dependency, dependency.substringAfterLast(':').let { if (dependency.startsWith("com.example")) "1.0.1" else it }, found?.version)
            assertEquals(dependency, rewritten, found!!.hasLogcatBridge)
        }
        assertNull("no SDK, nothing to rewrite to", TracewaySdk.find(ProjectBuilder.builder().withProjectDir(tmp.newFolder()).build().configurations.create("empty").incoming.resolutionResult.root))
        assertTrue("the SDK built in the same build has it", TracewaySdk.Found(null).hasLogcatBridge)
    }

    @Test
    fun versionsCompareByTheirNumbers() {
        assertTrue(TracewaySdk.atLeast("2.1.0", "2.1.0"))
        assertTrue(TracewaySdk.atLeast("2.1.0-SNAPSHOT", "2.1.0"))
        assertTrue(TracewaySdk.atLeast("2.1", "2.1.0"))
        assertTrue(TracewaySdk.atLeast("2.10.0", "2.1.0"))
        assertTrue(TracewaySdk.atLeast("10.0.0", "2.1.0"))
        assertFalse(TracewaySdk.atLeast("2.0.9", "2.1.0"))
        assertFalse(TracewaySdk.atLeast("1.0.1", "2.1.0"))
        assertFalse(TracewaySdk.atLeast("latest.release", "2.1.0"))
    }

    /** AND2-9: an app that strips `Log` from release builds gets the rewritten calls stripped the same way. */
    @Test
    fun logStrippingRulesAreMirroredForTheBridge() {
        val stripping = """
            -keep class com.example.** { *; }
            # -assumenosideeffects class android.util.Log { public static *** e(...); }
            -assumenosideeffects class android.util.Log {
                public static boolean isLoggable(java.lang.String, int);
                public static int v(...);
                public static int d(...);
                public static *** i(...);
            }
        """.trimIndent()
        val mirrored = LogcatRewrite.mirrorStripRules(listOf("-dontwarn x.**", stripping))!!
        assertTrue(mirrored, mirrored.contains("-assumenosideeffects class com.tracewayapp.traceway.TracewayLogcat {"))
        assertEquals(
            listOf("v", "d", "i"),
            Regex("""public static int (\w+)\(\.\.\.\);""").findAll(mirrored).map { it.groupValues[1] }.toList(),
        )
        assertTrue("a commented rule strips nothing", !mirrored.contains(" e(...)"))
        val all = LogcatRewrite.mirrorStripRules(listOf("-assumenosideeffects class android.util.Log { *; public static *** *(...); }"))!!
        assertEquals(7, Regex("""public static int""").findAll(all).count())
        assertNull(LogcatRewrite.mirrorStripRules(listOf("-keep class android.util.Log { *; }", "")))
        assertNull(LogcatRewrite.mirrorStripRules(emptyList()))
    }

    @Test
    fun minimumLevelNames() {
        assertEquals(LogcatRewrite.VERBOSE, LogcatRewrite.priorityOf("verbose"))
        assertEquals(LogcatRewrite.DEBUG, LogcatRewrite.priorityOf("Debug"))
        assertEquals(LogcatRewrite.INFO, LogcatRewrite.priorityOf(" info "))
        assertEquals(LogcatRewrite.WARN, LogcatRewrite.priorityOf("warn"))
        assertEquals(LogcatRewrite.ERROR, LogcatRewrite.priorityOf("error"))
        assertEquals(LogcatRewrite.ASSERT, LogcatRewrite.priorityOf("assert"))
        assertEquals(LogcatRewrite.ASSERT, LogcatRewrite.priorityOf("fatal"))
        assertNull(LogcatRewrite.priorityOf("loud"))
    }

    @Test
    fun theExtensionHasTheLogcatBlockWithItsDefaults() {
        val project = ProjectBuilder.builder().withProjectDir(tmp.newFolder("app")).build()
        val ext = project.extensions.create("traceway", TracewayExtension::class.java)
        assertTrue(ext.logcat.enabled.get())
        assertEquals("info", ext.logcat.minLevel.get())
        assertTrue(ext.logcat.includeDependencies.get())
        assertEquals(emptySet<String>(), ext.logcat.disabledVariants.get())
        ext.logcat {
            it.enabled.set(false)
            it.minLevel.set("warn")
            it.includeDependencies.set(false)
            it.disabledFor("release", "benchmark")
        }
        assertFalse(ext.logcat.enabled.get())
        assertEquals("warn", ext.logcat.minLevel.get())
        assertFalse(ext.logcat.includeDependencies.get())
        assertEquals(setOf("release", "benchmark"), ext.logcat.disabledVariants.get())
        // The upload settings are where they were.
        ext.uploadToken.set("t")
        assertEquals("t", ext.uploadToken.get())
    }
}
