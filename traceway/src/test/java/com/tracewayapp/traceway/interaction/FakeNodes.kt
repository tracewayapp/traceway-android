package com.tracewayapp.traceway.interaction

/**
 * A view or Compose node for JVM tests: what [ViewPath]'s adapters read
 * from real views, set directly. [path] snapshots it the way a tap on it
 * would.
 */
internal class FakeNode(
    private val name: String,
    private val supers: List<String> = emptyList(),
    override val ids: List<String> = emptyList(),
    override val roles: Set<String> = emptySet(),
    override val stringKey: String? = null,
    override val ownLabel: String? = null,
    override val ownText: String? = null,
    override val hidden: Boolean = false,
    override val masked: Boolean = false,
    override val handlesTaps: Boolean = false,
    override val showsContent: Boolean = ownText != null,
    override val boundary: Boolean = false,
    private val generic: Boolean = false,
    kids: List<FakeNode> = emptyList(),
) : SourceNode() {
    var parent: FakeNode? = null
        private set

    private val kids: List<FakeNode> = kids.onEach { it.parent = this }

    override val key: Any get() = this

    override fun identity(): Any = this

    override val typeFacts: TypeFacts
        get() = TypeFacts(
            type = name,
            tagNames = (listOf(name) + supers).mapTo(LinkedHashSet()) { it.lowercase() },
            typeNames = listOf("com.example.$name", name),
            generic = generic,
        )

    override fun loadChildren(): List<SourceNode> = kids

    /** This node and its ancestors, as a tap on it sees them. */
    fun path(): UiElementPath = UiSnapshot.build(generateSequence(this) { it.parent }.toList())
}

private fun idList(id: String?) = listOfNotNull(id)

/** An activity window: the decor, its content root, and [content] in it. */
internal fun window(vararg content: FakeNode) = FakeNode(
    "DecorView",
    supers = listOf("FrameLayout", "ViewGroup", "View"),
    kids = listOf(
        FakeNode(
            "FrameLayout",
            supers = listOf("ViewGroup", "View"),
            ids = listOf("content"),
            boundary = true,
            kids = content.toList(),
        ),
    ),
)

internal fun linear(vararg kids: FakeNode, id: String? = null, clickable: Boolean = false, masked: Boolean = false) =
    FakeNode(
        "LinearLayout",
        supers = listOf("ViewGroup", "View"),
        ids = idList(id),
        handlesTaps = clickable,
        masked = masked,
        kids = kids.toList(),
    )

internal fun frame(vararg kids: FakeNode, id: String? = null, clickable: Boolean = false, masked: Boolean = false, name: String = "FrameLayout") =
    FakeNode(
        name,
        supers = listOf("FrameLayout", "ViewGroup", "View"),
        ids = idList(id),
        handlesTaps = clickable,
        masked = masked,
        kids = kids.toList(),
    )

internal fun materialButton(text: String, id: String? = null, tag: String? = null) = FakeNode(
    "MaterialButton",
    supers = listOf("AppCompatButton", "Button", "TextView", "View"),
    ids = idList(id),
    roles = setOf("button"),
    stringKey = tag,
    ownText = text,
    handlesTaps = true,
)

internal fun textView(text: String?, id: String? = null, clickable: Boolean = false, hidden: Boolean = false, masked: Boolean = false) =
    FakeNode(
        "MaterialTextView",
        supers = listOf("AppCompatTextView", "TextView", "View"),
        ids = idList(id),
        ownText = text,
        handlesTaps = clickable,
        hidden = hidden,
        masked = masked,
    )

internal fun imageButton(label: String?, id: String? = null) = FakeNode(
    "AppCompatImageButton",
    supers = listOf("ImageButton", "ImageView", "View"),
    ids = idList(id),
    roles = setOf("button"),
    ownLabel = label,
    handlesTaps = true,
    showsContent = true,
)

internal fun imageView(label: String?, masked: Boolean = false) = FakeNode(
    "AppCompatImageView",
    supers = listOf("ImageView", "View"),
    roles = setOf("image"),
    ownLabel = label,
    showsContent = true,
    masked = masked,
)

/** An input: its own text is its hint; what was typed never reaches a snapshot. */
internal fun editText(hint: String?, id: String? = null) = FakeNode(
    "TextInputEditText",
    supers = listOf("AppCompatEditText", "EditText", "TextView", "View"),
    ids = idList(id),
    roles = setOf("textField"),
    ownText = hint,
    handlesTaps = true,
)

internal fun composeView(vararg kids: FakeNode) = FakeNode(
    "AndroidComposeView",
    supers = listOf("ViewGroup", "View"),
    kids = kids.toList(),
)

/** A Compose node with `Role.Button` and a click action. */
internal fun composeButton(vararg kids: FakeNode, testTag: String? = null) = FakeNode(
    "Button",
    ids = idList(testTag),
    roles = setOf("button"),
    handlesTaps = true,
    kids = kids.toList(),
)

internal fun composeText(text: String) = FakeNode("Text", ownText = text)

/** A Compose node without a role or text: a test tag, a click action, a description. */
internal fun composeNode(
    vararg kids: FakeNode,
    testTag: String? = null,
    clickable: Boolean = false,
    label: String? = null,
    roles: Set<String> = emptySet(),
    masked: Boolean = false,
) = FakeNode(
    "Composable",
    ids = idList(testTag),
    roles = roles,
    ownLabel = label,
    handlesTaps = clickable,
    masked = masked,
    generic = true,
    kids = kids.toList(),
)

internal fun sel(selector: String, path: UiElementPath): Boolean = ViewSelector.parse(selector)!!.matches(path)
