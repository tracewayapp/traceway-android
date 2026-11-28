package com.tracewayapp.traceway.interaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports `test/interaction_test.dart` of the Flutter SDK over synthetic view trees. */
class SelectorSyntaxTest {
    @Test
    fun acceptsWhatTheDashboardAccepts() {
        for (valid in listOf(
            "ElevatedButton",
            "#checkout",
            ".button",
            "[key=\"42\"]",
            "[text='Pay now']",
            "[label]",
            "ListTile[text^=\"Set\"]",
            "#cart ElevatedButton",
            "Row > Text",
            "Text + Text",
            "Text ~ Text",
            "*[type=\"_PrivateCard\"]",
            "[data-step=\"2\"]",
            "a#b.c[d=e][f~=\"g\"][h|=\"i\"][j\$=\"k\"][l*=\"m\"]",
            "MaterialButton#pay",
            "#toolbar_actions   >   ImageButton",
            "  .textField  ",
            "[ text = \"Pay\" ]",
            "#--x",
            "#-a",
        )) {
            assertNotNull(valid, ViewSelector.parse(valid))
        }
    }

    @Test
    fun rejectsPseudoClassesListsEscapesAndBrokenSyntax() {
        for (invalid in listOf(
            "Text:hover",
            "a, b",
            "#a\\:b",
            "[text=\"unterminated]",
            "[text=2]",
            ">",
            "a >",
            "",
            "   ",
            "#",
            "a\"b",
            "[text=\"a\nb\"]",
            "x".repeat(257),
            "#1abc",
            "#-",
            "[text!=\"a\"]",
            "a >> b",
            "[text=\"a\"",
            "1Button",
        )) {
            assertNull(invalid, ViewSelector.parse(invalid))
        }
    }

    @Test
    fun parsesAreCached() {
        assertTrue(ViewSelector.parse("#pay") === ViewSelector.parse("#pay"))
        assertNull(ViewSelector.parse("a:b"))
        assertNull(ViewSelector.parse("a:b"))
    }
}

class SelectorMatchingTest {
    @Test
    fun aButtonMatchesByTypeIdTextRoleAndAncestor() {
        val pay = materialButton("Pay now", id = "pay")
        window(linear(pay, id = "cart"))
        val path = pay.path()
        assertTrue(sel("MaterialButton", path))
        assertTrue(sel("materialbutton", path))
        assertTrue("a superclass names it too", sel("Button", path))
        assertTrue(sel("TextView", path))
        assertTrue(sel("#pay", path))
        assertTrue(sel("MaterialButton#pay", path))
        assertTrue(sel("MaterialButton[text=\"Pay now\"]", path))
        assertTrue(sel("[text^=\"Pay\"]", path))
        assertTrue(sel("[text~=\"now\"]", path))
        assertTrue(sel("[text\$=\"now\"]", path))
        assertTrue(sel("[text*=\"y n\"]", path))
        assertTrue(sel("[text|=\"Pay now\"]", path))
        assertTrue(sel(".button[text=\"Pay now\"]", path))
        assertTrue(sel("#cart MaterialButton", path))
        assertTrue(sel("#cart #pay", path))
        assertTrue(sel("#cart > #pay", path))
        assertTrue(sel("LinearLayout > MaterialButton", path))
        assertTrue(sel("FrameLayout MaterialButton", path))
        assertTrue(sel("[id=\"pay\"]", path))
        assertTrue(sel("[type=\"com.example.MaterialButton\"]", path))
        assertTrue(sel("*", path))

        assertFalse(sel("ImageButton", path))
        assertFalse(sel("#other", path))
        assertFalse(sel("MaterialButton[text=\"Pay\"]", path))
        assertFalse(sel("MaterialButton #cart", path))
        assertFalse(sel("[text~=\"no\"]", path))
        assertFalse(sel("[text^=\"\"]", path))
        assertFalse("the content frame is the grandparent", sel("FrameLayout > MaterialButton", path))
        assertFalse(sel("[data-step=\"2\"]", path))
        assertFalse(sel(".link", path))
    }

