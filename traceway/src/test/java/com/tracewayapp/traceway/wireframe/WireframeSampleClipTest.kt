package com.tracewayapp.traceway.wireframe

import com.tracewayapp.traceway.internal.Json
import com.tracewayapp.traceway.internal.ManualScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A clip of a typical screen, built with the same CSS builders as the
 * captures, played through the mirror and written to
 * `build/wireframe-sample/` (events.json, checkpoints.json) for
 * `tools/wireframe/replay.mjs`, which renders it with rrweb's player.
 */
class WireframeSampleClipTest {
    private var nextId = firstCapturedNodeId
    private val ids = HashMap<String, Int>()
    private val nodes = HashMap<Int, VNode>()

    private fun id(key: String) = ids.getOrPut(key) { nextId++ }

    /** An element that is the same object as last time when nothing changed, as a capture makes it. */
    private fun el(key: String, style: String, children: List<VNode> = emptyList(), cls: String? = null, tag: String = "div"): VNode {
        val id = id(key)
        val attrs = LinkedHashMap<String, String>()
        if (cls != null) attrs["class"] = cls
        attrs["style"] = style
        val node = VNode.element(id, tag, attrs, children)
        val prev = nodes[id]
        if (prev != null && prev.sameAs(node)) return prev
        nodes[id] = node
        return node
    }

    private fun txt(key: String, text: String): VNode {
        val id = id(key)
        val prev = nodes[id]
        if (prev != null && prev.text == text) return prev
        return VNode.text(id, text).also { nodes[id] = it }
    }

    private fun css(block: StringBuilder.() -> Unit): String = StringBuilder().apply(block).toString()

    private fun label(key: String, x: Float, y: Float, w: Float, text: String, size: Float = 16f, color: Int = 0xDE000000.toInt(), weight: Int = 400, align: TextAlign = TextAlign.LEFT): VNode {
        val look = TextLook().apply {
            fontSize = size
            lineHeight = size * 1.5f
            this.color = color
            this.weight = weight
            this.align = align
        }
        val sb = StringBuilder()
        sb.append("left:").appendPx(x).append(";top:").appendPx(y).append(";width:").appendPx(w).append(";height:").appendPx(look.lineHeight).append(';')
        val cls = sb.appendTextCss(look)
        return el(key, sb.toString(), listOf(txt("$key.t", text)), cls)
    }

    private fun screen(scroll: Float, checked: Boolean, progress: Float, dialog: Boolean): VNode {
        val primary = 0xFF6750A4.toInt()
        val bar = el(
            "toolbar",
            css { append("left:0;top:0;width:412px;height:88px;").appendFill(primary).appendShadows(4f, 0xFF000000.toInt(), 0f, 0) },
            listOf(
                el("nav", "left:4px;top:36px;width:48px;height:48px;color:#ffffff;", cls = "ic"),
                label("title", 64f, 46f, 280f, "Wireframe sample", 22f, 0xFFFFFFFF.toInt(), 500),
            ),
        )
        val rows = (0 until 14).map { i ->
            el(
                "row$i",
                "left:0;top:${i * 72}px;width:412px;height:72px;",
                listOf(
                    el("row$i.icon", "left:16px;top:16px;width:40px;height:40px;border-radius:20px;", cls = "im"),
                    label("row$i.title", 72f, 12f, 300f, "List item $i", 16f),
                    label("row$i.sub", 72f, 38f, 300f, "Secondary text that is long enough to be cut off by the box", 14f, 0x99000000.toInt()),
                ),
            )
        }
        val content = el("list.content", css { append("left:0;top:").appendPx(-scroll).append(';') }, rows)
        val list = el("list", "left:0;top:88px;width:412px;height:500px;overflow:hidden;", listOf(content))
        val toggles = el(
            "toggles", "left:0;top:588px;width:412px;height:120px;background-color:#f3edf7;",
            listOf(
                el(
                    "check", css { append("left:16px;top:12px;width:48px;height:48px;") },
                    listOf(
                        el(
                            "check.box", css { WidgetCss.checkbox(this, 24f, 24f, checked, primary, DEFAULT_OUTLINE) },
                            if (checked) listOf(el("check.mark", css { WidgetCss.checkMark(this) })) else emptyList(),
                        ),
                    ),
                ),
                label("check.label", 72f, 24f, 200f, "Remember me"),
                el(
                    "switch.track", css { WidgetCss.switchTrack(this, 360f, 36f, 52f, 32f, checked, primary, DEFAULT_OUTLINE, false) },
                    listOf(el("switch.thumb", css { WidgetCss.switchThumb(this, 52f, 32f, if (checked) 24f else 16f, checked, if (checked) -1 else DEFAULT_OUTLINE, false) })),
                ),
                el(
                    "progress", "left:16px;top:84px;width:380px;height:4px;",
                    listOf(
                        el(
                            "progress.track", css { append("left:0;top:0;width:380px;height:4px;"); WidgetCss.linearTrack(this, 4f, withAlpha(primary, 0.24f)) },
                            listOf(el("progress.bar", css { WidgetCss.linearBar(this, progress, primary) })),
                        ),
                    ),
                ),
                el("spinner", "left:360px;top:70px;width:32px;height:32px;", listOf(el("spinner.ring", css { WidgetCss.circular(this, 32f, 32f, progress, primary, null) }))),
            ),
        )
        val field = el(
            "field", "left:16px;top:724px;width:380px;height:56px;box-shadow:inset 0 0 0 1px #79747e;border-radius:4px;",
            listOf(label("field.text", 16f, 16f, 348f, maskText("hunter2 secret", StringBuilder()).toString())),
        )
        val web = el("web", "left:16px;top:796px;width:380px;height:60px;", listOf(txt("web.t", "Web view")), cls = "pv")
        val button = el(
            "button", "left:16px;top:868px;width:380px;height:40px;",
            listOf(
                el("button.bg", css { append("left:0;top:0;width:380px;height:40px;").appendFill(primary).appendRadius(20f) }),
                label("button.text", 0f, 8f, 380f, "Continue", 14f, -1, 500, TextAlign.CENTER),
            ),
        )
        val decor = el("decor", "left:0;top:0;width:412px;height:915px;background-color:#fef7ff;")
        val children = arrayListOf(decor, bar, list, toggles, field, web, button)
        if (dialog) {
            children.add(el("dim", "left:0;top:0;width:412px;height:915px;background-color:rgba(0,0,0,0.6);"))
            children.add(
                el(
                    "dialog", css { append("left:40px;top:330px;width:332px;height:200px;").appendFill(0xFFFFFFFF.toInt()).appendRadius(28f).appendShadows(6f, 0xFF000000.toInt(), 0f, 0) },
                    listOf(
                        label("dialog.title", 24f, 24f, 284f, "Delete item?", 24f),
                        label("dialog.body", 24f, 70f, 284f, "This cannot be undone.", 14f, 0x99000000.toInt()),
                        label("dialog.ok", 200f, 150f, 108f, "DELETE", 14f, primary, 500, TextAlign.RIGHT),
                        el("dialog.blank", "left:24px;top:100px;width:120px;height:24px;background:#000000;", cls = "mk"),
                    ),
                ),
            )
        }
        return VNode.element(screenNodeId, "div", mapOf("id" to "tw", "style" to "width:412px;height:915px"), children)
    }

