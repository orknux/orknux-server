package io.mszymanski.orknux.server.live

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * A person whose editor tab is out of sight is still in the room, inactive.
 *
 * A tab out of sight lets go of its stream: a browser keeps six connections to
 * one host over plain HTTP, every open editor held one, and past six tabs
 * Orknux read as frozen. Gone from the room, though, a person who had only
 * switched tabs vanished from the others' editor. So they stay, marked
 * inactive, until they look at it again or close it.
 */
@SpringBootTest
class LiveAwayTest(@Autowired val rooms: LiveRooms) {

    private val key = LiveRooms.Key(LiveKind.DIAGRAM, System.nanoTime())

    private fun shown() = rooms.viewers(key).associate { it.login to it.active }

    @Test
    fun `a tab that goes out of sight stays in the room, inactive, and is active again when looked at`() {
        rooms.join(key, "tab-bob", "bob")
        val alice = rooms.join(key, "tab-alice", "alice")

        rooms.away(key, "tab-alice", "alice")
        rooms.leave(alice)
        assertThat(shown()).containsEntry("alice", false).containsEntry("bob", true)
        assertThat(rooms.viewers(key).single { it.login == "alice" }.awaySince).isNotNull()

        rooms.join(key, "tab-alice", "alice")
        assertThat(shown()).containsEntry("alice", true)
        assertThat(rooms.viewers(key).count { it.viewer == "tab-alice" }).isEqualTo(1)
    }

    @Test
    fun `a tab closed while away leaves the room at once`() {
        rooms.join(key, "tab-bob", "bob")
        val alice = rooms.join(key, "tab-alice", "alice")
        rooms.away(key, "tab-alice", "alice")
        rooms.leave(alice)

        rooms.gone(key, "tab-alice", "alice")
        assertThat(shown()).doesNotContainKey("alice")
    }

    /** A stream that just ends - a tab closed while looked at - leaves as it always did. */
    @Test
    fun `a tab that closes without going away is not kept`() {
        val alice = rooms.join(key, "tab-alice", "alice")
        rooms.leave(alice)
        assertThat(shown()).doesNotContainKey("alice")
    }

    /** Only the tab itself, under its own login, says it is away or gone. */
    @Test
    fun `nobody else can mark a tab away or take it out`() {
        rooms.join(key, "tab-alice", "alice")
        rooms.away(key, "tab-alice", "mallory")
        assertThat(shown()).containsEntry("alice", true)

        val bob = rooms.join(key, "tab-bob", "bob")
        rooms.away(key, "tab-bob", "bob")
        rooms.leave(bob)
        rooms.gone(key, "tab-bob", "mallory")
        assertThat(shown()).containsEntry("bob", false)
    }
}