    @Test
    fun aTapMatchesItsTargetOrAnAncestorNotADescendant() {
        val label = textView("Settings")
        val row = linear(label, id = "row", clickable = true)
        window(row)
        assertTrue(sel("#row", label.path()))
        assertTrue(sel("#row", row.path()))
        assertFalse("a tap on the row is not on its text", sel("MaterialTextView", row.path()))
    }

    @Test
    fun composeNodesMatchByRoleTestTagAndText() {
        val label = composeText("Pay now")
        val button = composeButton(label, testTag = "pay")
        window(composeView(composeNode(button, testTag = "cart")))
        val path = label.path()
        assertTrue(sel("Button", path))
        assertTrue(sel("#pay", path))
        assertTrue(sel("Button#pay", path))
        assertTrue(sel("Button[text=\"Pay now\"]", path))
        assertTrue(sel("Button > Text", path))
        assertTrue(sel("#cart Button Text", path))
        assertTrue(sel(".button[text=\"Pay now\"]", path))
        assertTrue(sel("Text[text=\"Pay now\"]", path))
        assertTrue(sel("AndroidComposeView #pay", path))
        assertTrue(sel("[identifier=\"pay\"]", path))
        assertFalse(sel("Button > #cart", path))
        assertFalse(sel("#cart > Text", path))
    }

    @Test
    fun idsLabelsAndTooltips() {
        val ann = textView("Ann")
        val user = frame(ann, id = "open_user", clickable = true)
        val delete = imageButton("Delete")
        val star = imageView("Favorite")
        window(linear(user, delete, star))

        val userPath = ann.path()
        assertTrue(sel("#open_user", userPath))
        assertTrue(sel("[identifier=\"open_user\"]", userPath))
        assertTrue(sel("[key=\"open_user\"]", userPath))

        val deletePath = delete.path()
        assertTrue(sel("ImageButton[label=\"Delete\"]", deletePath))
        assertTrue(sel("AppCompatImageButton[label=\"Delete\"]", deletePath))
        assertTrue(sel("[label=\"Delete\"]", deletePath))
        assertFalse(sel("ImageButton[label=\"Remove\"]", deletePath))

        assertTrue(sel("ImageView[label=\"Favorite\"]", star.path()))
        assertTrue(sel(".image", star.path()))
    }

    @Test
    fun aTestTagIsAnIdAndAStringTagIsAKey() {
        val user = composeNode(composeText("Ann"), testTag = "open-user", clickable = true)
        window(composeView(user))
        val path = user.path()
        assertTrue(sel("#open-user", path))
        assertTrue(sel("[identifier=\"open-user\"]", path))

        val row = materialButton("Open", tag = "order.42")
        window(row)
        assertTrue(sel("[key=\"order.42\"]", row.path()))
        assertTrue(sel("[identifier=\"order.42\"]", row.path()))
        assertFalse(sel("#order", row.path()))
    }

    @Test
    fun aLabelIsFoundBelowAlongSingleChildrenOnly() {
        val icon = imageView("Call")
        val single = frame(frame(icon), clickable = true)
        val text = textView("Ann")
        val icon2 = imageView("Mail")
        val branching = frame(icon2, text, clickable = true)
        window(linear(single, branching))
        assertTrue(sel("FrameLayout[label=\"Call\"] > FrameLayout > ImageView", icon.path()))
        assertFalse(sel("FrameLayout[label=\"Mail\"]", icon2.path()))
        assertTrue(sel("FrameLayout[text=\"Ann\"]", icon2.path()))
    }

    @Test
    fun aMaskBelowStopsTheLabel() {
        val icon = imageView("Call Ann Smith", masked = true)
        val button = frame(icon, clickable = true)
        window(button)
        assertFalse(sel("FrameLayout[label]", icon.path()))
        assertTrue("the masked view's own label still matches", sel("ImageView[label]", icon.path()))
    }

