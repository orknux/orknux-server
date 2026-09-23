package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * A workflow saying something out loud, and keeping what it said.
 *
 * Issue #264. The product spoke in a chat and nowhere else, so a run that wanted
 * to hand somebody audio - a summary to listen to on the way in, a message for a
 * channel where nobody reads - had no way to make any. Every other kind of
 * output a run produces is a file it leaves behind, and this was the missing
 * one.
 *
 * What is pinned here is the refusals and the bounds rather than the sound: the
 * speaking itself is a provider call, and a test that made one would be testing
 * somebody else's server and billing this workspace for it. Each refusal is a
 * sentence the step reports, because a workspace with no speech model chosen is
 * something nobody has set up rather than a run that went wrong - and a stack
 * trace reaching the run page would say the opposite.
 *
 * Makes its own workspace and leaves it.
 */
@SpringBootTest
class StepSpeechTest(
    @Autowired val speech: StepSpeech,
    @Autowired val speeches: ExecutionSpeechRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun make() {
        workspaceId = requireNotNull(
            (workspaces.findByName("speaking") ?: workspaces.save(Workspace(name = "speaking"))).id,
        )
        // No speech model chosen, which is the state most workspaces are in.
        workspaces.findByIdOrNull(workspaceId)?.let {
            it.speechModelId = null
            workspaces.save(it)
        }
    }

    private fun refusal(said: StepSpoken): String = (said as StepSpoken.Refused).reason

    @Test
    fun `a workspace with no speech model is told so rather than failing`() {
        val said = speech.speak(executionId = 1, nodeKey = "n1", workspaceId = workspaceId, text = "anything")

        assertThat(said).isInstanceOf(StepSpoken.Refused::class.java)
        assertThat(refusal(said)).contains("no text-to-speech model")
        // And it says where to choose one, because the next thing somebody does
        // is look for the setting.
        assertThat(refusal(said)).contains("Chat settings")
    }

    @Test
    fun `nothing to say is refused before a provider is called`() {
        val said = speech.speak(executionId = 1, nodeKey = "n1", workspaceId = workspaceId, text = "   ")

        assertThat(refusal(said)).contains("nothing to say")
    }

    /**
     * Billed by the character, and a run that has generated four thousand
     * characters of speech has produced something nobody is going to listen to.
     * The refusal is more use than the file.
     */
    @Test
    fun `more than one sitting's worth is refused with the figure in it`() {
        val said = speech.speak(
            executionId = 1,
            nodeKey = "n1",
            workspaceId = workspaceId,
            text = "a".repeat(StepSpeech.MOST_TEXT + 1),
        )

        assertThat(refusal(said)).contains("too long to read out")
        assertThat(refusal(said)).contains(StepSpeech.MOST_TEXT.toString())
    }

    /**
     * The order the refusals come in is itself a decision.
     *
     * What is wrong with the text is local, cheap and the thing the node's
     * author is looking at; the model is a workspace setting the same person may
     * not own. So with both wrong at once the text is named - and a node with
     * nothing to say never reaches the model lookup at all.
     */
    @Test
    fun `the words are what is named first, because that is the node in front of somebody`() {
        val said = speech.speak(executionId = 1, nodeKey = "n1", workspaceId = workspaceId, text = "")

        assertThat(refusal(said)).contains("nothing to say")
    }

    @Test
    fun `a run that has said nothing has nothing filed against it`() {
        assertThat(speeches.countByExecutionId(1)).isEqualTo(0)
        speech.speak(executionId = 1, nodeKey = "n1", workspaceId = workspaceId, text = "anything")
        // Refused, so nothing was stored: a row for an attempt that produced no
        // audio would be a run page offering a player with nothing behind it.
        assertThat(speeches.countByExecutionId(1)).isEqualTo(0)
    }
}

private fun WorkspaceRepository.findByIdOrNull(id: Long): Workspace? = findById(id).orElse(null)
