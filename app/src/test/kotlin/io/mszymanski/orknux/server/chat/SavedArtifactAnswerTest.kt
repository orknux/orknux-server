package io.mszymanski.orknux.server.chat

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * What a model is handed after it saves an artifact.
 *
 * `save_artifact` is the one tool here that answers with a link, and the
 * asymmetry is deliberate. A saved artifact is a thing at an address - having
 * one is the point of saving it - so a model pointing at it has nothing else
 * to point with. What the picture tools make is bytes somebody wants
 * *delivered*, so those answer a key into the session's store and no link at
 * all: handed a link, a model pastes it, and a chat client with no document to
 * resolve the address against prints the construction instead of a picture.
 *
 * Absolute, for the same reason: the answer is read wherever the model writes
 * next, and a path has no host behind it once it has been copied into a
 * message.
 */
class SavedArtifactAnswerTest {

    @Test
    fun `the answer carries the artifact's own address, whole`() {
        val answer = AgentTools.savedAnswer("diagram.svg", 812, 41, "https://orknux.example.com")

        assertThat(answer["saved"]).isEqualTo("diagram.svg")
        assertThat(answer["bytes"]).isEqualTo(812L)
        assertThat(answer["url"])
            .describedAs("the installation's own address, not a path")
            .isEqualTo("https://orknux.example.com/api/artifacts/41")
    }

    @Test
    fun `and markdown, so the line does not have to be composed around an id`() {
        val answer = AgentTools.savedAnswer("report.pdf", 9, 7, "https://orknux.example.com")

        assertThat(answer["markdown"])
            .isEqualTo("[report.pdf](https://orknux.example.com/api/artifacts/7)")
    }

    /**
     * A base with a slash on the end is the same base.
     *
     * It is a setting somebody types, and half of them type the slash.
     */
    @Test
    fun `a trailing slash does not double`() {
        val answer = AgentTools.savedAnswer("a.txt", 1, 2, "https://orknux.example.com/")

        assertThat(answer["url"]).isEqualTo("https://orknux.example.com/api/artifacts/2")
    }

    /** A key beside the url, where the session could keep the content; nothing promised where it could not. */
    @Test
    fun `with a key the answer says so and how to use it, and without one says nothing about keys`() {
        val keyed = AgentTools.savedAnswer("page.html", 120, 3, "http://x", contentKey = "artifact.3")
        assertThat(keyed["contentKey"]).isEqualTo("artifact.3")
        assertThat(keyed["note"].toString()).contains("pass that key").contains("never type the content back")

        val bare = AgentTools.savedAnswer("page.html", 120, 3, "http://x")
        assertThat(bare).doesNotContainKeys("contentKey", "note")
    }
}