    @Test
    fun siblingCombinatorsFollowTheOrderOfChildren() {
        val first = textView("first", id = "first")
        val second = textView("second", id = "second")
        val third = textView("third", id = "third")
        window(linear(first, second, third))
        val path = third.path()
        assertTrue(sel("#second + #third", path))
        assertTrue(sel("#first ~ #third", path))
        assertTrue(sel("#first + #second + #third", path))
        assertTrue(sel("LinearLayout > #second + #third", path))
        assertTrue(sel("[text=\"first\"] ~ MaterialTextView", path))
        assertFalse(sel("#first + #third", path))
        assertFalse(sel("#third ~ #first", path))
        assertFalse(sel("#third + #third", path))
        assertFalse("siblings come before", sel("#third ~ #second", second.path()))
        assertFalse("the first child has none", sel("* + #first", first.path()))
    }

    @Test
    fun siblingsOfAncestorsWork() {
        val title = textView("Title", id = "title")
        val button = materialButton("Go")
        val body = frame(button, id = "body")
        window(linear(title, body))
        val path = button.path()
        assertTrue(sel("#title + #body MaterialButton", path))
        assertTrue(sel("#title ~ #body > MaterialButton", path))
        assertFalse(sel("#title + MaterialButton", path))
    }

    @Test
    fun textIsJoinedFromAtMostThreeShownViews() {
        val row = linear(
            textView("One"),
            textView("gone", hidden = true),
            textView("  Two \n lines "),
            frame(textView("Three"), textView("Four")),
            clickable = true,
        )
        window(row)
        val path = row.path()
        assertEquals("One Two lines Three", path[0].text)
        assertTrue(sel("LinearLayout[text=\"One Two lines Three\"]", path))
        assertFalse(sel("[text*=\"gone\"]", path))
        assertFalse(sel("[text*=\"Four\"]", path))
    }

    @Test
    fun inputsShowTheirHintNeverWhatWasTyped() {
        val field = editText("Email", id = "email")
        val layout = FakeNode(
            "TextInputLayout",
            supers = listOf("LinearLayout", "ViewGroup", "View"),
            ownText = "Password",
            kids = listOf(frame(editText(null))),
        )
        window(linear(field, layout))
        assertTrue(sel("EditText[text=\"Email\"]", field.path()))
        assertTrue(sel("#email.textField", field.path()))
        assertTrue(sel("TextInputLayout[text*=\"Password\"]", layout.path()))

        // A Compose text field: no text of its own, its label below it.
        val textField = FakeNode("TextField", roles = setOf("textField"), handlesTaps = true, kids = listOf(composeText("Password")))
        window(composeView(textField))
        assertTrue(sel("TextField[text*=\"Password\"]", textField.path()))
    }

    @Test
    fun maskedTextStillMatchesOnTheDevice() {
        val card = textView("4242 4242 4242 4242")
        val button = frame(card, clickable = true, masked = true)
        window(button)
        val path = card.path()
        assertTrue(sel("FrameLayout[text^=\"4242\"]", path))
        assertNull("but is never reported", describeTap(path)!!.text)
        assertTrue(path.isMasked(0))
        assertTrue(path.isMasked(1))
        assertFalse(path.isMasked(2))
    }

    @Test
    fun aMaskInsideKeepsItsTextOutOfTheReportedText() {
        val row = linear(textView("Ann Smith", masked = true), textView("Call"), clickable = true)
        window(row)
        val element = row.path()[0]
        assertEquals("Ann Smith Call", element.text)
        assertEquals("Call", element.reportedText)
    }

