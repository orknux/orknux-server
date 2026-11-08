package io.mszymanski.orknux.server.desktop

import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.device.DeviceKind
import io.mszymanski.orknux.server.device.DeviceTools
import io.mszymanski.orknux.server.device.DeviceTools.Companion.flag
import io.mszymanski.orknux.server.device.DeviceTools.Companion.number
import io.mszymanski.orknux.server.device.DeviceTools.Companion.text
import io.mszymanski.orknux.server.device.DeviceToolsParts
import org.springframework.stereotype.Service

/**
 * The person's own Windows desktop, as tools: list the windows, open a
 * program, read a window, click, type, press keys, scroll, look at it - as the
 * person would, with the mouse and the keyboard.
 *
 * Reached through Orknux for Windows, which the person runs and connects from
 * its window. A window is read through UI Automation, the interface Windows
 * gives screen readers: its controls and text, each control with a ref like
 * `e12`, so a model that cannot see pictures can still use it, and a click
 * names a control rather than a position. A position works too, for what UI
 * Automation cannot see.
 *
 * Unlike the browser there is no window of the agent's own: a desktop is one
 * screen, and the agent works on the person's. So the client says on screen
 * while it is driving, and a hotkey there stops it. Who may use it and how a
 * call reaches it is [DeviceTools]'.
 */
@Service
class DesktopTools(parts: DeviceToolsParts) : DeviceTools(parts, DeviceKind.DESKTOP) {

    override val specs: List<ToolSpec> get() = SPECS
    override val briefing: String get() = BRIEFING
    override val snapshotTool: String get() = SNAPSHOT
    override val screenshotTool: String get() = SCREENSHOT
    override val prefix: String get() = "desktop_"
    override val listTool: String get() = WINDOWS
    override val chooses: Boolean get() = true

