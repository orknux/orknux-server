package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.attachment.DEFAULT_NOTE_CHARACTERS
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.attachment.NoteLengthOutOfRangeException
import io.mszymanski.orknux.server.llm.LlmSessionEventKind
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest

/**
 * An agent writing something down for itself, part-way through. Issue #371.
 *
 * An agent that ends its turn with a wake-up is handed the note it left, which
 * #367 built and which covers the moment it parks. What it had no way to do is
 * write something down while it is still working: what the first six steps of a
 * long job found, the thing it must not forget at the end, the reason it ruled
 * an approach out.
 *
 * The transcript is not that, which is the whole reason this exists. What comes
 * back into a turn is a share of the model's context window, so what an agent
 * said twenty turns ago is exactly what is gone by the time it matters. A note
 * is kept apart and handed back whole.
 *
 * Makes its own workspace and session.
 */
@SpringBootTest
class NoteToSelfTest(
    @Autowired val notes: NoteTools,
    @Autowired val sessions: LlmSessionRecorder,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val events: LlmSessionEventRepository,
    @Autowired val settings: InstallationSettings,
) {

    private var session: Long = 0

    @BeforeEach
    fun make() {
        val workspaceId = requireNotNull(
            (workspaces.findByName("notes") ?: workspaces.save(Workspace(name = "notes"))).id,
        )
        // Its own session each time: the bound is per conversation, and a test
        // that shared one would be counting the last test's notes.
        session = sessions.open(workspaceId, "test", "notes-${System.nanoTime()}")
    }

    private fun call(note: String) = ToolCall("1", NoteTools.NOTE, """{"note":"$note"}""")

    private fun write(note: String): String =
        requireNotNull(notes.shed(session, "Support responder")).run(call(note))

    /* ------------------------------------------- where it is offered ------- */

    /**
     * A tool that takes a note and has nowhere to put it is worse than no tool,
     * because the agent goes on believing it was kept.
     */
    @Test
    fun `a turn with no conversation to keep it in is not offered the tool`() {
        assertThat(notes.shed(null, "Support responder")).isNull()
    }

    @Test
    fun `a turn with one is, and says what it is for`() {
        val spec = requireNotNull(notes.shed(session, "Support responder")).specs().single()

        assertThat(spec.name).isEqualTo(NoteTools.NOTE)
        // The distinction that makes it worth having: an answer is trimmed away
        // and a note is not.
        assertThat(spec.description).contains("trimmed to fit")
        assertThat(spec.parameters.single().required).isTrue()
    }

    /* ------------------------------------------- what it keeps ------------- */

    @Test
    fun `what is written down comes back, oldest first`() {
        write("Steps 1-6 are done.")
        write("The failing one is the third.")

        val said = notes.recalled(session)

        assertThat(said).contains("Steps 1-6 are done.")
        assertThat(said).contains("The failing one is the third.")
        assertThat(said.indexOf("Steps 1-6")).isLessThan(said.indexOf("The failing one"))
    }

    @Test
    fun `a conversation with nothing written carries no section at all`() {
        // Rather than a heading with nothing under it, which is a prompt paying
        // for a sentence that says only that there is no sentence.
        assertThat(notes.recalled(session)).isEmpty()
        assertThat(notes.recalled(null)).isEmpty()
    }

    @Test
    fun `it says who wrote it, because a conversation can be shared`() {
        write("Steps 1-6 are done.")

        assertThat(sessions.notesOf(session).single().writtenBy).isEqualTo("Support responder")
    }

    /**
     * A note is also a line in the log, where it was written, so a reader
     * following the transcript sees it in time order rather than lifted into a
     * header. It signs the note with whoever wrote it, and carries its text.
     * Issue #409.
     */
    @Test
    fun `a note is drawn in the log where it was written`() {
        write("The failing one is the third.")

        val logged = events
            .after(session, 0L, PageRequest.of(0, 50))
            .filter { it.kind == LlmSessionEventKind.NOTE }

        assertThat(logged).hasSize(1)
        assertThat(logged.single().content).isEqualTo("The failing one is the third.")
        assertThat(logged.single().actor).isEqualTo("Support responder")
    }

    /* ------------------------------------------------- the bounds ---------- */

    @Test
    fun `an empty note is refused rather than kept`() {
        assertThat(write("   ")).contains("nothing in that note")
        assertThat(sessions.notesOf(session)).isEmpty()
    }

    /**
     * Every note is read back on every later turn, so a long one is paid for on
     * every turn rather than once.
     */
    @Test
    fun `a note too long to carry is refused, and says why`() {
        val said = write("a".repeat(DEFAULT_NOTE_CHARACTERS + 1))

        assertThat(said).contains("kept up to $DEFAULT_NOTE_CHARACTERS")
        assertThat(said).contains("paid for on every turn")
        assertThat(sessions.notesOf(session)).isEmpty()
    }

    /**
     * The length is the installation's, and the agent is told it before it
     * writes: a review agent wrote seven hundred characters against a fixed five
     * hundred and was refused twice running.
     */
    @Test
    fun `the longest note is an administrator's setting, and the tool says it up front`() {
        try {
            settings.setNoteMaxCharacters(2000, "alice")
            val spec = requireNotNull(notes.shed(session, "Support responder")).specs().single()
            assertThat(spec.parameters.single().description).contains("at most 2000 characters")

            assertThat(write("a".repeat(1500))).contains("\"written\":true")
            assertThat(write("a".repeat(2001))).contains("kept up to 2000")
            assertThatThrownBy { settings.setNoteMaxCharacters(99, "alice") }
                .isInstanceOf(NoteLengthOutOfRangeException::class.java)
        } finally {
            settings.setNoteMaxCharacters(settings.noteMaxCharactersConfigured(), "alice")
        }
        assertThat(settings.noteMaxCharacters()).isEqualTo(1000)
    }

    /**
     * Refused rather than dropped: an agent told it is full can decide what
     * matters, where one whose notes vanished would go on believing they were
     * kept.
     */
    @Test
    fun `a conversation full of notes says so rather than losing one`() {
        repeat(NoteTools.MOST) { write("Note $it.") }

        val said = write("One more.")
        assertThat(said).contains("as many as")
        assertThat(sessions.notesOf(session)).hasSize(NoteTools.MOST)
        assertThat(notes.recalled(session)).doesNotContain("One more.")
    }

    @Test
    fun `writing one answers with how many are now held`() {
        assertThat(write("Steps 1-6 are done.")).contains("\"notes\":1")
        assertThat(write("The failing one is the third.")).contains("\"notes\":2")
    }
}