    @Test
    fun hoverMatchesOnlyElementsTheMouseEntered() {
        val a = materialButton("A", id = "a")
        val b = materialButton("B", id = "b")
        window(linear(a, b, id = "toolbar"))
        val first = a.path()
        val second = b.path()
        assertTrue(first.matchesEntering("#toolbar", null))
        assertTrue(first.matchesEntering("#a", null))
        assertTrue(second.matchesEntering("#b", first))
        assertTrue(second.matchesEntering("#toolbar #b", first))
        assertFalse("the mouse was already in the toolbar", second.matchesEntering("#toolbar", first))
        assertFalse(second.matchesEntering("#a", first))
        assertFalse(second.matchesEntering("not:valid", first))
    }

    @Test
    fun hoverComparesSnapshotsOfTheSameViews() {
        val a = materialButton("A", id = "a")
        window(linear(a, id = "toolbar"))
        assertFalse(a.path().matchesEntering("#a", a.path()))
    }

    @Test
    fun manyDescendantCombinatorsOnADeepTreeStayBounded() {
        var node = textView("leaf")
        val leaf = node
        repeat(400) { node = frame(node) }
        window(node)
        val path = leaf.path()
        val start = System.nanoTime()
        assertFalse(sel(List(18) { "FrameLayout" }.joinToString(" ") + " #missing", path))
        // Each FrameLayout matches hundreds of ancestors; only the budget ends the search.
        assertFalse(sel("ImageView " + "FrameLayout ".repeat(18) + "MaterialTextView", path))
        assertTrue(sel("FrameLayout ".repeat(18) + "MaterialTextView", path))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2000)
    }

    @Test
    fun theElementPathInterfaceRunsSelectors() {
        val pay = materialButton("Pay", id = "pay")
        window(pay)
        val path: ElementPath = pay.path()
        assertTrue(path.matches("#pay"))
        assertFalse(path.matches("#pay:hover"))
        assertFalse(path.matches(""))
    }
}

class TypeNamesTest {
    open class Base

    class PayButton : Base()

    @Suppress("ClassName")
    class ab : PayButton2()

    open class PayButton2 : Base()

    @Test
    fun namesNestedClassesBySimpleName() {
        val facts = TypeNames.of(PayButton::class.java)
        assertEquals("PayButton", facts.type)
        assertTrue("paybutton" in facts.tagNames)
        assertTrue("base" in facts.tagNames)
        assertTrue(PayButton::class.java.name in facts.typeNames)
        assertTrue("PayButton" in facts.typeNames)
    }

    @Test
    fun anonymousClassesAreNamedByTheirSuperclass() {
        val anonymous = object : Base() {}
        val facts = TypeNames.of(anonymous.javaClass)
        assertEquals("Base", facts.type)
        assertTrue(anonymous.javaClass.name in facts.typeNames)
    }

    @Test
    fun minifiedNamesAreNamedByTheNearestKeptSuperclass() {
        val facts = TypeNames.of(ab::class.java)
        assertEquals("PayButton2", facts.type)
        assertFalse("ab" in facts.tagNames)
        assertTrue(ab::class.java.name in facts.typeNames)
    }
}

class DescribeTapTest {
    @Test
    fun namesTheControlNotTheTextInsideIt() {
        val pay = materialButton("Pay now")
        window(pay)
        val target = describeTap(pay.path())!!
        assertEquals("MaterialButton", target.tag)
        assertEquals("MaterialButton[text=\"Pay now\"]", target.selector)
        assertEquals("Pay now", target.text)
        assertNull(target.id)

        val text = textView("Pay now")
        window(linear(text, clickable = true))
        val row = describeTap(text.path())!!
        assertEquals("LinearLayout", row.tag)
        assertEquals("LinearLayout[text=\"Pay now\"]", row.selector)
    }

    @Test
    fun anIdOnTheControlComesFirst() {
        val pay = materialButton("Pay now", id = "pay")
        window(linear(pay, materialButton("Cancel"), id = "cart"))
        val path = pay.path()
        val target = describeTap(path)!!
        assertEquals("MaterialButton#pay", target.selector)
        assertEquals("pay", target.id)
        assertTrue(sel(target.selector, path))
    }

