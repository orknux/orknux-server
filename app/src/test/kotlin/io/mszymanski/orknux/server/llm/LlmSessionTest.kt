package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.ParameterizedTypeReference
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.context.support.WithMockUser

/**
 * A session's identity, and reading one back.
 *
 * Identity is most of what is worth pinning down here, because the thing that
 * looks like a bug is the feature: two workflows landing in one session is the
 * point, and the tests say so out loud rather than leaving somebody to discover
 * it and "fix" it. The other half is the separator, which is what keeps two
 * callers who meant different sessions out of each other's conversation - the
 * one collision that is not intended.
 *
 * The rest is what the AI perspective's two screens ask for: a list that can be
 * searched and counted, and a transcript that can be searched, filtered and
 * turned round.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class LlmSessionTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val events: LlmSessionEventRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private var otherWorkspaceId: Long = 0

    @BeforeEach
    fun reset() {
        events.deleteAll()
        sessions.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        otherWorkspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
    }

    /**
     * The whole reason a session is keyed rather than identified.
     *
     * Two runs, of two different workflows, weeks apart, that arrive at the same
     * halves are talking into one conversation. Nothing about a run is on the
     * row, which is what makes that true.
     */
    @Test
    fun `two callers that compute the same key are in the same session`() {
        val first = recorder.open(workspaceId, "issue", "42")
        val second = recorder.open(workspaceId, "issue", "42")

        assertThat(second).isEqualTo(first)
        assertThat(sessions.findAll()).hasSize(1)
        assertThat(sessions.findAll().single().sessionKey).isEqualTo("issue:42")
        assertThat(sessions.findAll().single().keyPrefix).isEqualTo("issue")
    }

    /**
     * The separator earns its place here.
     *
     * Joined bare, prefix "issue-" with key "42" would be the string
     * "issue-42" - the same session as somebody who used no prefix at all and
     * meant something else entirely. That is the one collision this must not
     * allow, and it is invisible without a test that names it.
     */
    @Test
    fun `a prefix is a namespace rather than a few more characters`() {
        val prefixed = recorder.open(workspaceId, "issue-", "42")
        val bare = recorder.open(workspaceId, null, "issue-42")

        assertThat(prefixed).isNotEqualTo(bare)
        assertThat(sessions.findAll().map { it.sessionKey })
            .containsExactlyInAnyOrder("issue-:42", "issue-42")
    }

    /** No prefix is the key alone, not a key with a colon in front of it. */
    @Test
    fun `a session without a prefix is keyed by its key`() {
        recorder.open(workspaceId, null, "thread-7")
        recorder.open(workspaceId, "   ", "thread-8")

        assertThat(sessions.findAll().map { it.sessionKey }).containsExactlyInAnyOrder("thread-7", "thread-8")
        assertThat(sessions.findAll().map { it.keyPrefix }).containsOnlyNulls()
    }

    /**
     * A key nobody can store is said so rather than trimmed to fit.
     *
     * Two long keys cut to the same three hundred characters would be one
     * session, and pouring two conversations into one quietly is worse than
     * refusing a mapping that has to be shortened.
     */
    @Test
    fun `a key that names nothing, and one that will not fit, are both refused`() {
        assertThrows<LlmSessionKeyMissingException> { recorder.open(workspaceId, "issue", "   ") }
        assertThrows<LlmSessionKeyTooLongException> {
            recorder.open(workspaceId, "x".repeat(120), "y".repeat(200))
        }
        assertThat(sessions.findAll()).isEmpty()
    }

    /** The key is unique here and not everywhere: two teams may both say "standup". */
    @Test
    fun `the same key in two workspaces is two conversations`() {
        val ours = recorder.open(workspaceId, null, "standup")
        val theirs = recorder.open(otherWorkspaceId, null, "standup")

        assertThat(ours).isNotEqualTo(theirs)
        assertThat(sessions.findAll()).hasSize(2)
    }

    /**
     * All four kinds, read back as one transcript in the order they happened.
     *
     * One test rather than four, because the order is as much the subject as the
     * lines: a transcript is read as a conversation, and the question has to be
     * above the answer.
     */
    /**
     * Newest first unless asked otherwise, and the order it happened in on request.
     *
     * The default is the reverse of the order it was written in: a transcript is
     * opened to see how a turn ended - the last tool call, the answer, the
     * refusal - and reading that meant paging to the end first.
     */
    @Test
    fun `what was recorded is read back newest first, and oldest first on request`() {
        val session = recorder.open(workspaceId, "issue", "42")
        recorder.userSaid(session, "Ask reviewer", "Why is the reply late?")
        recorder.toolCalled(session, "skill_load", """{"name":"codeReview"}""")
        recorder.agentSaid(session, "Reviewer", "The database was the cause.")
        recorder.note(session, "Reviewer could not answer: the model refused")

        val lines = events(session)
        assertThat(lines.map { it["kind"] }).containsExactly("SYSTEM", "AGENT", "TOOL", "USER")
        assertThat(lines.map { it["actor"] })
            .containsExactly("system", "Reviewer", "skill_load", "Ask reviewer")

        // And the way it happened, for somebody following a turn through.
        val told = events(session, ascending = true)
        assertThat(told.map { it["kind"] }).containsExactly("USER", "TOOL", "AGENT", "SYSTEM")
        assertThat(told.map { it["actor"] })
            .containsExactly("Ask reviewer", "skill_load", "Reviewer", "system")
        assertThat(told[1]["content"] as String).contains("codeReview")

        // The session's own clock moved with the last of them, which is what the
        // list is ordered by.
        assertThat(requireNotNull(sessions.findAll().single().lastEventAt)).isNotNull()
    }

    /** The list page: searched across the key and the prefix, counted per row. */
    @Test
    fun `the list is searched and says how much each session holds`() {
        val incident = recorder.open(workspaceId, "issue", "42")
        recorder.userSaid(incident, "Ask reviewer", "Why is the reply late?")
        recorder.agentSaid(incident, "Reviewer", "The database was the cause.")
        recorder.open(workspaceId, "thread", "C123")

        val all = list()
        assertThat(all).hasSize(2)
        assertThat(all.single { it["key"] == "issue:42" }["eventCount"]).isEqualTo(2)
        // A session opened and not written to says nothing rather than nothing
        // at all: zero is an answer, and null would be a hole in the column.
        assertThat(all.single { it["key"] == "thread:C123" }["eventCount"]).isEqualTo(0)
        assertThat(all.single { it["key"] == "thread:C123" }["lastEventAt"]).isNull()

        assertThat(list(search = "issue").map { it["key"] }).containsExactly("issue:42")
        // The prefix is searched as well as the key, so a family can be found by
        // the name it was namespaced under.
        assertThat(list(search = "thread").map { it["key"] }).containsExactly("thread:C123")
        assertThat(list(search = "nothing here")).isEmpty()
    }

    /**
     * The list's two glances: whether an agent is at work in a session, and how
     * many it fanned out into. Issues #403, #404.
     */
    @Test
    fun `the list says which sessions are active and how many subagents each has`() {
        // Active: a line written just now is within the window.
        val busy = recorder.open(workspaceId, "issue", "42")
        recorder.userSaid(busy, "Ask reviewer", "Why is the reply late?")
        // And it fanned out: two sessions started under it.
        recorder.openUnder(busy, "Summarise the incident")
        recorder.openUnder(busy, "Find the service owners")

        // Inactive: nothing unfinished, and its last line pushed back past the
        // window - the only way to make "not active" without waiting a minute.
        val quiet = recorder.open(workspaceId, "thread", "C9")
        recorder.userSaid(quiet, "Ask reviewer", "Any update?")
        val held = sessions.findById(quiet).orElseThrow()
        held.lastEventAt = java.time.OffsetDateTime.now().minusMinutes(5)
        sessions.save(held)

        val byKey = list().associateBy { it["key"] }
        assertThat(byKey["issue:42"]?.get("active")).isEqualTo(true)
        assertThat(byKey["issue:42"]?.get("subagentCount")).isEqualTo(2)
        assertThat(byKey["thread:C9"]?.get("active")).isEqualTo(false)
        assertThat(byKey["thread:C9"]?.get("subagentCount")).isEqualTo(0)
    }

    /**
     * An unfinished line only counts while something is still writing.
     *
     * Issue #404 read a tool call with no result as an agent at work, which it
     * is - right up until the run that made the call dies under a restart and
     * leaves the line open for ever, and the session reads as active for ever
     * with it. Issue #448 made the open line count only while a run or a task
     * writing into that session is still going, and this session has neither:
     * nothing started it, so an old clock and an open call is a session nobody
     * is in.
     */
    @Test
    fun `a call still out does not light a session nothing is writing into`() {
        val session = recorder.open(workspaceId, "issue", "77")
        // A tool called with no result yet: the unfinished half of the old rule.
        recorder.toolCalled(session, "skill_load", """{"name":"codeReview"}""")
        val held = sessions.findById(session).orElseThrow()
        held.lastEventAt = java.time.OffsetDateTime.now().minusMinutes(5)
        sessions.save(held)

        assertThat(list().single { it["key"] == "issue:77" }["active"]).isEqualTo(false)

        // And the recency half is untouched: a line written just now still
        // lights it, whoever wrote it.
        val spoken = sessions.findById(session).orElseThrow()
        spoken.lastEventAt = java.time.OffsetDateTime.now()
        sessions.save(spoken)
        assertThat(list().single { it["key"] == "issue:77" }["active"]).isEqualTo(true)
    }

    /** The detail page: searched, filtered by kind, and turned round. */
    @Test
    fun `a transcript is searched, filtered and reversed`() {
        val session = recorder.open(workspaceId, "issue", "42")
        recorder.userSaid(session, "Ask reviewer", "Why is the reply late?")
        recorder.toolCalled(session, "skill_load", """{"name":"codeReview"}""")
        recorder.agentSaid(session, "Reviewer", "The database was the cause.")

        assertThat(events(session, search = "database").map { it["kind"] }).containsExactly("AGENT")
        // The actor is searched too, which is how somebody finds every call to
        // one tool in a long session.
        assertThat(events(session, search = "skill_load").map { it["kind"] }).containsExactly("TOOL")

        // Newest first, which is this list's order everywhere now.
        assertThat(events(session, kinds = "[TOOL, AGENT]").map { it["kind"] })
            .containsExactly("AGENT", "TOOL")
        // No kinds is every kind: a page that has cleared its checkboxes is
        // asking for everything, not for nothing.
        assertThat(events(session, kinds = "[]")).hasSize(3)

        // Newest first is the default; oldest first is what the press asks for.
        assertThat(events(session).map { it["kind"] })
            .containsExactly("AGENT", "TOOL", "USER")
        assertThat(events(session, ascending = true).map { it["kind"] })
            .containsExactly("USER", "TOOL", "AGENT")
    }

    /**
     * Somebody who cannot see the workspace cannot read what its agents said.
     *
     * Asked by the session's own id, so the check has to be made against the
     * workspace on the row rather than one the caller names - otherwise any id
     * would be readable by anybody who can see any workspace at all.
     */
    @Test
    fun `a session in a workspace you cannot see is not there`() {
        val session = recorder.open(workspaceId, "issue", "42")
        recorder.agentSaid(session, "Reviewer", "The database was the cause.")

        asNobody {
            graphQlTester.document("{ llmSession(id: $session) { key } }")
                .execute()
                .path("llmSession")
                .valueIsNull()

            // The list refuses outright rather than answering with an empty one:
            // a workspace somebody cannot see is one they should be told nothing
            // about, including how many conversations it is not showing them.
            graphQlTester.document("{ llmSessions(workspaceId: $workspaceId) { totalElements } }")
                .execute()
                .errors()
                .satisfy { assertThat(it).isNotEmpty() }
        }
    }

    /**
     * The bug this half exists for, in one session.
     *
     * A tool is called, and what it gave back is kept and handed back as data.
     * Without it the next turn holds the model's own account of the lookup and
     * not the lookup - which is how two models on one conversation both came to
     * report issues as unlabelled that plainly carried a label, each correcting
     * itself only when it called the tool again.
     */
    @Test
    fun `what a tool returned is kept and comes back as data`() {
        val session = recorder.open(workspaceId, "issue", "42")
        val call = recorder.toolCalled(session, "orknux_issues", """{"status":"OPEN"}""")
        recorder.toolReturned(call, "#220 labels=['p1']")

        val recalled = recorder.recalled(session).single()
        // Under the role a live result already arrives under, so nothing has to
        // learn a second answer to what a result looks like.
        assertThat(recalled.role).isEqualTo("user")
        // The arguments come with it: two calls to one tool are two different
        // answers, and a result alone does not say which question it answers.
        assertThat(recalled.content).contains("orknux_issues")
        assertThat(recalled.content).contains("""{"status":"OPEN"}""")
        assertThat(recalled.content).contains("#220 labels=['p1']")

        // And none of it is offered as something anybody said. What was said is
        // the other half of a session's memory and is untouched by this.
        assertThat(recorder.remembered(session)).isEmpty()
    }

    /**
     * A call that never returned is a line to read and nothing to recall.
     *
     * The call is written before the tool runs, so one that hangs still leaves
     * the transcript saying what was asked of it. That row is worth keeping and
     * worth nothing in a prompt.
     */
    @Test
    fun `a call whose tool never answered is not recalled`() {
        val session = recorder.open(workspaceId, "issue", "42")
        recorder.toolCalled(session, "orknux_issues", "{}")

        assertThat(events.findAll().single().result).isNull()
        assertThat(recorder.recalled(session)).isEmpty()
    }

    /**
     * The same lookup twice is one answer, and the newer one is it.
     *
     * An agent that asked the same question in five rounds would otherwise
     * spend the whole allowance on five copies of one list, which is the
     * crowding-out this budget exists to prevent arriving by another route.
     */
    @Test
    fun `the same lookup made twice is recalled once, as the newer answer`() {
        val session = recorder.open(workspaceId, "issue", "42")
        recorder.toolReturned(recorder.toolCalled(session, "orknux_issue", """{"issue":"220"}"""), "labels=[]")
        recorder.toolReturned(recorder.toolCalled(session, "orknux_issue", """{"issue":"220"}"""), "labels=['p1']")

        val recalled = recorder.recalled(session).single().content
        assertThat(recalled).contains("labels=['p1']")
        assertThat(recalled).doesNotContain("labels=[]")
    }

    /**
     * A result too long to hand back whole is cut, and the cut says so.
     *
     * The failure being fixed is a model answering confidently out of something
     * it does not have. A silently shortened list is that failure again: the
     * model cannot tell it is short. Told, it can go and get the rest, so the
     * marker names the tool to call.
     */
    @Test
    fun `a result too long to keep is cut, and says asking again will not bring it back`() {
        val session = recorder.open(workspaceId, "issue", "42")
        val whole = listing(40_864)
        recorder.toolReturned(recorder.toolCalled(session, "orknux_issues", "{}"), whole)

        val recalled = requireNotNull(recorder.recalled(session).single().content)
        assertThat(recalled.length).isLessThan(whole.length)
        assertThat(recalled).contains("more characters of this answer were not kept")
        /*
         * It used to say "call orknux_issues again", which is false where the
         * cut is this one's: the tool answers in full and the recall cuts it the
         * same way, so a model that believed it asked for ever. Issue #519.
         */
        assertThat(recalled).contains("Calling orknux_issues again returns the same answer")
        assertThat(recalled).doesNotContain("Call orknux_issues again")

        // The record keeps all of it. The bound is on what a prompt may hold,
        // not on what happened - and [SessionValueTrim] leaves a listing of
        // short fields alone however long the listing is, so the two bounds do
        // not meet here.
        assertThat(events.findAll().single().result).isEqualTo(whole)
    }

    /**
     * The design, stated: two allowances rather than one.
     *
     * A single listing of a workspace's issues measured forty thousand
     * characters against twenty-four thousand for everything ever said. Sharing
     * that allowance, one lookup either takes all of it or is the first thing
     * dropped - so a conversation is not shortened by a lookup, and a lookup
     * does not have to fit inside what is left of one.
     */
    @Test
    fun `what was said and what came back are two separate allowances`() {
        val session = recorder.open(workspaceId, "issue", "42")
        recorder.userSaid(session, "alice", "Which of these carry p1?")
        recorder.toolReturned(recorder.toolCalled(session, "orknux_issues", "{}"), listing(20_000))
        recorder.agentSaid(session, "Reviewer", "None of them do.")

        assertThat(recorder.remembered(session)).hasSize(2)
        assertThat(recorder.recalled(session)).hasSize(1)
    }

    /**
     * And when there is more than fits, it is the oldest lookup that goes.
     *
     * "Check that again" is asked about the last thing that was looked up, so
     * the recall is filled from the newest backwards and read forwards.
     */
    @Test
    fun `the newest lookups survive the budget and are read oldest first`() {
        val session = recorder.open(workspaceId, "issue", "42")
        repeat(6) { page ->
            recorder.toolReturned(
                recorder.toolCalled(session, "orknux_issues", """{"page":$page}"""),
                """{"page":$page,"issues":${listing(7_000)}}""",
            )
        }

        val recalled = requireNotNull(recorder.recalled(session).single().content)
        assertThat(recalled).contains(""""page":5""").contains(""""page":4""")
        assertThat(recalled).doesNotContain(""""page":0""")
        assertThat(recalled.indexOf(""""page":4""")).isLessThan(recalled.indexOf(""""page":5"""))
    }

    /**
     * A budget narrower than the default really is narrower.
     *
     * The five numbers moved out of this class and became a share of a model's
     * context window, and the whole point of the move is that the recorder
     * reads whichever budget it is handed rather than the one it was compiled
     * with. Asserted against the same session twice, so what differs is only
     * the allowance.
     */
    @Test
    fun `a smaller budget brings back less of the same session`() {
        val session = recorder.open(workspaceId, "issue", "42")
        repeat(6) { recorder.userSaid(session, "alice", "question $it") }

        assertThat(recorder.remembered(session)).hasSize(6)
        assertThat(recorder.remembered(session, SessionMemoryBudget.of(3_000))).hasSize(3)
    }

    /** And the same for what tools gave back, which is the other allowance. */
    @Test
    fun `a smaller budget cuts a lookup that the default kept whole`() {
        val session = recorder.open(workspaceId, "issue", "42")
        recorder.toolReturned(recorder.toolCalled(session, "orknux_issues", "{}"), listing(6_000))

        assertThat(requireNotNull(recorder.recalled(session).single().content))
            .doesNotContain("more characters of this answer were not kept")
        assertThat(requireNotNull(recorder.recalled(session, SessionMemoryBudget.of(10_000)).single().content))
            .contains("more characters of this answer were not kept")
    }

    /** The transcript's page shows what came back, beside what was asked. */
    @Test
    fun `a call and what it returned are read back as one line`() {
        val session = recorder.open(workspaceId, "issue", "42")
        recorder.toolReturned(recorder.toolCalled(session, "orknux_issues", "{}"), "#220 labels=['p1']")

        val line = events(session).single()
        assertThat(line["kind"]).isEqualTo("TOOL")
        assertThat(line["content"]).isEqualTo("{}")
        assertThat(line["result"]).isEqualTo("#220 labels=['p1']")
    }

    /**
     * A lookup's answer of roughly this many characters, in fields none of
     * which is long.
     *
     * What a real listing looks like, and what the four budget tests above need
     * it to be. [SessionValueTrim] cuts any single value over
     * [SessionValueTrim.LONGEST_VALUE] out on the way into the row, so a
     * fixture of forty thousand identical characters is now a fixture of two
     * hundred and fifty and would measure the trim rather than the thing these
     * tests are about. Nothing in here is over that line, so the row keeps all
     * of it and what is being asserted is still the recall budget.
     */
    private fun listing(characters: Int): String {
        val issue = """{"id":220,"labels":["p1"],"title":"the export runs twice"}"""
        return (1..characters / (issue.length + 1) + 1).joinToString(",", "[", "]") { issue }
    }

    private fun list(search: String? = null): List<Map<String, Any?>> {
        val asked = if (search == null) "" else """, search: "$search""""
        return graphQlTester.document(
            """{ llmSessions(workspaceId: $workspaceId$asked) {
                   totalElements
                   content { id key keyPrefix eventCount createdAt lastEventAt active subagentCount }
                 } }""",
        ).execute()
            .path("llmSessions.content")
            .entity(object : ParameterizedTypeReference<List<Map<String, Any?>>>() {})
            .get()
    }

    private fun events(
        session: Long,
        search: String? = null,
        kinds: String? = null,
        ascending: Boolean? = null,
    ): List<Map<String, Any?>> {
        val asked = buildString {
            if (search != null) append(""", search: "$search"""")
            if (kinds != null) append(", kinds: $kinds")
            if (ascending != null) append(", ascending: $ascending")
        }
        return graphQlTester.document(
            """{ llmSessionEvents(sessionId: $session$asked) {
                   totalElements
                   content { id kind actor content result at }
                 } }""",
        ).execute()
            .path("llmSessionEvents.content")
            .entity(object : ParameterizedTypeReference<List<Map<String, Any?>>>() {})
            .get()
    }

    /**
     * The same request from somebody holding nothing.
     *
     * A workspace with no roles is administrators only, so an authenticated
     * caller with no authorities is exactly the person this must refuse.
     */
    private fun <T> asNobody(block: () -> T): T {
        val held = SecurityContextHolder.getContext().authentication
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("mallory", "n/a", emptyList())
        try {
            return block()
        } finally {
            SecurityContextHolder.getContext().authentication = held
        }
    }
}
