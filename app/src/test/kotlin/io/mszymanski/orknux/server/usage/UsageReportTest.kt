package io.mszymanski.orknux.server.usage

import io.mszymanski.orknux.connector.connection.SlackDirectory
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.SessionSpend
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal

/**
 * Admin > Reports: every model call booked to whoever prompted the turn it ran
 * in - two people in one session are two people - and nothing written at all
 * while tracking is off, which it is until an administrator turns it on.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class UsageReportTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val spend: SessionSpend,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val mapper: ObjectMapper,
    @Autowired val llmSessions: LlmSessionRepository,
    @Autowired val ledger: PersonUsageRepository,
) {

    /** Slack itself, which a test has no token for: what a member is called is what a test says. */
    @MockitoBean
    private lateinit var slack: SlackDirectory

    private var workspaceId: Long = 0
    private var session: Long = 0

    @BeforeEach
    fun make() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "usage-${System.nanoTime()}")).id)
        session = recorder.open(workspaceId, "test", "usage-${System.nanoTime()}")
    }

    @AfterEach
    fun off() = track(false)

    private fun track(enabled: Boolean) {
        graphQlTester.document("mutation { setUsageTracking(enabled: $enabled) { enabled } }").execute()
            .path("setUsageTracking.enabled").entity(Boolean::class.java).isEqualTo(enabled)
    }

    /** One model call into [into], prompted by [who]. The model is none there is, so it is unpriced. */
    private fun call(who: Prompter, input: Long, output: Long, into: Long = session) = Prompting.by(who) {
        spend.add(into, UNPRICED_MODEL, ChatCompletion.Answered("ok", 10, inputTokens = input, outputTokens = output))
    }

    private fun report() = graphQlTester.document(
        """query { usageReport(filter: { workspaceId: $workspaceId }) {
             inputTokens outputTokens people sessions unattributedTokens total partlyUnpriced
             rows { key source personKey name sessions inputTokens outputTokens }
           } }""",
    ).execute()

    @Test
    fun `tracking is off until an administrator turns it on, and off records nothing`() {
        graphQlTester.document("query { usageTracking { enabled } }").execute()
            .path("usageTracking.enabled").entity(Boolean::class.java).isEqualTo(false)

        call(Prompter(PromptSource.ORKNUX, "alice"), 1_000, 100)

        report().path("usageReport.total").entity(Int::class.java).isEqualTo(0)
    }

    @Test
    fun `two people in one session are two rows, each with their own share of it`() {
        track(true)
        val slack = Prompter(PromptSource.SLACK, "U04SLACKER", connectionId = 7, name = "Sam")
        val orknux = Prompter(PromptSource.ORKNUX, "alice", name = "Alice")
        call(slack, 6_000, 300)
        call(orknux, 3_000, 200)
        call(slack, 1_000, 100)
        call(Prompter.NOBODY, 500, 0)

        val read = report()
        read.path("usageReport.total").entity(Int::class.java).isEqualTo(3)
        read.path("usageReport.people").entity(Int::class.java).isEqualTo(2)
        read.path("usageReport.sessions").entity(Int::class.java).isEqualTo(1)
        read.path("usageReport.inputTokens").entity(Double::class.java).isEqualTo(10_500.0)
        read.path("usageReport.unattributedTokens").entity(Double::class.java).isEqualTo(500.0)
        read.path("usageReport.partlyUnpriced").entity(Boolean::class.java).isEqualTo(true)
        // Biggest first.
        read.path("usageReport.rows[*].source").entityList(String::class.java).containsExactly("SLACK", "ORKNUX", "NONE")
        read.path("usageReport.rows[0].name").entity(String::class.java).isEqualTo("Sam")
        read.path("usageReport.rows[0].inputTokens").entity(Double::class.java).isEqualTo(7_000.0)
        read.path("usageReport.rows[0].key").entity(String::class.java).isEqualTo("SLACK::U04SLACKER")

        val shares = graphQlTester.document(
            """query { usagePersonSessions(person: "SLACK::U04SLACKER", filter: { workspaceId: $workspaceId }) {
                 total items { sessionId tokens sessionTokens others cost partlyUnpriced lastActiveAt }
               } }""",
        ).execute()
        shares.path("usagePersonSessions.total").entity(Int::class.java).isEqualTo(1)
        shares.path("usagePersonSessions.items[0].tokens").entity(Double::class.java).isEqualTo(7_400.0)
        shares.path("usagePersonSessions.items[0].sessionTokens").entity(Double::class.java).isEqualTo(11_100.0)
        shares.path("usagePersonSessions.items[0].others").entityList(String::class.java)
            .containsExactly("Alice (Orknux)", "Unattributed")
        // When they last prompted it, and what their calls there cost - none priced here.
        shares.path("usagePersonSessions.items[0].lastActiveAt").entity(String::class.java).satisfies {
            assertThat(java.time.OffsetDateTime.parse(it)).isAfter(java.time.OffsetDateTime.now().minusMinutes(5))
        }
        shares.path("usagePersonSessions.items[0].partlyUnpriced").entity(Boolean::class.java).isEqualTo(true)
        shares.path("usagePersonSessions.items[0].cost").valueIsNull()
    }

    /**
     * An asked agent's session is booked to whoever prompted the conversation
     * that asked it. Its calls run on a thread of their own, or are woken later
     * with nobody on it, and were all booked to nobody.
     */
    @Test
    fun `a subagent's calls with nobody prompting them are booked to whoever prompted above it`() {
        track(true)
        val slack = Prompter(PromptSource.SLACK, "U04SLACKER", connectionId = 7, name = "Sam")
        call(slack, 1_000, 100)
        val asked = recorder.openUnder(session, "Find the answer")
        val below = recorder.openUnder(asked, "And below that")

        spend.add(asked, UNPRICED_MODEL, ChatCompletion.Answered("ok", 10, inputTokens = 2_000, outputTokens = 50))
        spend.add(below, UNPRICED_MODEL, ChatCompletion.Answered("ok", 10, inputTokens = 500, outputTokens = 5))

        val read = report()
        read.path("usageReport.rows[*].source").entityList(String::class.java).contains("SLACK")
        read.path("usageReport.rows[0].inputTokens").entity(Double::class.java).isEqualTo(3_500.0)
        read.path("usageReport.unattributedTokens").entity(Double::class.java).isEqualTo(0.0)
    }

    @Test
    fun `the report is searched by person and put in a column's order`() {
        track(true)
        call(Prompter(PromptSource.SLACK, "U04SLACKER", connectionId = 7, name = "Sam"), 6_000, 300)
        call(Prompter(PromptSource.ORKNUX, "alice", name = "Alice"), 3_000, 200)
        call(Prompter.NOBODY, 500, 0)

        graphQlTester.document("""query { usageReport(filter: { workspaceId: $workspaceId, search: "sam" }) { total inputTokens rows { name } } }""").execute()
            .path("usageReport.total").entity(Int::class.java).isEqualTo(1)
            .path("usageReport.inputTokens").entity(Double::class.java).isEqualTo(6_000.0)
        graphQlTester.document("""query { usageReport(filter: { workspaceId: $workspaceId, search: "unattr" }) { total rows { source } } }""").execute()
            .path("usageReport.rows[0].source").entity(String::class.java).isEqualTo("NONE")
        graphQlTester.document("""query { usageReport(filter: { workspaceId: $workspaceId }, order: "NAME", ascending: true) { rows { source name } } }""").execute()
            .path("usageReport.rows[*].source").entityList(String::class.java).containsExactly("ORKNUX", "SLACK", "NONE")
        graphQlTester.document("""query { usageReport(filter: { workspaceId: $workspaceId }, order: "INPUT", ascending: true) { rows { inputTokens } } }""").execute()
            .path("usageReport.rows[0].inputTokens").entity(Double::class.java).isEqualTo(500.0)
    }

    /** Ordered across every one of their sessions before the page is cut, not within the page. */
    @Test
    fun `a person's sessions are put in a column's order across pages`() {
        track(true)
        val sam = Prompter(PromptSource.SLACK, "U04SLACKER", connectionId = 7, name = "Sam")
        val other = recorder.open(workspaceId, "test", "usage-order-${System.nanoTime()}")
        call(sam, 4_000, 0)
        call(sam, 1_000, 0, into = other)
        call(Prompter(PromptSource.ORKNUX, "alice", name = "Alice"), 9_000, 0, into = other)

        fun ids(order: String?, ascending: Boolean?, page: Int = 0, size: Int = 5) = graphQlTester.document(
            """query { usagePersonSessions(person: "SLACK::U04SLACKER", filter: { workspaceId: $workspaceId },
                 page: $page, size: $size, order: ${order?.let { "\"$it\"" }}, ascending: $ascending) { total items { sessionId } } }""",
        ).execute().path("usagePersonSessions.items[*].sessionId").entityList(Long::class.java)

        ids(null, null).containsExactly(session, other)
        ids("SHARE", true).containsExactly(other, session)
        ids("SESSION", null).containsExactly(session, other)
        ids("SESSION", false).containsExactly(other, session)
        // The second page of one holds the smaller share, which a sort of the page alone would never reach.
        ids("SHARE", false, page = 1, size = 1).containsExactly(other)
    }

    /**
     * Every workspace has a Slack connection of its own, often to one Slack, so
     * a member who wrote in two workspaces arrived through two connections and
     * was drawn as two rows with the same id. They are one person.
     */
    @Test
    fun `a Slack member who came through two connections is one person`() {
        track(true)
        val other = recorder.open(workspaceId, "test", "usage-other-${System.nanoTime()}")
        call(Prompter(PromptSource.SLACK, "U01PKNA3DNZ", connectionId = 7, name = "Jakub"), 4_000, 100)
        call(Prompter(PromptSource.SLACK, "U01PKNA3DNZ", connectionId = 8, name = "Jakub"), 2_000, 50, into = other)

        val read = report()
        read.path("usageReport.total").entity(Int::class.java).isEqualTo(1)
        read.path("usageReport.people").entity(Int::class.java).isEqualTo(1)
        read.path("usageReport.rows[0].key").entity(String::class.java).isEqualTo("SLACK::U01PKNA3DNZ")
        read.path("usageReport.rows[0].sessions").entity(Int::class.java).isEqualTo(2)
        read.path("usageReport.rows[0].inputTokens").entity(Double::class.java).isEqualTo(6_000.0)

        graphQlTester.document(
            """query { usagePersonSessions(person: "SLACK::U01PKNA3DNZ", filter: { workspaceId: $workspaceId }) {
                 total items { sessionId others }
               } }""",
        ).execute()
            .path("usagePersonSessions.total").entity(Int::class.java).isEqualTo(2)
            .path("usagePersonSessions.items[*].sessionId").entityList(Long::class.java).containsExactly(session, other)
            .path("usagePersonSessions.items[0].others").entityList(String::class.java).hasSize(0)
        // A link written while a member was one row per connection still opens that connection's share.
        graphQlTester.document(
            """query { usagePersonSessions(person: "SLACK:8:U01PKNA3DNZ", filter: { workspaceId: $workspaceId }) { total } }""",
        ).execute().path("usagePersonSessions.total").entity(Int::class.java).isEqualTo(1)
    }

    /**
     * A name was asked for once, when a call was booked, and a lookup that
     * failed then - and was remembered for an hour - left the member as `U…`
     * in the report for good. The report asks again.
     */
    @Test
    fun `a Slack member Slack could not name when booked is named when the report is read`() {
        track(true)
        call(Prompter(PromptSource.SLACK, "UM0LLMKN3", connectionId = 9), 1_000, 10)
        org.mockito.Mockito.`when`(slack.memberName(9L, "UM0LLMKN3")).thenReturn("Marta")

        report().path("usageReport.rows[0].name").entity(String::class.java).isEqualTo("Marta")
    }

    /**
     * Only a session an agent started from another has a title (#379), so the
     * report called every other one removed while it was there to be opened.
     */
    @Test
    fun `a session is called removed only once it is gone`() {
        track(true)
        val gone = recorder.open(workspaceId, "test", "usage-gone-${System.nanoTime()}")
        call(Prompter(PromptSource.ORKNUX, "titles"), 2_000, 0)
        call(Prompter(PromptSource.ORKNUX, "titles"), 1_000, 0, into = gone)
        llmSessions.deleteById(gone)

        graphQlTester.document(
            """query { usagePersonSessions(person: "ORKNUX::titles", filter: { workspaceId: $workspaceId }) {
                 items { sessionId title }
               } }""",
        ).execute()
            .path("usagePersonSessions.items[0].title").entity(String::class.java)
            .isEqualTo(llmSessions.findById(session).orElseThrow().sessionKey)
            .path("usagePersonSessions.items[1].title").valueIsNull()
    }

    @Test
    fun `a person's sessions come a page at a time, biggest first`() {
        track(true)
        val who = Prompter(PromptSource.ORKNUX, "pager", name = "Pager")
        val sessions = (1..7).map { n ->
            recorder.open(workspaceId, "test", "page-$n-${System.nanoTime()}").also { call(who, n * 100L, 0, into = it) }
        }

        val second = graphQlTester.document(
            """query { usagePersonSessions(person: "ORKNUX::pager", filter: { workspaceId: $workspaceId }, page: 1, size: 5) {
                 total items { sessionId tokens }
               } }""",
        ).execute()
        second.path("usagePersonSessions.total").entity(Int::class.java).isEqualTo(7)
        second.path("usagePersonSessions.items[*].sessionId").entityList(Long::class.java)
            .containsExactly(sessions[1], sessions[0])
    }

    @Test
    fun `switching off stops recording and keeps what was recorded`() {
        track(true)
        call(Prompter(PromptSource.ORKNUX, "alice"), 1_000, 100)
        track(false)
        call(Prompter(PromptSource.ORKNUX, "alice"), 9_000, 900)

        val read = report()
        read.path("usageReport.total").entity(Int::class.java).isEqualTo(1)
        read.path("usageReport.inputTokens").entity(Double::class.java).isEqualTo(1_000.0)
        graphQlTester.document("query { usageTracking { enabled changedAt changedBy } }").execute()
            .path("usageTracking.changedBy").entity(String::class.java).isEqualTo("alice")
    }

    @Test
    fun `a run is booked to the Slack member whose message started it, and a webhook to nobody`() {
        val slack = """{"action":"MENTION","text":"hi","user":"U04SLACKER","connection":"12","channel":"C1"}"""
        assertThat(Prompter.ofTrigger(slack, mapper)).isEqualTo(Prompter(PromptSource.SLACK, "U04SLACKER", connectionId = 12))

        val webhook = """{"webhook":{"path":"x"},"action":"MENTION","user":"U04SLACKER","connection":"12"}"""
        assertThat(Prompter.ofTrigger(webhook, mapper)).isEqualTo(Prompter.NOBODY)
        assertThat(Prompter.ofTrigger("""{"cron":"0 * * * *"}""", mapper)).isEqualTo(Prompter.NOBODY)
        assertThat(Prompter.ofTrigger(null, mapper)).isEqualTo(Prompter.NOBODY)
    }

    /** A ledger row written straight, so a test can say which day it fell on and what it cost. */
    private fun booked(
        on: java.time.LocalDate,
        who: String,
        input: Long,
        cached: Long,
        output: Long,
        cost: String?,
        name: String? = null,
        inSession: Long = session,
    ) = ledger.save(
        PersonUsage(
            at = on.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime(),
            workspaceId = workspaceId,
            sessionId = inSession,
            modelId = UNPRICED_MODEL,
            source = PromptSource.ORKNUX,
            connectionId = null,
            personKey = who,
            personName = name,
            inputTokens = input,
            cachedInputTokens = cached,
            outputTokens = output,
            cost = cost?.let(::BigDecimal) ?: BigDecimal.ZERO,
            unpriced = cost == null,
        ),
    )

    private fun series(from: java.time.LocalDate, to: java.time.LocalDate, search: String? = null) = graphQlTester.document(
        """query { usageSeries(filter: { workspaceId: $workspaceId, from: "$from", to: "$to"${search?.let { ", search: \"$it\"" } ?: ""} }) {
             day inputTokens cachedInputTokens outputTokens cost partlyUnpriced sessions calls
           } }""",
    ).execute()

    /**
     * The chart over the table: each day of the period, the quiet ones as
     * zeros so the spacing is the calendar's, with the cached part of the
     * input and the cost summed per day.
     */
    @Test
    fun `the series is every day of the period, each summed on its own`() {
        val first = java.time.LocalDate.now().minusDays(3)
        booked(first, "daily", 1_000, 400, 100, "0.25")
        booked(first, "daily", 500, 0, 50, "0.5")
        booked(first.plusDays(2), "daily", 2_000, 2_000, 10, null)

        val read = series(first, first.plusDays(3))
        read.path("usageSeries[*].day").entityList(String::class.java)
            .containsExactly(first.toString(), first.plusDays(1).toString(), first.plusDays(2).toString(), first.plusDays(3).toString())
        read.path("usageSeries[0].inputTokens").entity(Double::class.java).isEqualTo(1_500.0)
        read.path("usageSeries[0].cachedInputTokens").entity(Double::class.java).isEqualTo(400.0)
        read.path("usageSeries[0].outputTokens").entity(Double::class.java).isEqualTo(150.0)
        read.path("usageSeries[0].cost").entity(String::class.java).isEqualTo("0.75")
        read.path("usageSeries[0].partlyUnpriced").entity(Boolean::class.java).isEqualTo(false)
        read.path("usageSeries[1].inputTokens").entity(Double::class.java).isEqualTo(0.0)
        read.path("usageSeries[1].cost").valueIsNull()
        read.path("usageSeries[2].cachedInputTokens").entity(Double::class.java).isEqualTo(2_000.0)
        read.path("usageSeries[2].cost").valueIsNull()
        read.path("usageSeries[2].partlyUnpriced").entity(Boolean::class.java).isEqualTo(true)
    }

    /** Beside the tokens and the cost, how busy each day was: sessions that made a call, and the calls. */
    @Test
    fun `the series counts each day's sessions and model calls`() {
        val day = java.time.LocalDate.now().minusDays(1)
        booked(day, "busy", 10, 0, 1, "0.1")
        booked(day, "busy", 10, 0, 1, "0.1")
        booked(day, "busy", 10, 0, 1, "0.1", inSession = session + 1_000_000)

        series(day, day.plusDays(1))
            .path("usageSeries[0].sessions").entity(Int::class.java).isEqualTo(2)
            .path("usageSeries[0].calls").entity(Int::class.java).isEqualTo(3)
            .path("usageSeries[1].sessions").entity(Int::class.java).isEqualTo(0)
            .path("usageSeries[1].calls").entity(Int::class.java).isEqualTo(0)
    }

    /** The search narrows the chart as it narrows the totals beside it: to the people it matches. */
    @Test
    fun `the series follows the search to the people it matches`() {
        val day = java.time.LocalDate.now().minusDays(1)
        booked(day, "kept", 1_000, 0, 10, "1", name = "Kept Person")
        booked(day, "other", 9_000, 0, 90, "9", name = "Somebody Else")

        series(day, day, search = "kept").path("usageSeries[0].inputTokens").entity(Double::class.java).isEqualTo(1_000.0)
            .path("usageSeries[0].cost").entity(String::class.java).isEqualTo("1")
        series(day, day, search = "nobody-at-all").path("usageSeries[0].inputTokens").entity(Double::class.java).isEqualTo(0.0)
        series(day, day).path("usageSeries[0].inputTokens").entity(Double::class.java).isEqualTo(10_000.0)
    }

    private companion object {
        const val UNPRICED_MODEL = 987_654_321L
    }
}
