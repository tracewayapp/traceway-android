package com.tracewayapp.traceway.wireframe

/**
 * One node of the wireframe DOM: an element or a text node.
 *
 * Nodes are immutable. A capture reuses the previous frame's node when
 * nothing about it changed, so consecutive frames share their unchanged
 * subtrees: a frame costs memory only for what moved, and a diff skips
 * every subtree that is the same object in both frames.
 */
internal class VNode private constructor(
    /** The rrweb node id. The same view keeps its id from frame to frame. */
    val id: Int,
    /** Null for a text node. */
    val tag: String?,
    val text: String?,
    val attributes: Map<String, String>,
    val children: List<VNode>,
) {
    val isText: Boolean get() = tag == null

    /**
     * Whether this node shows the same as [other]: same tag, text and
     * attributes, and children that are the very same objects.
     */
    fun sameAs(other: VNode): Boolean {
        if (id != other.id || tag != other.tag || text != other.text) return false
        if (attributes.size != other.attributes.size) return false
        for ((key, value) in attributes) {
            if (other.attributes[key] != value) return false
        }
        if (children.size != other.children.size) return false
        for (i in children.indices) {
            if (children[i] !== other.children[i]) return false
        }
        return true
    }

    /**
     * The node in rrweb's serialized form. A full snapshot nests the whole
     * subtree; a mutation adds every node on its own, so it leaves the
     * children out.
     */
    fun serialize(withChildren: Boolean = true): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>(8)
        if (isText) {
            out["type"] = 3
            out["textContent"] = text
            out["id"] = id
            return out
        }
        out["type"] = 2
        out["tagName"] = tag
        out["attributes"] = attributes
        out["childNodes"] = if (withChildren) children.map { it.serialize() } else emptyList<Any?>()
        out["id"] = id
        return out
    }

    override fun toString(): String = if (isText) "#$id\"$text\"" else "#$id<$tag $attributes>$children"

    companion object {
        fun element(id: Int, tag: String, attributes: Map<String, String>, children: List<VNode>): VNode =
            VNode(id, tag, null, attributes, children)

        fun text(id: Int, text: String): VNode = VNode(id, null, text, emptyMap(), emptyList())
    }
}

/** One captured screen: the wireframe DOM of what the app's windows drew. */
internal class WireframeFrame(
    /**
     * The element holding the screen ([screenNodeId]); null while nothing is
     * on screen (the app is in the background, the recorder stopped).
     */
    val screen: VNode?,
    /** Size of the screen in dp. */
    val width: Float,
    val height: Float,
    val timestampMs: Long,
    /** The screen on top when the frame was taken, the replay's address. */
    val href: String? = null,
) {
    fun sameSize(other: WireframeFrame): Boolean = width == other.width && height == other.height
}

/** Ids of the document skeleton around the captured screen. */
internal const val documentNodeId = 1
private const val doctypeNodeId = 2
private const val htmlNodeId = 3
private const val headNodeId = 4
private const val charsetNodeId = 5
private const val styleNodeId = 6
private const val bodyNodeId = 7

/** The element that holds the screen; every captured node is inside it. */
internal const val screenNodeId = 8

/**
 * Fixed ids of nodes the recorder draws itself (below the first captured
 * id), the same on every platform: the screen shown while the app is in the
 * background, and the keyboard box.
 */
internal const val awayScreenTextBoxId = 9
internal const val awayScreenTextId = 10
internal const val keyboardNodeId = 12
internal const val keyboardLabelBoxId = 13
internal const val keyboardLabelTextId = 14

/** The first id handed to a captured node. */
internal const val firstCapturedNodeId = 16

/** The rrweb document of a full snapshot, with [screen] inside its body. */
internal fun serializeDocument(screen: VNode): Map<String, Any?> = linkedMapOf(
    "type" to 0,
    "id" to documentNodeId,
    "childNodes" to listOf(
        linkedMapOf(
            "type" to 1,
            "name" to "html",
            "publicId" to "",
            "systemId" to "",
            "id" to doctypeNodeId,
        ),
        linkedMapOf(
            "type" to 2,
            "tagName" to "html",
            "attributes" to emptyMap<String, String>(),
            "id" to htmlNodeId,
            "childNodes" to listOf(
                linkedMapOf(
                    "type" to 2,
                    "tagName" to "head",
                    "attributes" to emptyMap<String, String>(),
                    "id" to headNodeId,
                    "childNodes" to listOf(
                        linkedMapOf(
                            "type" to 2,
                            "tagName" to "meta",
                            "attributes" to mapOf("charset" to "utf-8"),
                            "childNodes" to emptyList<Any?>(),
                            "id" to charsetNodeId,
                        ),
                        linkedMapOf(
                            "type" to 2,
                            "tagName" to "style",
                            "attributes" to mapOf("_cssText" to wireframeCss),
                            "childNodes" to emptyList<Any?>(),
                            "id" to styleNodeId,
                        ),
                    ),
                ),
                linkedMapOf(
                    "type" to 2,
                    "tagName" to "body",
                    "attributes" to emptyMap<String, String>(),
                    "id" to bodyNodeId,
                    "childNodes" to listOf(screen.serialize()),
                ),
            ),
        ),
    ),
)
