package io.mszymanski.orknux.server.workspace

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * How voice mode decides somebody has finished talking, as the API offers it.
 *
 * The microphone kept ending people's turns while they were still speaking, and
 * every fix was a better guess at one number that suits one voice, one room and
 * one microphone. It is not a number that can be got right centrally, so a
 * workspace says - and what is worth pinning down here is that saying nothing
 * remains a thing a workspace can say.
 *
 * Null on all three is what every workspace starts as and what clearing returns
 * it to, and it has to reach the client as null rather than as a number, because
 * the values a workspace that has decided nothing gets live in the interface.
 * A server that helpfully filled them in would be a second source of truth, and
 * the first change to either half would make the two disagree without failing
 * anything.
 *
 * The bounds are the other half. They are not tuning advice: the floor under the
 * pause sits above the value that was demonstrated to cut people off, so the
 * reported bug cannot be reproduced through configuration, and the floor under
 * the unattended microphone sits well clear of a long spoken thought because
 * two values that looked like reasonable limits both cut the same person off.
 * Each refusal names what is allowed, which is what a form has to show beside
 * the control.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class WorkspaceVoiceTurnTakingTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    /**
     * A workspace nobody has touched has decided nothing, and says so.
     *
     * Three nulls rather than three numbers. The interface reads
     * `workspace.voicePauseEndsTurnMs ?? SILENCE_MS`, so null is what hands the
     * decision back to the half that owns those values.
     */
    @Test
    fun `a workspace that has decided nothing reports three nulls`() {
        graphQlTester.document(
            """query { workspace(id: $workspaceId) {
                 voicePauseEndsTurnMs voiceSpeechOverRoomPercent voiceUnattendedMicrophoneMs
               } }""",
        ).execute()
            .path("workspace.voicePauseEndsTurnMs").valueIsNull()
            .path("workspace.voiceSpeechOverRoomPercent").valueIsNull()
            .path("workspace.voiceUnattendedMicrophoneMs").valueIsNull()

        with(workspaces.findAll().single()) {
            assertThat(voicePauseEndsTurnMs).isNull()
            assertThat(voiceSpeechOverRoomPercent).isNull()
            assertThat(voiceUnattendedMicrophoneMs).isNull()
        }
    }

    /**
     * All three set in one call, read back from the mutation and from the row.
     *
     * One call because they are one decision: a generous pause that a
     * sensitivity never lets the microphone reach is not a setting anybody
     * meant to make, and three mutations would let it be made a step at a time.
     */
    @Test
    fun `each setting round-trips`() {
        graphQlTester.document(
            """mutation { setWorkspaceVoiceTurnTaking(workspaceId: $workspaceId,
                 pauseEndsTurnMs: 4000, speechOverRoomPercent: 180, unattendedMicrophoneMs: 900000) {
                 voicePauseEndsTurnMs voiceSpeechOverRoomPercent voiceUnattendedMicrophoneMs
               } }""",
        ).execute()
            .path("setWorkspaceVoiceTurnTaking.voicePauseEndsTurnMs").entity(Int::class.java).isEqualTo(4_000)
            .path("setWorkspaceVoiceTurnTaking.voiceSpeechOverRoomPercent").entity(Int::class.java).isEqualTo(180)
            .path("setWorkspaceVoiceTurnTaking.voiceUnattendedMicrophoneMs").entity(Int::class.java)
            .isEqualTo(900_000)

        with(workspaces.findAll().single()) {
            assertThat(voicePauseEndsTurnMs).isEqualTo(4_000)
            assertThat(voiceSpeechOverRoomPercent).isEqualTo(180)
            assertThat(voiceUnattendedMicrophoneMs).isEqualTo(900_000)
        }

        graphQlTester.document(
            """query { workspace(id: $workspaceId) {
                 voicePauseEndsTurnMs voiceSpeechOverRoomPercent voiceUnattendedMicrophoneMs
               } }""",
        ).execute()
            .path("workspace.voicePauseEndsTurnMs").entity(Int::class.java).isEqualTo(4_000)
            .path("workspace.voiceSpeechOverRoomPercent").entity(Int::class.java).isEqualTo(180)
            .path("workspace.voiceUnattendedMicrophoneMs").entity(Int::class.java).isEqualTo(900_000)
    }

    /** The extremes of all three bounds are allowed, since a bound nobody may reach is not a bound. */
    @Test
    fun `the ends of each range are accepted`() {
        set(pause = 1_500, speech = 120, unattended = 300_000)
        with(workspaces.findAll().single()) {
            assertThat(voicePauseEndsTurnMs).isEqualTo(1_500)
            assertThat(voiceSpeechOverRoomPercent).isEqualTo(120)
            assertThat(voiceUnattendedMicrophoneMs).isEqualTo(300_000)
        }

        set(pause = 10_000, speech = 600, unattended = 3_600_000)
        with(workspaces.findAll().single()) {
            assertThat(voicePauseEndsTurnMs).isEqualTo(10_000)
            assertThat(voiceSpeechOverRoomPercent).isEqualTo(600)
            assertThat(voiceUnattendedMicrophoneMs).isEqualTo(3_600_000)
        }
    }

    /**
     * The floor under the pause is the reported bug, made unreachable.
     *
     * 1200 ms is the value that was cutting people off at clause breaks. It is
     * refused rather than allowed with a warning, because a setting that can be
     * typed is a setting somebody will type and then report the same bug from.
     */
    @Test
    fun `a pause shorter than the floor is refused, and the floor is above what cut people off`() {
        refused(pause = 1_200) { message ->
            assertThat(message).contains("between 1.5 and 10 seconds").contains("1500")
        }
    }

    /** And past ten seconds, where nothing happening reads as the application being broken. */
    @Test
    fun `a pause longer than the ceiling is refused`() {
        refused(pause = 15_000) { message ->
            assertThat(message).contains("between 1.5 and 10 seconds").contains("10000")
        }
    }

    /**
     * Under about 1.2 times the room the ratio cannot tell a voice from the
     * room, and the failure inverts: a breath holds the turn open for ever.
     */
    @Test
    fun `a sensitivity below the floor is refused`() {
        refused(speech = 100) { message ->
            assertThat(message).contains("between 120% and 600%").contains("never ends")
        }
    }

    /** And over six times the room you have to raise your voice, which is the original complaint. */
    @Test
    fun `a sensitivity above the ceiling is refused`() {
        refused(speech = 900) { message ->
            assertThat(message).contains("between 120% and 600%").contains("raise your voice")
        }
    }

    /**
     * Thirty seconds and two minutes both looked defensible and both cut the
     * same person off, so the floor is five minutes rather than a little above
     * whatever failed last.
     */
    @Test
    fun `an unattended microphone below the floor is refused`() {
        refused(unattended = 120_000) { message ->
            assertThat(message).contains("between 5 minutes and an hour").contains("300000")
        }
    }

    /** And past an hour, where a fuse that never blows is not a fuse. */
    @Test
    fun `an unattended microphone above the ceiling is refused`() {
        refused(unattended = 7_200_000) { message ->
            assertThat(message).contains("between 5 minutes and an hour").contains("3600000")
        }
    }

    /**
     * Clearing puts them back on the interface's own values, not on the last
     * number set.
     *
     * Which is the point of stating all three on every call: a form that offers
     * "the default" as a thing to choose needs a way to say it, and a mutation
     * that only ever accepted numbers would have none.
     */
    @Test
    fun `clearing returns all three to null`() {
        set(pause = 4_000, speech = 180, unattended = 900_000)
        set(pause = null, speech = null, unattended = null)

        with(workspaces.findAll().single()) {
            assertThat(voicePauseEndsTurnMs).isNull()
            assertThat(voiceSpeechOverRoomPercent).isNull()
            assertThat(voiceUnattendedMicrophoneMs).isNull()
        }
    }

    /** One of the three cleared and the others kept, which is what a form saves. */
    @Test
    fun `one setting can be cleared while the others stand`() {
        set(pause = 4_000, speech = 180, unattended = 900_000)
        set(pause = 4_000, speech = null, unattended = 900_000)

        with(workspaces.findAll().single()) {
            assertThat(voicePauseEndsTurnMs).isEqualTo(4_000)
            assertThat(voiceSpeechOverRoomPercent).isNull()
            assertThat(voiceUnattendedMicrophoneMs).isEqualTo(900_000)
        }
    }

    /**
     * A refusal changes nothing, including the settings it was not about.
     *
     * The whole call is one decision, so a sensitivity nobody can be heard over
     * must not land because the pause beside it was the part that was refused.
     */
    @Test
    fun `a refused call leaves every setting as it was`() {
        set(pause = 4_000, speech = 180, unattended = 900_000)

        graphQlTester.document(
            """mutation { setWorkspaceVoiceTurnTaking(workspaceId: $workspaceId,
                 pauseEndsTurnMs: 1200, speechOverRoomPercent: 300, unattendedMicrophoneMs: 600000) { id } }""",
        ).execute().errors().satisfy { errors -> assertThat(errors).hasSize(1) }

        with(workspaces.findAll().single()) {
            assertThat(voicePauseEndsTurnMs).isEqualTo(4_000)
            assertThat(voiceSpeechOverRoomPercent).isEqualTo(180)
            assertThat(voiceUnattendedMicrophoneMs).isEqualTo(900_000)
        }
    }

    /**
     * Setting it is worth a line of its own, under the category a chat's own
     * behaviour belongs to.
     *
     * CHAT rather than MODEL: it is not the transcription model that changed,
     * it is when the microphone stops listening - and somebody asking why their
     * turns are being cut short is reading the chat's history, not the model's.
     */
    @Test
    fun `changing it is recorded under CHAT, and clearing it says so`() {
        set(pause = 4_000, speech = 180, unattended = 900_000)
        set(pause = null, speech = null, unattended = null)

        val chat = audit.findAll().filter { it.category == WorkspaceAuditCategory.CHAT }.map { it.message }
        assertThat(chat)
            .contains(
                "Voice turn-taking set to a pause of 4000 ms, a voice at 180% of the room, " +
                    "an unattended microphone of 900000 ms and talking over the answer for the default",
            )
            .contains("Voice turn-taking back to the default")
    }

    /** And a partly cleared call says which part went back to the default. */
    @Test
    fun `the recorded line names what was cleared`() {
        set(pause = 4_000, speech = null, unattended = null)

        assertThat(audit.findAll().map { it.message }).contains(
            "Voice turn-taking set to a pause of 4000 ms, a voice at the default of the room, " +
                "an unattended microphone of the default and talking over the answer for the default",
        )
    }

    /* ------------------------------------ talking over the answer (#342) --- */

    /**
     * The microphone is held open while an answer is read aloud, so what is
     * said over one was always heard - what was missing is the answer stopping,
     * which is the one thing a person cannot do in a conversation: say "no, not
     * that" and be listened to.
     */
    @Test
    fun `talking over the answer is a length this workspace can set`() {
        graphQlTester.document(
            """mutation { setWorkspaceVoiceTurnTaking(workspaceId: $workspaceId, bargeInMs: 800) {
                 voiceBargeInMs
               } }""",
        ).execute()
            .path("setWorkspaceVoiceTurnTaking.voiceBargeInMs").entity(Int::class.java).isEqualTo(800)

        assertThat(workspaces.findAll().single().voiceBargeInMs).isEqualTo(800)
    }

    /**
     * Zero is outside the range and means it: off, for a room or a microphone
     * where the answer keeps stopping on nothing. No number inside the range
     * can say that.
     */
    @Test
    fun `zero is allowed, and is the workspace saying to leave the answer alone`() {
        graphQlTester.document(
            """mutation { setWorkspaceVoiceTurnTaking(workspaceId: $workspaceId, bargeInMs: 0) {
                 voiceBargeInMs
               } }""",
        ).execute()
            .path("setWorkspaceVoiceTurnTaking.voiceBargeInMs").entity(Int::class.java).isEqualTo(0)
    }

    @Test
    fun `a hold short enough for a cough to clear is refused, and says why`() {
        var message = ""
        graphQlTester.document(
            """mutation { setWorkspaceVoiceTurnTaking(workspaceId: $workspaceId, bargeInMs: 100) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors).hasSize(1)
            message = errors.single().message.orEmpty()
        }

        assertThat(message).contains("250").contains("3000")
        // And that there is a way to turn it off, which is what somebody
        // reaching for 100 ms may actually have wanted.
        assertThat(message).contains("0 to leave the answer alone")
        assertThat(workspaces.findAll().single().voiceBargeInMs).isNull()
    }

    @Test
    fun `a hold longer than the interruption is refused too`() {
        graphQlTester.document(
            """mutation { setWorkspaceVoiceTurnTaking(workspaceId: $workspaceId, bargeInMs: 5000) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors).singleElement().satisfies({
                assertThat(it.message).contains("the interruption is over")
            })
        }

        assertThat(workspaces.findAll().single().voiceBargeInMs).isNull()
    }

    private fun set(pause: Int? = null, speech: Int? = null, unattended: Int? = null) {
        graphQlTester.document(
            """mutation { setWorkspaceVoiceTurnTaking(workspaceId: $workspaceId,
                 pauseEndsTurnMs: ${pause ?: "null"},
                 speechOverRoomPercent: ${speech ?: "null"},
                 unattendedMicrophoneMs: ${unattended ?: "null"}) { id } }""",
        ).execute().path("setWorkspaceVoiceTurnTaking.id").hasValue()
    }

    /**
     * The call is refused, nothing is stored, and the sentence says what would
     * have been allowed.
     *
     * The other two are left null so that each test is about one bound; the
     * mutation checks them in order and the first one out of bounds is what is
     * reported.
     */
    private fun refused(
        pause: Int? = null,
        speech: Int? = null,
        unattended: Int? = null,
        expectation: (String) -> Unit,
    ) {
        var message = ""
        graphQlTester.document(
            """mutation { setWorkspaceVoiceTurnTaking(workspaceId: $workspaceId,
                 pauseEndsTurnMs: ${pause ?: "null"},
                 speechOverRoomPercent: ${speech ?: "null"},
                 unattendedMicrophoneMs: ${unattended ?: "null"}) { id } }""",
        ).execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors).hasSize(1)
                message = errors.single().message.orEmpty()
            }
        expectation(message)

        with(workspaces.findAll().single()) {
            assertThat(voicePauseEndsTurnMs).isNull()
            assertThat(voiceSpeechOverRoomPercent).isNull()
            assertThat(voiceUnattendedMicrophoneMs).isNull()
        }
    }
}