    @Test
    fun prefersAnIdOnTheAppViewAroundTheControl() {
        val pay = materialButton("Pay now")
        val checkout = frame(pay, id = "checkout", name = "CheckoutButton")
        window(linear(checkout, textView("Total")))
        val path = pay.path()
        val target = describeTap(path)!!
        assertEquals("CheckoutButton#checkout", target.selector)
        assertEquals("checkout", target.id)
        assertEquals("MaterialButton", target.tag)
        assertTrue(sel(target.selector, path))
    }

    @Test
    fun aContainerWithOtherChildrenDoesNotNameTheControl() {
        val pay = materialButton("Pay")
        window(FakeNode("ConstraintLayout", ids = listOf("root"), kids = listOf(pay, textView("Total"))))
        val target = describeTap(pay.path())!!
        assertEquals("MaterialButton[text=\"Pay\"]", target.selector)
        assertNull(target.id)
    }

    @Test
    fun anIdInsideTheControlNamesIt() {
        val title = textView("Settings", id = "title")
        window(linear(title, textView("On"), clickable = true))
        val target = describeTap(title.path())!!
        assertEquals("MaterialTextView#title", target.selector)
        assertEquals("LinearLayout", target.tag)
        assertEquals("title", target.id)
    }

    @Test
    fun anIconButtonIsNamedByItsLabel() {
        val delete = imageButton("Delete")
        window(delete)
        assertEquals("AppCompatImageButton[label=\"Delete\"]", describeTap(delete.path())!!.selector)
    }

    @Test
    fun anIconButtonInsideAClickableRowIsTheIconButton() {
        val more = imageButton("More")
        val title = textView("Settings")
        window(linear(title, more, clickable = true))
        assertEquals("AppCompatImageButton", describeTap(more.path())!!.tag)
        val row = describeTap(title.path())!!
        assertEquals("LinearLayout[text=\"Settings\"]", row.selector)
    }

    @Test
    fun aClickableViewInsideAClickableRowIsNotTheRow() {
        val inner = textView("inner", clickable = true)
        window(linear(inner, textView("other"), clickable = true))
        val target = describeTap(inner.path())!!
        assertEquals("MaterialTextView", target.tag)
        assertEquals("MaterialTextView[text=\"inner\"]", target.selector)
    }

    @Test
    fun textUnsafeForTheDashboardBecomesAPrefix() {
        val yes = materialButton("Yes, continue")
        window(yes)
        val path = yes.path()
        val target = describeTap(path)!!
        assertEquals("MaterialButton[text^=\"Yes\"]", target.selector)
        assertTrue(sel(target.selector, path))
    }

    @Test
    fun longTextIsLeftOut() {
        val long = materialButton("x".repeat(41))
        window(long)
        assertEquals("MaterialButton", describeTap(long.path())!!.selector)
    }

    @Test
    fun maskedTextIsNotReported() {
        val card = materialButton("4242 4242 4242 4242")
        window(frame(card, masked = true))
        val target = describeTap(card.path())!!
        assertNull(target.text)
        assertEquals("MaterialButton", target.selector)
    }

    @Test
    fun aMaskedControlKeepsItsLabelToo() {
        val call = imageButton("Call Ann Smith")
        window(frame(call, masked = true))
        val target = describeTap(call.path())!!
        assertNull(target.label)
        assertEquals("AppCompatImageButton", target.selector)
    }

    @Test
    fun aMaskInsideAControlHidesItsLabel() {
        val callIcon = imageView("Call Ann Smith", masked = true)
        val mailIcon = imageView("Mail Ann Smith", masked = true)
        window(linear(frame(callIcon, clickable = true), composeView(composeNode(mailIcon, clickable = true))))
        for (icon in listOf(callIcon, mailIcon)) {
            val target = describeTap(icon.path())!!
            assertNull(target.label)
            assertFalse(target.selector, target.selector.contains("Ann"))
        }
    }