    @Test
    fun `a sample screen replays and is written for the browser check`() {
        val scheduler = ManualScheduler()
        val source = FakeSource().apply {
            width = 412f
            height = 915f
        }
        val recorder = WireframeRecorder(150, 10, source, ManualMainThread(scheduler), scheduler::now)
        recorder.screenName = { "SampleActivity" }
        val start = scheduler.now()
        val checkpoints = LinkedHashMap<String, Long>()
        source.screen = screen(0f, checked = false, progress = 0.2f, dialog = false)
        recorder.start()
        scheduler.runDue()
        checkpoints["a-initial"] = scheduler.now()
        for (i in 1..20) {
            scheduler.advance(100)
            source.screen = screen(i * 15f, checked = i > 10, progress = 0.2f + i * 0.03f, dialog = false)
            source.draw()
            if (i == 6) {
                recorder.pointerDown(1, 200f, 400f, touch = true)
            } else if (i in 7..12) {
                recorder.pointerMove(1, 200f, 400f - (i - 6) * 20, touch = true)
            } else if (i == 13) {
                recorder.pointerUp(1, 200f, 280f, touch = true, tap = false)
            }
        }
        scheduler.advance(200)
        checkpoints["b-scrolled"] = scheduler.now()
        recorder.pointerDown(2, 380f, 640f, touch = true)
        recorder.pointerUp(2, 380f, 640f, touch = true, tap = true)
        scheduler.advance(300)
        source.screen = screen(300f, checked = true, progress = 0.9f, dialog = true)
        source.draw()
        scheduler.advance(400)
        checkpoints["c-dialog"] = scheduler.now()
        scheduler.advance(600)
        val clip = recorder.captureRecording("sample", start, scheduler.now())!!

        val mirror = Mirror()
        mirror.play(clip.events)
        assertTrue(mirror.texts().contains("Delete item?"))
        assertTrue(mirror.texts().contains("List item 13"))
        assertTrue(mirror.texts().none { it.contains("hunter2") })
        val mutations = clip.events.count { it["type"] == 3 && (it["data"] as Map<*, *>)["source"] == 0 }
        assertEquals(21, mutations)
        assertTrue("clip is ${clip.approxBytes} bytes", clip.approxBytes!! < 60_000)

        val dir = File("build/wireframe-sample")
        dir.mkdirs()
        File(dir, "events.json").writeText(Json.stringify(clip.events))
        File(dir, "checkpoints.json").writeText(Json.stringify(checkpoints))
    }
}