    companion object {
        const val WINDOWS = "desktop_windows"
        const val OPEN = "desktop_open"
        const val FOCUS = "desktop_focus"
        const val SNAPSHOT = "desktop_snapshot"
        const val CLICK = "desktop_click"
        const val TYPE = "desktop_type"
        const val PRESS = "desktop_press"
        const val SCROLL = "desktop_scroll"
        const val SCREENSHOT = "desktop_screenshot"
        const val CLOSE = "desktop_close"

        private val WINDOW = number("window", "The window's id from desktop_windows or desktop_open. Leave it out for the window you used last.")

        private val BASE_SPECS: List<ToolSpec> = listOf(
            ToolSpec(
                name = WINDOWS,
                description = "Lists the windows open on the person's desktop, each with its id, title, " +
                    "program, and whether it has the focus or is minimised, and says which system the computer " +
                    "runs - Windows or Linux - so look here first. Where the person has more than one computer " +
                    "connected, it lists each one's windows under that computer's name.",
                summary = "list the windows on the person's computer",
                readOnly = true,
            ),
            ToolSpec(
                name = OPEN,
                description = "Starts a program on the person's computer, or opens a file, a folder or an address " +
                    "with the program the system uses for it, and waits for its window: on Windows \"notepad\", " +
                    "\"calc\" or \"C:\\\\Users\\\\me\\\\report.xlsx\", on Linux \"gedit\", \"gnome-calculator\" or " +
                    "\"/home/me/report.ods\", on either \"https://example.com\". A name that is not a program the " +
                    "system can start is looked up among the installed apps by the name their menu shows - the Start " +
                    "menu on Windows, the app menu on Linux - so \"musescore\" or a Store app works. An app already " +
                    "running is brought to the front rather than started again, unless new is set. Answers the id and " +
                    "title of the window that ended up in front; read it with desktop_snapshot.",
                parameters = listOf(
                    text("target", "A program's name or path, or a file, folder or address to open.", required = true),
                    text("arguments", "Command-line arguments, for a program."),
                    flag("new", "Start another copy even when the app is already running."),
                ),
                summary = "start a program or open a file",
            ),
            ToolSpec(
                name = FOCUS,
                description = "Brings a window to the front and gives it the focus, restoring it if it is minimised.",
                parameters = listOf(number("window", "The window's id from desktop_windows.", required = true)),
                summary = "bring a window to the front",
            ),
            ToolSpec(
                name = SNAPSHOT,
                description = "Reads a window as a list of its controls and text, in order, through the system's " +
                    "accessibility interface (UI Automation on Windows, AT-SPI on Linux). " +
                    "Each control has a ref like e12 that desktop_click and desktop_type take. Take a fresh one after " +
                    "anything that changes the window: refs from an old snapshot are refused." + DeviceTools.HOSTILE,
                parameters = listOf(
                    WINDOW,
                    flag("interactiveOnly", "Only the controls - buttons, fields, menus, list items - without the text around them."),
                    text("find", "Only lines containing this text, ignoring case, for finding one thing in a large window."),
                    number("maxCharacters", "How much of the window to answer at most; the installation sets the ceiling."),
                ),
                summary = "read a window's controls and text, with refs",
                readOnly = true,
            ),
            ToolSpec(
                name = CLICK,
                description = "Clicks a control by its ref from desktop_snapshot, or a point by x and y in screen " +
                    "pixels, with the mouse as the person would. Clicking acts as the person: never confirm a " +
                    "purchase, a payment, a deletion, an installation or a message sent in their name unless they " +
                    "asked for exactly that.",
                parameters = listOf(
                    text("ref", "The control's ref, like e12. Give this or x and y."),
                    number("x", "Screen x, in pixels, where there is no ref."),
                    number("y", "Screen y, in pixels, where there is no ref."),
                    text("button", "left, right or middle; left where it is left out."),
                    flag("double", "Double-click."),
                    WINDOW,
                ),
                summary = "click a control or a point",
            ),
            ToolSpec(
                name = TYPE,
                description = "Types text with the keyboard into a control by its ref, or into whatever has the " +
                    "focus. Replaces what a field held unless append is true; submit presses Enter after it.",
                parameters = listOf(
                    text("text", "What to type.", required = true),
                    text("ref", "The field's ref, like e12; leave it out to type where the focus is."),
                    flag("append", "Keep what the field already holds and type after it."),
                    flag("submit", "Press Enter after typing."),
                    WINDOW,
                ),
                summary = "type into a field",
            ),
            ToolSpec(
                name = PRESS,
                description = "Presses a key or a combination on whatever has the focus: Enter, Tab, Escape, " +
                    "Backspace, Delete, an arrow, PageUp, PageDown, Home, End, F1 to F12, or a combination such as " +
                    "Ctrl+S, Ctrl+Shift+N, Alt+F4 or Win+R (Win is the Super key on Linux).",
                parameters = listOf(text("keys", "The key or combination, like Ctrl+S.", required = true), WINDOW),
                summary = "press a key or a shortcut",
            ),
            ToolSpec(
                name = SCROLL,
                description = "Scrolls a control by its ref, or the window, with the mouse wheel, to bring more of " +
                    "a long list or page into a snapshot.",
                parameters = listOf(
                    text("direction", "down, up, left or right; down where it is left out."),
                    number("amount", "How many wheel notches; 5 where it is left out."),
                    text("ref", "The control to scroll, like e12; leave it out for the window."),
                    WINDOW,
                ),
                summary = "scroll a window or a list",
            ),
            ToolSpec(
                name = SCREENSHOT,
                description = "Takes a picture of a window, or of the whole screen, and keeps it under a contentKey, " +
                    "which picture_view shows you and any uploading tool takes. For a model that can see pictures, " +
                    "for what the accessibility interface cannot read, or for something to send the person; a snapshot is cheaper " +
                    "for finding and using controls.",
                parameters = listOf(WINDOW, flag("screen", "The whole screen rather than one window.")),
                summary = "take a picture of a window or the screen",
                readOnly = true,
            ),
            ToolSpec(
                name = CLOSE,
                description = "Closes a window, as its close button would. A program with unsaved work may ask " +
                    "whether to save; read the window again and answer as the person would want.",
                parameters = listOf(number("window", "The window's id.", required = true)),
                summary = "close a window",
            ),
        )

        private val ON_COMPUTER = text(
            DeviceTools.COMPUTER,
            "Which of the person's computers, by the name desktop_windows gives it, or its system (Windows, Linux). " +
                "Leave it out for the one this conversation used last.",
        )

        /** Every desktop tool takes the computer it is for; ids and refs belong to that computer. */
        val SPECS: List<ToolSpec> = BASE_SPECS.map { it.copy(parameters = it.parameters + ON_COMPUTER) }

        val NAMES: List<String> = SPECS.map { it.name }

        /** Said in the briefing of an agent holding them. */
        val BRIEFING: String =
            "You can use the person's own computer through the desktop_* tools, while they have Orknux for " +
                "Windows or Orknux for Linux connected. $WINDOWS says which of the two it is, so call it first and " +
                "use that system's programs, paths and shortcuts; with more than one computer connected it lists each, and every desktop tool takes `computer` to say which: list windows with $WINDOWS, start a program with $OPEN, read a window with " +
                "$SNAPSHOT, then $CLICK, $TYPE and $PRESS by the refs it gives. You work on their real screen and " +
                "files, as them: ask before anything that deletes, installs, spends money or sends something in " +
                "their name, and do not touch windows the task is not about. When a desktop tool says no computer " +
                "is connected, tell them to start Orknux for Windows or Orknux for Linux and connect it."
    }
}