    @Test
    fun aMaskInsideAControlHidesOnlyItsText() {
        val row = linear(textView("Ann Smith", masked = true), textView("Call"), clickable = true)
        window(row)
        val target = describeTap(row.path())!!
        assertEquals("Call", target.text)
        assertFalse(target.selector.contains("Ann"))
    }

    @Test
    fun aTapOnPlainTextNamesTheText() {
        val hello = textView("Hello there")
        window(linear(hello, textView("other")))
        val target = describeTap(hello.path())!!
        assertEquals("MaterialTextView", target.tag)
        assertEquals("MaterialTextView[text=\"Hello there\"]", target.selector)
    }

    @Test
    fun aTagThatIsNotANameBecomesAKeyAttribute() {
        val open = materialButton("Open", tag = "order.42")
        window(open)
        val path = open.path()
        val target = describeTap(path)!!
        assertEquals("MaterialButton[key=\"order.42\"]", target.selector)
        assertTrue(sel(target.selector, path))
    }

    @Test
    fun aTestTagThatIsNotANameBecomesAnIdentifier() {
        val open = composeButton(composeText("Open"), testTag = "order.42")
        window(composeView(open))
        val target = describeTap(open.path())!!
        assertEquals("Button[identifier=\"order.42\"]", target.selector)
        assertEquals("order.42", target.id)
    }

    @Test
    fun composeButtonsAreNamedByRoleAndText() {
        val label = composeText("Pay now")
        window(composeView(composeButton(label)))
        val target = describeTap(label.path())!!
        assertEquals("Button", target.tag)
        assertEquals("Button[text=\"Pay now\"]", target.selector)
    }

    @Test
    fun genericComposeNodesLeaveTheirTypeOut() {
        val tagged = composeNode(composeText("Checkout"), testTag = "checkout", clickable = true)
        window(composeView(tagged))
        assertEquals("#checkout", describeTap(tagged.path())!!.selector)

        val text = composeText("Settings")
        window(composeView(composeNode(text, clickable = true)))
        assertEquals("[text=\"Settings\"]", describeTap(text.path())!!.selector)

        val icon = composeNode(clickable = true, roles = setOf("selected"))
        window(composeView(icon, composeText("x")))
        assertEquals(".selected", describeTap(icon.path())!!.selector)
    }

    @Test
    fun aTypeAloneTakesTheNearestAncestorIdAsContext() {
        val button = imageButton(null)
        window(linear(button, imageButton(null), id = "toolbar_actions"))
        val path = button.path()
        val target = describeTap(path)!!
        assertEquals("#toolbar_actions AppCompatImageButton", target.selector)
        assertTrue(sel(target.selector, path))
    }

    @Test
    fun theWindowDecorIsNotContext() {
        val button = imageButton(null)
        window(linear(button, imageButton(null)))
        assertEquals("AppCompatImageButton", describeTap(button.path())!!.selector)
    }

    @Test
    fun aTypeThatIsNotATagIsQuoted() {
        val card = FakeNode("_PrivateCard", handlesTaps = true, kids = listOf(textView("card")))
        window(linear(card, textView("x")))
        val target = describeTap(card.path())!!
        assertEquals("_PrivateCard", target.tag)
        assertEquals("[type=\"_PrivateCard\"][text=\"card\"]", target.selector)
        assertTrue(sel(target.selector, card.path()))
    }

    @Test
    fun withoutTextTheTextIsLeftOut() {
        val pay = materialButton("Pay now")
        window(pay)
        val target = describeTap(pay.path(), includeText = false)!!
        assertNull(target.text)
        assertEquals("MaterialButton", target.selector)
    }

    @Test
    fun anEmptyPathHasNoTarget() {
        assertNull(describeTap(UiElementPath(emptyList())))
    }
}
