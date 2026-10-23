package io.mszymanski.orknux.server.rating

import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.usage.PromptSource
import io.mszymanski.orknux.server.usage.UsageReportAPI
import io.mszymanski.orknux.server.usage.UsageReportFilter
import io.mszymanski.orknux.server.usage.UsageTrackingView
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Admin > Reports > Ratings: the reviews agents sent on through
 * [RatingTools.RATE], and the switch that offers the tool.
 *
 * Filtered by the usage report's own filter, so switching between the two
 * reports keeps the period, the workspace and the source. Read whole and paged
 * here, as that report is: an installation's reviews are a list a person
 * reads, not a ledger of every call.
 */
@Controller
class SessionRatingAPI(
    private val settings: InstallationSettings,
    private val access: WorkspaceAccess,
    private val audit: WorkspaceAuditRecorder,
    private val ratings: SessionRatingRepository,
    private val sessions: LlmSessionRepository,
    private val workspaces: WorkspaceRepository,
    private val tools: RatingTools,
    private val chats: io.mszymanski.orknux.server.chat.ChatService,
    private val ownership: io.mszymanski.orknux.server.chat.ChatOwnership,
    private val agents: io.mszymanski.orknux.server.agent.AgentRepository,
    private val models: io.mszymanski.orknux.connector.model.LlmModelRepository,
    private val usage: io.mszymanski.orknux.server.usage.UsageTracking,
    private val recorder: io.mszymanski.orknux.server.llm.LlmSessionRecorder,
) {

    /** Whether a review can be sent at all, for the chat's menu: anybody signed in. */
    @QueryMapping
    fun sessionReviewsOffered(): Boolean = settings.chatReviews()

    @QueryMapping
    fun chatReviewsEnabled(): UsageTrackingView {
        access.requireAdmin()
        return switchView(settings.chatReviews(), settings.chatReviewsChanged())
    }

    @MutationMapping
    fun setChatReviews(@Argument enabled: Boolean): UsageTrackingView {
        access.requireAdmin()
        settings.setChatReviews(enabled, SecurityContextHolder.getContext().authentication?.name ?: "system")
        audit.record(null, WorkspaceAuditCategory.WORKSPACE, if (enabled) "Chat reviews turned on" else "Chat reviews turned off")
        return switchView(settings.chatReviews(), settings.chatReviewsChanged())
    }

    /**
     * A review the person sends from their own chat, without asking the agent
     * to pass it on: the same switch, scale and limit as rate_session, booked
     * to them and to the agent the chat is with.
     */
    @MutationMapping
    fun sendChatReview(@Argument chatId: Long, @Argument score: Int, @Argument review: String?): Boolean {
        val chat = chats.session(chatId) ?: throw io.mszymanski.orknux.server.chat.ChatSessionNotFoundException(chatId)
        ownership.requireOwn(chat)
        val session = chats.reviewSession(chat) ?: throw ReviewRefusedException("This chat has had no answer to review yet.")
        val with = chat.agentId?.let { agents.findById(it).orElse(null)?.name }
            ?: chat.modelId?.let { models.findById(it).orElse(null)?.name }
            ?: ""
        val who = usage.named(
            io.mszymanski.orknux.server.usage.Prompter(
                PromptSource.ORKNUX,
                SecurityContextHolder.getContext().authentication?.name ?: "system",
            ),
        )
        val sent = tools.send(session, with, who, score, review, fromChat = true)
        // In the session too, where rate_session's call already shows; a review from the menu had no line there.
        recorder.note(session, "${who.name ?: who.key} reviewed this chat: ${sent.score} / 5" + (sent.review?.let { " - $it" } ?: ""))
        return true
    }

    @QueryMapping
    fun chatReviews(@Argument chatId: Long): List<ChatReviewView> {
        val chat = chats.session(chatId) ?: throw io.mszymanski.orknux.server.chat.ChatSessionNotFoundException(chatId)
        ownership.requireOwn(chat)
        val session = chat.llmSessionId ?: return emptyList()
        return ratings.findBySessionIdOrderByAtAsc(session).map {
            ChatReviewView(
                requireNotNull(it.id), it.at.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), it.score, it.review, it.personName ?: it.personKey,
                it.withdrawnAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            )
        }
    }

    @QueryMapping
    fun sessionRatingsEnabled(): UsageTrackingView {
        access.requireAdmin()
        return switchView(settings.sessionRatings(), settings.sessionRatingsChanged())
    }

    @MutationMapping
    fun setSessionRatings(@Argument enabled: Boolean): UsageTrackingView {
        access.requireAdmin()
        settings.setSessionRatings(enabled, SecurityContextHolder.getContext().authentication?.name ?: "system")
        audit.record(null, WorkspaceAuditCategory.WORKSPACE, if (enabled) "Session ratings turned on" else "Session ratings turned off")
        return switchView(settings.sessionRatings(), settings.sessionRatingsChanged())
    }

    @QueryMapping
    @Transactional(readOnly = true)
    fun sessionRatings(
        @Argument filter: UsageReportFilter?,
        @Argument page: Int?,
        @Argument size: Int?,
        @Argument order: String?,
        @Argument ascending: Boolean?,
    ): SessionRatingReportView {
        access.requireAdmin()
        val period = UsageReportAPI.Period.of(filter)
        val all = ratings.findAll(ratingFilter(period.from, period.to, filter?.workspaceId, filter?.source, filter?.search))
        val at = (page ?: 0).coerceAtLeast(0)
        val take = (size ?: DEFAULT_PAGE).coerceIn(1, MAX_PAGE)
        val shown = arranged(all, order, ascending).drop(at * take).take(take)
        val held = sessions.findAllById(shown.map { it.sessionId }.toSet()).associateBy { it.id }
        val places = workspaces.findAllById(shown.map { it.workspaceId }.toSet()).associate { it.id to it.name }
        return SessionRatingReportView(
            total = all.size,
            average = all.takeIf { it.isNotEmpty() }?.map { it.score }?.average(),
            counts = (RatingTools.LOWEST..RatingTools.HIGHEST).map { score -> all.count { it.score == score } },
            days = daysOf(all, period),
            rows = shown.map { one ->
                SessionRatingView(
                    id = one.id!!,
                    at = one.at.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    score = one.score,
                    review = one.review,
                    sessionId = one.sessionId,
                    // Named as the usage report names a session; null for one that is gone.
                    sessionTitle = held[one.sessionId]?.let { it.title ?: it.sessionKey },
                    workspaceId = one.workspaceId,
                    workspaceName = places[one.workspaceId],
                    agentName = one.agentName,
                    source = one.source,
                    personKey = one.personKey,
                    personName = one.personName,
                )
            },
        )
    }

    /** Every day of the period, a quiet one included: how many gave each score, and the day's mean. */
    private fun daysOf(all: List<SessionRating>, period: UsageReportAPI.Period): List<RatingDayView> {
        val zone = ZoneId.systemDefault()
        val byDay = all.groupBy { it.at.atZoneSameInstant(zone).toLocalDate() }
        val first = period.from.atZoneSameInstant(zone).toLocalDate()
        val last = period.to.atZoneSameInstant(zone).toLocalDate().minusDays(1)
        return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.map { day ->
            val held = byDay[day].orEmpty()
            RatingDayView(
                day = day.toString(),
                counts = (RatingTools.LOWEST..RatingTools.HIGHEST).map { score -> held.count { it.score == score } },
                average = held.takeIf { it.isNotEmpty() }?.map { it.score }?.average(),
            )
        }.toList()
    }

    private fun arranged(rows: List<SessionRating>, order: String?, ascending: Boolean?): List<SessionRating> {
        val key: (SessionRating) -> Comparable<*>? = when (order?.uppercase()) {
            "SCORE" -> { one -> one.score }
            "PERSON" -> { one -> (one.personName ?: one.personKey).lowercase() }
            "AGENT" -> { one -> one.agentName.lowercase() }
            "SESSION" -> { one -> one.sessionId }
            else -> { one -> one.at }
        }
        @Suppress("UNCHECKED_CAST")
        val comparator = compareBy<SessionRating, Comparable<Any>?>(nullsFirst()) { key(it) as Comparable<Any>? }
            .thenBy { it.id }
        val up = ascending ?: (order?.uppercase() in setOf("PERSON", "AGENT"))
        return rows.sortedWith(if (up) comparator else comparator.reversed())
    }

    private fun switchView(enabled: Boolean, changed: io.mszymanski.orknux.server.attachment.InstallationSetting?): UsageTrackingView {
        return UsageTrackingView(
            enabled = enabled,
            changedAt = changed?.lastModifiedAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            changedBy = changed?.lastModifiedBy?.takeIf { it.isNotBlank() },
        )
    }

    private companion object {
        const val DEFAULT_PAGE = 25
        const val MAX_PAGE = 200
    }
}

data class SessionRatingReportView(
    val total: Int,
    val average: Double?,
    /** How many gave each score, lowest first. */
    val counts: List<Int>,
    /** Every day of the period, oldest first. */
    val days: List<RatingDayView>,
    val rows: List<SessionRatingView>,
)

/** One day of the ratings chart: how many gave each score from 1 to 5, and the mean; null on a day with none. */
data class RatingDayView(val day: String, val counts: List<Int>, val average: Double?)

data class SessionRatingView(
    val id: Long,
    val at: String,
    val score: Int,
    val review: String?,
    val sessionId: Long,
    val sessionTitle: String?,
    val workspaceId: Long,
    val workspaceName: String?,
    val agentName: String,
    val source: PromptSource,
    val personKey: String,
    val personName: String?,
)

data class ChatReviewView(
    val id: Long,
    val at: String,
    val score: Int,
    val review: String?,
    val personName: String?,
    /** When it was taken back, or null while it stands. */
    val withdrawnAt: String? = null,
)
