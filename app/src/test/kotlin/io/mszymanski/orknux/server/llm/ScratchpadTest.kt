package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * A working file an agent keeps within a session: written a piece at a time,
 * read in fragments, searched, shared with the agents it asks, and bounded.
 * Issue #411.
 *
 * Makes its own workspace and session each time.
 */
@SpringBootTest
class ScratchpadTest(
    @Autowired val pads: SessionScratchpadService,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val settings: InstallationSettings,
    /** The model's way in, and what it is told about it. Issue #445. */
    @Autowired val tools: io.mszymanski.orknux.server.chat.ScratchpadTools,
    /** Where a kept pad lands, for the tool that hands one to a key. Issue #417. */
    @Autowired val store: LlmSessionStore,
) {

    private var session: Long = 0

    /**
     * Nothing told the agent it had scratchpads, so it never used one - the
     * silence that kept memory search unused until the briefing named the
     * memory. The shed says so itself, and says when; two sheds lent together
     * say both. Issue #445.
     */
    @Test
    fun `the shed tells the agent it has scratchpads, and when to use one`() {
        val shed = requireNotNull(tools.shed(session))
        val said = requireNotNull(shed.briefing())
        assertThat(said).contains("You have scratchpads")
        assertThat(said).contains("longer than a message")
        assertThat(said).contains("scratchpad_write")

        assertThat(tools.shed(null)).describedAs("nowhere to keep a file").isNull()

        val silent = object : io.mszymanski.orknux.server.chat.ToolShed {
            override fun specs() = emptyList<io.mszymanski.orknux.connector.model.ToolSpec>()
            override fun handles(name: String) = false
            override fun run(call: io.mszymanski.orknux.connector.model.ToolCall) = ""
        }
        assertThat(silent.briefing()).describedAs("a shed with nothing to say").isNull()
        assertThat(io.mszymanski.orknux.server.chat.sheds(silent, shed)?.briefing()).isEqualTo(said)
    }

    private var workspaceId: Long = 0

    @BeforeEach
    fun make() {
        workspaceId = requireNotNull(
            (workspaces.findByName("pads") ?: workspaces.save(Workspace(name = "pads"))).id,
        )
        session = recorder.open(workspaceId, "test", "pads-${System.nanoTime()}")
    }

    /**
     * A pad handed to something that takes a key. Issue #417: an upload, a
     * message, a mail all take a key into the session store, and a model that
     * had written a page into a pad could only reach them by reading it and
     * typing it back - which is the thing the output limit cuts off.
     */
    @Test
    fun `a scratchpad is kept in the session store, and answers with its key`() {
        ok(pads.create(session, "page.html", "The landing page", "<h1>Hello</h1>"))
        val shed = requireNotNull(tools.shed(session))

        val said = shed.run(
            io.mszymanski.orknux.connector.model.ToolCall("1", "scratchpad_keep", """{"name":"page.html"}"""),
        )

        assertThat(said).contains("\"key\":\"page.html\"")
        assertThat(store.get(session, "page.html")).isEqualTo("\"<h1>Hello</h1>\"")
        // The pad is left where it was: this is a copy, not a move.
        assertThat(requireNotNull(pads.find(session, "page.html")).content).isEqualTo("<h1>Hello</h1>")

        // Under a name of the agent's choosing where it gives one.
        shed.run(
            io.mszymanski.orknux.connector.model.ToolCall(
                "2",
                "scratchpad_keep",
                """{"name":"page.html","key":"site"}""",
            ),
        )
        assertThat(store.get(session, "site")).isEqualTo("\"<h1>Hello</h1>\"")

        // And a pad that is not there is said so rather than kept as nothing.
        val missed = shed.run(
            io.mszymanski.orknux.connector.model.ToolCall("3", "scratchpad_keep", """{"name":"nothing"}"""),
        )
        assertThat(missed).contains("no scratchpad called")
    }

    /**
     * A page naming a picture by its key. Issue #545: uploaded as HTML the src
     * points at nothing, and the model is told to host it or zip the two.
     */
    @Test
    fun `keeping a page that names a picture by key warns, and leaves the page as it is`() {
        store.put(session, "picture.30", "\"iVBORw0KGgo=\"")
        val page = "<h1>Mascots</h1><img src=\"picture.30\"><img src=\"https://example.invalid/a.png\">"
        ok(pads.create(session, "report.html", "The report", page))
        val shed = requireNotNull(tools.shed(session))

        val said = shed.run(
            io.mszymanski.orknux.connector.model.ToolCall("1", "scratchpad_keep", """{"name":"report.html"}"""),
        )

        assertThat(said).contains("\"warning\"").contains("picture.30").contains("zip_files")
            .doesNotContain("example.invalid")
        assertThat(store.get(session, "report.html")).contains("src=\\\"picture.30\\\"")
    }

    private fun ok(result: ScratchpadResult): ScratchpadResult.Ok {
        assertThat(result).isInstanceOf(ScratchpadResult.Ok::class.java)
        return result as ScratchpadResult.Ok
    }

    private fun no(result: ScratchpadResult): String {
        assertThat(result).isInstanceOf(ScratchpadResult.No::class.java)
        return (result as ScratchpadResult.No).why
    }

    /* --------------------------------------------------- the basics -------- */

    @Test
    fun `a scratchpad is created and read back whole`() {
        ok(pads.create(session, "draft.html", "The landing page", "<h1>Hello</h1>"))

        val held = requireNotNull(pads.find(session, "draft.html"))
        assertThat(held.content).isEqualTo("<h1>Hello</h1>")
        assertThat(held.description).isEqualTo("The landing page")
    }

    @Test
    fun `two scratchpads with the same name in one session is refused`() {
        ok(pads.create(session, "notes", null, "one"))
        assertThat(no(pads.create(session, "notes", null, "two"))).contains("already has a scratchpad")
    }

    @Test
    fun `append grows a file without resending it`() {
        ok(pads.create(session, "log", null, "line one\n"))
        ok(pads.append(session, "log", "line two\n"))

        assertThat(requireNotNull(pads.find(session, "log")).content).isEqualTo("line one\nline two\n")
    }

    @Test
    fun `replace changes one piece in place, and refuses an absent or ambiguous one`() {
        ok(pads.create(session, "code", null, "val x = 1\nval y = 1\n"))

        // Ambiguous: "= 1" is there twice.
        assertThat(no(pads.replace(session, "code", "= 1", "= 2"))).contains("more than once")
        // Absent.
        assertThat(no(pads.replace(session, "code", "= 9", "= 2"))).contains("not in")
        // Unique.
        ok(pads.replace(session, "code", "val x = 1", "val x = 42"))
        assertThat(requireNotNull(pads.find(session, "code")).content).isEqualTo("val x = 42\nval y = 1\n")
    }

    @Test
    fun `describe sets what a file is for, and the list shows it`() {
        ok(pads.create(session, "plan", null, "steps"))
        ok(pads.describe(session, "plan", "The rollout plan"))

        val listed = pads.list(session).single { it.name == "plan" }
        assertThat(listed.description).isEqualTo("The rollout plan")
        assertThat(listed.bytes).isEqualTo("steps".length)
    }

    @Test
    fun `delete removes a file`() {
        ok(pads.create(session, "temp", null, "throwaway"))
        ok(pads.delete(session, "temp"))
        assertThat(pads.find(session, "temp")).isNull()
    }

    /* --------------------------------------------------- search ------------ */

    @Test
    fun `search says which file and line a piece of text is on`() {
        ok(pads.create(session, "a.txt", null, "alpha\nbeta\ngamma"))
        ok(pads.create(session, "b.txt", null, "delta\nBETA rising"))

        val hits = pads.search(session, "beta")
        assertThat(hits).hasSize(2)
        assertThat(hits.map { it.name }).containsExactlyInAnyOrder("a.txt", "b.txt")
        val inA = hits.single { it.name == "a.txt" }
        assertThat(inA.line).isEqualTo(2)
        assertThat(inA.text).isEqualTo("beta")
    }

    /* --------------------------------------------------- the budget -------- */

    @Test
    fun `a write that would put the session over its byte budget is refused`() {
        val was = settings.scratchpadBudgetBytes()
        try {
            settings.setScratchpadBudgetBytes(1024, "test")
            ok(pads.create(session, "big", null, "a".repeat(1000)))
            // 1000 held, appending 100 would be 1100 > 1024.
            assertThat(no(pads.append(session, "big", "b".repeat(100)))).contains("over the")
            // And it was not written.
            assertThat(requireNotNull(pads.find(session, "big")).bytes).isEqualTo(1000)
        } finally {
            settings.setScratchpadBudgetBytes(was, "test")
        }
    }

    /* --------------------------------------------------- sharing ----------- */

    @Test
    fun `a shared file reaches the sessions started under the one that owns it`() {
        ok(pads.create(session, "shared.md", "Handover", "what the shift found\n"))
        ok(pads.share(session, "shared.md", true))
        val child = recorder.openUnder(session, "Summarise the day")

        // The child sees it and can add to it.
        assertThat(pads.find(child, "shared.md")?.content).isEqualTo("what the shift found\n")
        assertThat(pads.list(child).map { it.name }).contains("shared.md")
        ok(pads.append(child, "shared.md", "and what the child added\n"))

        // Both see the addition - it is one file.
        assertThat(requireNotNull(pads.find(session, "shared.md")).content)
            .isEqualTo("what the shift found\nand what the child added\n")
    }

    /**
     * A subagent rewriting the conversation's file rewrites the conversation's
     * file. Issue #497.
     *
     * The write tool used to make a copy in the child's own session whenever
     * the name resolved to an ancestor's shared pad - so a subagent asked to
     * rewrite a stylesheet did the work, said so, and the conversation went on
     * reading the original, with nothing anywhere saying a fork had happened.
     * Append and replace always wrote through; write forking alone made the
     * first operation anybody reaches for the one that quietly did something
     * else.
     */
    @Test
    fun `a subagent writing a shared file writes the shared file, not a copy`() {
        ok(pads.create(session, "styles.css", "The stylesheet", "body { color: #333; }"))
        ok(pads.share(session, "styles.css", true))
        val child = recorder.openUnder(session, "Make it dark")

        val shed = requireNotNull(tools.shed(child))
        val said = shed.run(
            io.mszymanski.orknux.connector.model.ToolCall(
                id = "call_1",
                name = "scratchpad_write",
                arguments = """{"name":"styles.css","content":"body { color: #eee; }"}""",
            ),
        )

        // Written where the file actually is.
        assertThat(requireNotNull(pads.find(session, "styles.css")).content).isEqualTo("body { color: #eee; }")
        // And no second file of that name under the child, which is the fork
        // this is about.
        assertThat(pads.list(child).count { it.name == "styles.css" }).isEqualTo(1)
        // The answer says whose file it was, so an agent can tell.
        assertThat(said).contains("\"ownedHere\":false")
        assertThat(said).contains("belongs to the conversation that started you")
    }

    /**
     * The files are the conversation's, in both directions. Issue #498.
     *
     * This used to assert the opposite - a pad nobody had shared was invisible
     * below - and the flag was the fault: an agent had to remember to set it,
     * a subagent's work landed where nobody looked, and "did it save?" had two
     * answers. A subagent is not separate work; it is this conversation asking
     * somebody to do part of it.
     */
    @Test
    fun `a conversation's files reach the sessions it asks, and theirs come back`() {
        ok(pads.create(session, "notes.md", null, "what the shift found"))
        val child = recorder.openUnder(session, "A task")

        // Down, with nothing shared.
        assertThat(pads.find(child, "notes.md")?.content).isEqualTo("what the shift found")
        assertThat(pads.list(child).map { it.name }).contains("notes.md")

        // And up: what the subagent makes is there for the conversation.
        ok(pads.create(child, "findings.md", null, "what the subagent found"))
        assertThat(pads.find(session, "findings.md")?.content).isEqualTo("what the subagent found")
        assertThat(pads.list(session).map { it.name }).contains("findings.md")

        // A session of another conversation sees neither, which is the bound
        // that makes this a family rather than a workspace-wide pile.
        val stranger = recorder.open(workspaceId, "unrelated", "stranger-${System.nanoTime()}")
        assertThat(pads.find(stranger, "notes.md")).isNull()
        assertThat(pads.find(stranger, "findings.md")).isNull()
    }

    @Test
    fun `only the owning session may share or delete a file`() {
        ok(pads.create(session, "doc", null, "body"))
        ok(pads.share(session, "doc", true))
        val child = recorder.openUnder(session, "A task")

        // The child inherited it but does not own it, so it cannot re-share or remove it.
        assertThat(no(pads.share(child, "doc", false))).contains("of its own")
        assertThat(no(pads.delete(child, "doc"))).contains("of its own")
        // The owner still has it.
        assertThat(pads.find(session, "doc")).isNotNull()
    }
}
