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

    /** Seated outranks away, so the seat goes when the tab says so, not when its stream is next written to. */
    @Test
    fun `a tab that goes out of sight lets go of its seat at once`() {
        val alice = rooms.join(key, "tab-alice", "alice")
        rooms.away(key, "tab-alice", "alice")
        assertThat(alice.frames).contains(LiveRooms.Frame.CLOSED)
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

    /**
     * A tab closed or reloaded while looked at says so too: its stream's end is
     * only noticed at the next write, and until then it stayed among the
     * avatars. Saying it is gone ends the stream's wait, which leaves the room.
     */
    @Test
    fun `a tab closed while looked at ends its stream at once`() {
        rooms.join(key, "tab-bob", "bob")
        val alice = rooms.join(key, "tab-alice", "alice")

        rooms.gone(key, "tab-alice", "alice")
        // The stream reads up to the close and ends there.
        assertThat(alice.frames).contains(LiveRooms.Frame.CLOSED)
        rooms.leave(alice)
        assertThat(shown()).doesNotContainKey("alice").containsKey("bob")
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
