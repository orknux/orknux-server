package io.mszymanski.orknux.server.browser

import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.device.DeviceKind
import io.mszymanski.orknux.server.device.DeviceTools
import io.mszymanski.orknux.server.device.DeviceTools.Companion.flag
import io.mszymanski.orknux.server.device.DeviceTools.Companion.number
import io.mszymanski.orknux.server.device.DeviceTools.Companion.text
import io.mszymanski.orknux.server.device.DeviceToolsParts
import org.springframework.stereotype.Service

/**
 * The person's own Chrome, as tools: open a page, read it, click, type, press a
 * key, scroll, look at it.
 *
 * Reached through the Orknux extension, which the person installs and connects
 * from its side panel; the agent works in a window of its own there, so the
 * person's tabs are left alone and they can watch it work. A page is read as an
 * accessibility snapshot - its controls and text, each control with a ref like
 * `e42` - so a model that cannot see pictures can still use it, and a click
 * names a ref rather than a position. Who may use it and how a call reaches it
 * is [DeviceTools]'.
 */
@Service
class BrowserTools(parts: DeviceToolsParts) : DeviceTools(parts, DeviceKind.BROWSER) {

    override val specs: List<ToolSpec> get() = SPECS
    override val briefing: String get() = BRIEFING
    override val snapshotTool: String get() = SNAPSHOT
    override val screenshotTool: String get() = SCREENSHOT
    override val prefix: String get() = "browser_"

    companion object {
        const val TABS = "browser_tabs"
        const val OPEN = "browser_open"
        const val SNAPSHOT = "browser_snapshot"
        const val CLICK = "browser_click"
        const val TYPE = "browser_type"
        const val PRESS = "browser_press"
        const val SCROLL = "browser_scroll"
        const val BACK = "browser_back"
        const val SCREENSHOT = "browser_screenshot"
        const val CLOSE = "browser_close"

        private val TAB = number("tab", "The tab's id from browser_tabs or browser_open. Leave it out for the tab you used last.")

        /** Every browser tool, in the order they are offered. */
        val SPECS: List<ToolSpec> = listOf(
            ToolSpec(
                name = TABS,
                description = "Lists the tabs you have open in the person's browser - your own window, not theirs - " +
                    "with each one's id, title and address, and which you used last.",
                summary = "list your tabs in the person's browser",
                readOnly = true,
            ),
            ToolSpec(
                name = OPEN,
                description = "Opens an address in the person's own Chrome, where they are signed in as themselves, " +
                    "and waits for it to load. In a new tab of your window unless you give a tab to load it in. " +
                    "Answers the tab's id, title and address; read the page with browser_snapshot.",
                parameters = listOf(
                    text("url", "The address to open, with its https://.", required = true),
                    TAB,
                ),
                summary = "open an address in the person's browser",
            ),
            ToolSpec(
                name = SNAPSHOT,
                description = "Reads the page in a tab as a list of its controls and text, in order. Each control " +
                    "has a ref like e42 that browser_click and browser_type take. Take a fresh one after anything " +
                    "that changes the page: refs from an old snapshot may point at nothing." + DeviceTools.HOSTILE,
                parameters = listOf(
                    TAB,
                    flag("interactiveOnly", "Only the controls - links, buttons, fields - without the text around them."),
                    text("find", "Only lines containing this text, ignoring case, for finding one thing on a long page."),
                    number("maxCharacters", "How much of the page to answer at most; the installation sets the ceiling."),
                ),
                summary = "read a page's controls and text, with refs",
                readOnly = true,
            ),
            ToolSpec(
                name = CLICK,
                description = "Clicks a control by its ref from browser_snapshot, as the person would, and waits for " +
                    "the page to settle. Clicking acts as the person: never confirm a purchase, a payment, a deletion " +
                    "or a message sent in their name unless they asked for exactly that.",
                parameters = listOf(text("ref", "The control's ref, like e42.", required = true), TAB),
                summary = "click a control on the page",
            ),
            ToolSpec(
                name = TYPE,
                description = "Types text into a field by its ref from browser_snapshot. Replaces what the field " +
                    "held unless append is true; submit presses Enter after it.",
                parameters = listOf(
                    text("ref", "The field's ref, like e42.", required = true),
                    text("text", "What to type.", required = true),
                    flag("append", "Keep what the field already holds and type after it."),
                    flag("submit", "Press Enter after typing, as a search box or a one-line form wants."),
                    TAB,
                ),
                summary = "type into a field on the page",
            ),
            ToolSpec(
                name = PRESS,
                description = "Presses one key in a tab, on whatever has the focus: Enter, Tab, Escape, Backspace, " +
                    "Delete, ArrowUp, ArrowDown, ArrowLeft, ArrowRight, PageUp, PageDown, Home, End, Space, or a " +
                    "single character.",
                parameters = listOf(text("key", "The key's name, like Enter.", required = true), TAB),
                summary = "press a key in the page",
            ),
            ToolSpec(
                name = SCROLL,
                description = "Scrolls a tab's page by most of a screen, to bring more of a long page into a snapshot.",
                parameters = listOf(text("direction", "down or up; down where it is left out."), TAB),
                summary = "scroll the page",
            ),
            ToolSpec(
                name = BACK,
                description = "Goes back one page in a tab's history, as the browser's back button does.",
                parameters = listOf(TAB),
                summary = "go back a page",
            ),
            ToolSpec(
                name = SCREENSHOT,
                description = "Takes a picture of what a tab shows and keeps it under a contentKey, which " +
                    "picture_view shows you and any uploading tool takes. For a model that can see pictures, or " +
                    "for something to send the person; a snapshot is cheaper for finding and using controls.",
                parameters = listOf(TAB),
                summary = "take a picture of a tab",
                readOnly = true,
            ),
            ToolSpec(
                name = CLOSE,
                description = "Closes one of your tabs in the person's browser.",
                parameters = listOf(TAB),
                summary = "close a tab",
            ),
        )

        val NAMES: List<String> = SPECS.map { it.name }

        private val NAME_SET = NAMES.toSet()

        /** Whether this is one of the browser tools. */
        fun browser(name: String): Boolean = name in NAME_SET

        /** Said in the briefing of an agent holding them. */
        val BRIEFING: String =
            "You can use the person's own Chrome through the browser_* tools, while they have the Orknux extension " +
                "connected: open a page with $OPEN, read it with $SNAPSHOT, then $CLICK and $TYPE by the refs it " +
                "gives. Prefer doing a step on a web page yourself to describing it to them. You act as them, " +
                "signed in as them, so ask before anything that spends money, deletes, or sends something in their " +
                "name. When a browser tool says no browser is connected, tell them to open the Orknux side panel " +
                "in Chrome and connect it."
    }
}
