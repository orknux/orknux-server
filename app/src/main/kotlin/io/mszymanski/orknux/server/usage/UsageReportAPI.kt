package io.mszymanski.orknux.server.usage

import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import jakarta.persistence.EntityManager
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Admin > Reports: tokens by the person who prompted them, out of the ledger
 * [UsageTracking] writes.
 *
 * Summed in the database and paged here. The number of people an installation
 * has is small enough to hold, and paging a GROUP BY by offset would need a
 * second query to count the groups that a list in memory already knows.
 */
@Controller
class UsageReportAPI(
    private val settings: InstallationSettings,
    private val access: WorkspaceAccess,
    private val audit: WorkspaceAuditRecorder,
    private val entities: EntityManager,
    private val sessions: LlmSessionRepository,
    private val workspaces: WorkspaceRepository,
    private val connections: WorkspaceConnectionRepository,
    private val tracking: UsageTracking,
) {

    @QueryMapping
    fun usageTracking(): UsageTrackingView {
        access.requireAdmin()
        return trackingView()
    }

    @MutationMapping
    fun setUsageTracking(@Argument enabled: Boolean): UsageTrackingView {
        access.requireAdmin()
        settings.setUsageTracking(enabled, SecurityContextHolder.getContext().authentication?.name ?: "system")
        audit.record(null, WorkspaceAuditCategory.WORKSPACE, if (enabled) "Usage tracking turned on" else "Usage tracking turned off")
        return trackingView()
    }

    @QueryMapping
    @Transactional(readOnly = true)
    fun usageReport(
        @Argument filter: UsageReportFilter?,
        @Argument page: Int?,
        @Argument size: Int?,
        @Argument order: String?,
        @Argument ascending: Boolean?,
    ): UsageReportView {
        access.requireAdmin()
        val period = Period.of(filter)
        // The search is over the person alone - who they are, not what they used - and the totals follow it.
        val people = arranged(people(period, filter).filter { matches(it, filter?.search) }, order, ascending)
        val (at, take) = paging(page, size, DEFAULT_PEOPLE)
        val named = connections.findAllById(people.flatMap { it.connectionIds }.toSet()).associate { it.id to it.name }
        return UsageReportView(
            inputTokens = people.sumOf { it.input }.toDouble(),
            cachedInputTokens = people.sumOf { it.cached }.toDouble(),
            outputTokens = people.sumOf { it.output }.toDouble(),
            cost = costOf(people.fold(BigDecimal.ZERO) { sum, one -> sum + one.cost }),
            partlyUnpriced = people.any { it.unpriced },
            people = people.count { it.source != PromptSource.NONE },
            sessions = sessionCount(period, filter),
            unattributedTokens = people.filter { it.source == PromptSource.NONE }.sumOf { it.input + it.output }.toDouble(),
            total = people.size,
            rows = people.drop(at * take).take(take).map { one ->
                UsagePersonView(
                    key = one.key,
                    source = one.source,
                    personKey = one.personKey,
                    name = one.name ?: nameNow(one),
                    connectionName = one.connectionIds.mapNotNull { named[it] }.distinct().joinToString(", ").ifEmpty { null },
                    sessions = one.sessions,
                    inputTokens = one.input.toDouble(),
                    cachedInputTokens = one.cached.toDouble(),
                    outputTokens = one.output.toDouble(),
                    cost = costOf(one.cost),
                    partlyUnpriced = one.unpriced,
                    lastActiveAt = one.lastAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                )
            },
        )
    }

    @QueryMapping
    @Transactional(readOnly = true)
    fun usagePersonSessions(
        @Argument person: String,
        @Argument filter: UsageReportFilter?,
        @Argument page: Int?,
        @Argument size: Int?,
    ): UsagePersonSessionsView {
        access.requireAdmin()
        val period = Period.of(filter)
        val who = PersonKey.parse(person) ?: return UsagePersonSessionsView(0, emptyList())
        val (where, parameters) = conditions(period, filter)
        val mine = query(
            // A connection in the key is a link written before a Slack member was
            // one person across connections; it still narrows to that one.
            "SELECT u.sessionId, SUM(u.inputTokens + u.outputTokens) FROM PersonUsage u WHERE $where " +
                "AND u.source = :who AND u.personKey = :key " +
                (if (who.connectionId == null) "" else "AND u.connectionId = :connection ") +
                "GROUP BY u.sessionId",
            parameters + buildMap {
                put("who", who.source)
                put("key", who.personKey)
                who.connectionId?.let { put("connection", it) }
            },
        ).map { (it[0] as Long) to (it[1] as Number).toLong() }
            .sortedByDescending { it.second }
        val (at, take) = paging(page, size, DEFAULT_SESSIONS)
        val shown = mine.drop(at * take).take(take)
        if (shown.isEmpty()) return UsagePersonSessionsView(mine.size, emptyList())

        val ids = shown.map { it.first }
        // What they spent in each, and when they last prompted it, beside the tokens.
        val spentIn = query(
            "SELECT u.sessionId, SUM(u.cost), MAX(CASE WHEN u.unpriced = true THEN 1 ELSE 0 END), MAX(u.at) " +
                "FROM PersonUsage u WHERE $where AND u.source = :who AND u.personKey = :key " +
                (if (who.connectionId == null) "" else "AND u.connectionId = :connection ") +
                "AND u.sessionId IN :ids GROUP BY u.sessionId",
            parameters + buildMap {
                put("who", who.source)
                put("key", who.personKey)
                who.connectionId?.let { put("connection", it) }
                put("ids", ids)
            },
        ).associateBy { it[0] as Long }
        // Everybody in those sessions over the same period, for the share and the others.
        val everybody = query(
            "SELECT u.sessionId, u.source, u.personKey, MAX(u.personName), SUM(u.inputTokens + u.outputTokens) " +
                "FROM PersonUsage u WHERE $where AND u.sessionId IN :ids " +
                "GROUP BY u.sessionId, u.source, u.personKey",
            parameters + ("ids" to ids),
        ).groupBy { it[0] as Long }
        val held = sessions.findAllById(ids).associateBy { it.id }
        // Off the rows rather than the session, which may have been removed since.
        val workspaceOf = query(
            "SELECT u.sessionId, MAX(u.workspaceId) FROM PersonUsage u WHERE u.sessionId IN :ids GROUP BY u.sessionId",
            mapOf("ids" to ids),
        ).associate { (it[0] as Long) to (it[1] as Long) }
        val places = workspaces.findAllById(workspaceOf.values.toSet()).associate { it.id to it.name }

        return UsagePersonSessionsView(
            total = mine.size,
            items = shown.map { (id, tokens) ->
                val rows = everybody[id].orEmpty()
                val workspaceId = workspaceOf[id] ?: 0L
                UsageSessionShareView(
                    sessionId = id,
                    // A session has a title only when an agent started it from
                    // another (#379); every other one is named by its key, as the
                    // session list names it. Null is kept for a session that is gone.
                    title = held[id]?.let { it.title ?: it.sessionKey },
                    workspaceId = workspaceId,
                    workspaceName = places[workspaceId],
                    tokens = tokens.toDouble(),
                    sessionTokens = rows.sumOf { (it[4] as Number).toLong() }.toDouble(),
                    cost = spentIn[id]?.let { (it[1] as BigDecimal?) ?: BigDecimal.ZERO }?.let(::costOf),
                    partlyUnpriced = spentIn[id]?.let { (it[2] as Number).toInt() == 1 } ?: false,
                    lastActiveAt = (spentIn[id]?.get(3) as OffsetDateTime?)?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    others = rows
                        .filterNot { it[1] == who.source && it[2] == who.personKey }
                        .sortedByDescending { (it[4] as Number).toLong() }
                        .map { label(it[1] as PromptSource, it[2] as String, it[3] as String?) }
                        .distinct(),
                )
            },
        )
    }

    /**
     * The report day by day, for the chart over the table: every day of the
     * period, a quiet one with zeros, so the chart's spacing is the calendar's.
     *
     * Bucketed here rather than grouped in the database. A day is a day in the
     * server's zone, which is how [Period] cuts the report, and truncating a
     * timestamp to a date is spelled differently on Postgres and SQLite and
     * truncates in the database's zone on both. The search is over the person,
     * as the table's is, so it narrows the days to the people it matches.
     */
    @QueryMapping
    @Transactional(readOnly = true)
    fun usageSeries(@Argument filter: UsageReportFilter?): List<UsageDayView> {
        access.requireAdmin()
        val period = Period.of(filter)
        val zone = ZoneId.systemDefault()
        val (where, parameters) = conditions(period, filter)
        val only = filter?.search?.takeIf { it.isNotBlank() }?.let { search ->
            people(period, filter).filter { matches(it, search) }.map { it.source to it.personKey }.toSet()
        }
        val days = LinkedHashMap<LocalDate, DayTotal>()
        var day = period.from.atZoneSameInstant(zone).toLocalDate()
        val last = period.to.atZoneSameInstant(zone).toLocalDate()
        while (day.isBefore(last)) {
            days[day] = DayTotal()
            day = day.plusDays(1)
        }
        if (only?.isEmpty() != true) {
            query(
                "SELECT u.at, u.source, u.personKey, u.inputTokens, u.cachedInputTokens, u.outputTokens, u.cost, u.unpriced " +
                    "FROM PersonUsage u WHERE $where",
                parameters,
            ).forEach {
                if (only != null && (it[1] as PromptSource to it[2] as String) !in only) return@forEach
                val total = days[(it[0] as OffsetDateTime).atZoneSameInstant(zone).toLocalDate()] ?: return@forEach
                total.input += (it[3] as Number).toLong()
                total.cached += (it[4] as Number).toLong()
                total.output += (it[5] as Number).toLong()
                total.cost += (it[6] as BigDecimal?) ?: BigDecimal.ZERO
                total.unpriced = total.unpriced || it[7] == true
            }
        }
        return days.map { (date, total) ->
            UsageDayView(
                day = date.toString(),
                inputTokens = total.input.toDouble(),
                cachedInputTokens = total.cached.toDouble(),
                outputTokens = total.output.toDouble(),
                cost = costOf(total.cost),
                partlyUnpriced = total.unpriced,
            )
        }
    }

    private class DayTotal(
        var input: Long = 0,
        var cached: Long = 0,
        var output: Long = 0,
        var cost: BigDecimal = BigDecimal.ZERO,
        var unpriced: Boolean = false,
    )

    private fun trackingView(): UsageTrackingView {
        val changed = settings.usageTrackingChanged()
        return UsageTrackingView(
            enabled = settings.usageTracking(),
            changedAt = changed?.lastModifiedAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            changedBy = changed?.lastModifiedBy?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Every person the filter matches, biggest first.
     *
     * A person is a source and an id, and not the connection a Slack member
     * came through. Each workspace has a Slack connection of its own, often to
     * the same Slack, so grouping by connection made one member as many rows as
     * workspaces they had written in - two rows reading "Jakub · Slack". A
     * Slack id is not reused between Slack workspaces, and on an Enterprise Grid
     * a member keeps one id in all of them, so the id alone is the person; the
     * connections are only where their name can be asked.
     */
    private fun people(period: Period, filter: UsageReportFilter?): List<PersonTotal> {
        val (where, parameters) = conditions(period, filter)
        val through = query(
            "SELECT DISTINCT u.personKey, u.connectionId FROM PersonUsage u WHERE $where AND u.connectionId IS NOT NULL",
            parameters,
        ).groupBy({ it[0] as String }, { it[1] as Long })
        return query(
            "SELECT u.source, u.personKey, MAX(u.personName), COUNT(DISTINCT u.sessionId), " +
                "SUM(u.inputTokens), SUM(u.cachedInputTokens), SUM(u.outputTokens), SUM(u.cost), " +
                "MAX(CASE WHEN u.unpriced = true THEN 1 ELSE 0 END), MAX(u.at) " +
                "FROM PersonUsage u WHERE $where GROUP BY u.source, u.personKey",
            parameters,
        ).map {
            val source = it[0] as PromptSource
            val personKey = it[1] as String
            PersonTotal(
                source = source,
                connectionIds = if (source == PromptSource.SLACK) through[personKey].orEmpty().sorted() else emptyList(),
                personKey = personKey,
                name = it[2] as String?,
                sessions = (it[3] as Number).toInt(),
                input = (it[4] as Number).toLong(),
                cached = (it[5] as Number).toLong(),
                output = (it[6] as Number).toLong(),
                cost = (it[7] as BigDecimal?) ?: BigDecimal.ZERO,
                unpriced = (it[8] as Number).toInt() == 1,
                lastAt = it[9] as OffsetDateTime,
            )
        }.sortedByDescending { it.input + it.output }
    }

    /**
     * A Slack member none of whose rows carries a name, asked for now through
     * each connection they came through - only for the rows on the page shown.
     */
    private fun nameNow(person: PersonTotal): String? =
        if (person.source != PromptSource.SLACK) null
        else person.connectionIds.firstNotNullOfOrNull { tracking.slackName(it, person.personKey, askAgain = true) }

    /** Whether a person answers to what was typed: their name, their id, or "unattributed" for the row with neither. */
    private fun matches(person: PersonTotal, search: String?): Boolean {
        val looking = search?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return true
        val called = if (person.source == PromptSource.NONE) "unattributed" else listOfNotNull(person.name, person.personKey).joinToString(" ")
        return called.lowercase().contains(looking)
    }

    /**
     * The rows in the order a heading asked for; tokens, biggest first, where
     * none did. A name sorts as it reads, the row with no person last among
     * names; an unpriced cost sorts as nothing.
     */
    private fun arranged(people: List<PersonTotal>, order: String?, ascending: Boolean?): List<PersonTotal> {
        val key: (PersonTotal) -> Comparable<*>? = when (order?.uppercase()) {
            "NAME" -> { one -> if (one.source == PromptSource.NONE) "\uffff" else (one.name ?: one.personKey).lowercase() }
            "SOURCE" -> { one -> one.source.name }
            "SESSIONS" -> { one -> one.sessions }
            "INPUT" -> { one -> one.input }
            "CACHED" -> { one -> one.cached }
            "OUTPUT" -> { one -> one.output }
            "COST" -> { one -> one.cost }
            "LAST_ACTIVE" -> { one -> one.lastAt }
            else -> { one -> one.input + one.output }
        }
        @Suppress("UNCHECKED_CAST")
        val comparator = compareBy<PersonTotal, Comparable<Any>?>(nullsFirst()) { key(it) as Comparable<Any>? }
        val up = ascending ?: (order?.uppercase() in setOf("NAME", "SOURCE"))
        return people.sortedWith(if (up) comparator else comparator.reversed())
    }

    private fun sessionCount(period: Period, filter: UsageReportFilter?): Int {
        val (where, parameters) = conditions(period, filter)
        return (query("SELECT COUNT(DISTINCT u.sessionId) FROM PersonUsage u WHERE $where", parameters, single = true)
            .single()[0] as Number).toInt()
    }

    /** A filter's conditions, written only for what it sets - see AGENTS.md on optional parameters. */
    private fun conditions(period: Period, filter: UsageReportFilter?): Pair<String, Map<String, Any>> {
        val parameters = mutableMapOf<String, Any>("from" to period.from, "to" to period.to)
        val where = buildList {
            add("u.at >= :from AND u.at < :to")
            filter?.workspaceId?.let {
                add("u.workspaceId = :workspace")
                parameters["workspace"] = it
            }
            filter?.source?.let {
                add("u.source = :source")
                parameters["source"] = it
            }
        }.joinToString(" AND ")
        return where to parameters
    }

    @Suppress("UNCHECKED_CAST")
    private fun query(jpql: String, parameters: Map<String, Any>, single: Boolean = false): List<Array<Any?>> {
        val asked = entities.createQuery(jpql)
        parameters.forEach { (name, value) -> asked.setParameter(name, value) }
        val rows = asked.resultList
        return if (single) rows.map { arrayOf(it) } else rows as List<Array<Any?>>
    }

    private fun paging(page: Int?, size: Int?, default: Int): Pair<Int, Int> =
        (page ?: 0).coerceAtLeast(0) to (size ?: default).coerceIn(1, MAX_PAGE)

    private fun costOf(cost: BigDecimal): String? = cost.takeIf { it.signum() > 0 }?.stripTrailingZeros()?.toPlainString()

    private fun label(source: PromptSource, key: String, name: String?): String = when (source) {
        PromptSource.NONE -> "Unattributed"
        PromptSource.SLACK -> "${name ?: key} (Slack)"
        PromptSource.ORKNUX -> "${name ?: key} (Orknux)"
    }

    /** The days a report covers: from the start of [from] to the end of [to], in the server's zone. */
    private data class Period(val from: OffsetDateTime, val to: OffsetDateTime) {
        companion object {
            fun of(filter: UsageReportFilter?): Period {
                val zone = ZoneId.systemDefault()
                val last = filter?.to?.let(LocalDate::parse) ?: LocalDate.now(zone)
                val first = filter?.from?.let(LocalDate::parse) ?: last.minusDays(29)
                return Period(
                    first.atStartOfDay(zone).toOffsetDateTime(),
                    last.plusDays(1).atStartOfDay(zone).toOffsetDateTime(),
                )
            }
        }
    }

    private data class PersonTotal(
        val source: PromptSource,
        /** Every connection a Slack member's calls came through; empty for anybody else. */
        val connectionIds: List<Long>,
        val personKey: String,
        val name: String?,
        val sessions: Int,
        val input: Long,
        val cached: Long,
        val output: Long,
        val cost: BigDecimal,
        val unpriced: Boolean,
        val lastAt: OffsetDateTime,
    ) {
        val key: String get() = PersonKey(source, null, personKey).toString()
    }

    private companion object {
        const val DEFAULT_PEOPLE = 25
        const val DEFAULT_SESSIONS = 5
        const val MAX_PAGE = 200
    }
}

/**
 * A person as one string, for the API to hand back: `SLACK::U04ABC`,
 * `ORKNUX::alice`, `NONE::`. The middle place held a Slack member's connection
 * until a member became one person across connections; a key that still names
 * one is read as that connection's share alone.
 */
data class PersonKey(val source: PromptSource, val connectionId: Long?, val personKey: String) {
    override fun toString(): String = "${source.name}:${connectionId ?: ""}:$personKey"

    companion object {
        fun parse(text: String): PersonKey? {
            val parts = text.split(":", limit = 3)
            if (parts.size != 3) return null
            val source = PromptSource.entries.firstOrNull { it.name == parts[0] } ?: return null
            val connection = parts[1].takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: return null }
            return PersonKey(source, connection, parts[2])
        }
    }
}

data class UsageReportFilter(
    val from: String? = null,
    val to: String? = null,
    val workspaceId: Long? = null,
    val source: PromptSource? = null,
    /** Over the person alone: their name or id. */
    val search: String? = null,
)

data class UsageTrackingView(val enabled: Boolean, val changedAt: String?, val changedBy: String?)

data class UsageReportView(
    val inputTokens: Double,
    val cachedInputTokens: Double,
    val outputTokens: Double,
    val cost: String?,
    val partlyUnpriced: Boolean,
    val people: Int,
    val sessions: Int,
    val unattributedTokens: Double,
    val total: Int,
    val rows: List<UsagePersonView>,
)

data class UsagePersonView(
    val key: String,
    val source: PromptSource,
    val personKey: String,
    val name: String?,
    val connectionName: String?,
    val sessions: Int,
    val inputTokens: Double,
    val cachedInputTokens: Double,
    val outputTokens: Double,
    val cost: String?,
    val partlyUnpriced: Boolean,
    val lastActiveAt: String,
)

/** One day of the report; [cachedInputTokens] is a part of [inputTokens]. */
data class UsageDayView(
    val day: String,
    val inputTokens: Double,
    val cachedInputTokens: Double,
    val outputTokens: Double,
    val cost: String?,
    val partlyUnpriced: Boolean,
)

data class UsagePersonSessionsView(val total: Int, val items: List<UsageSessionShareView>)

data class UsageSessionShareView(
    val sessionId: Long,
    val title: String?,
    val workspaceId: Long,
    val workspaceName: String?,
    val tokens: Double,
    val sessionTokens: Double,
    /** What their calls here cost, where any was priced. */
    val cost: String?,
    /** Whether some of their calls here were on a model with no price. */
    val partlyUnpriced: Boolean,
    /** When they last prompted it. */
    val lastActiveAt: String?,
    val others: List<String>,
)
