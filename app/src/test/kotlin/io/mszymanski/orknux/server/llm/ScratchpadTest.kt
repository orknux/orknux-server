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

    @BeforeEach
    fun make() {
        val workspaceId = requireNotNull(
            (workspaces.findByName("pads") ?: workspaces.save(Workspace(name = "pads"))).id,
        )
        session = recorder.open(workspaceId, "test", "pads-${System.nanoTime()}")
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

    @Test
    fun `a file that is not shared is invisible to a session under it`() {
        ok(pads.create(session, "private", null, "for me only"))
        val child = recorder.openUnder(session, "A task")

        assertThat(pads.find(child, "private")).isNull()
        assertThat(pads.list(child).map { it.name }).doesNotContain("private")
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
