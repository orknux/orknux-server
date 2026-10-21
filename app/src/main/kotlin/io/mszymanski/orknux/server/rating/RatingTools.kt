package io.mszymanski.orknux.server.rating

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.chat.ToolShed
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.usage.Prompter
import io.mszymanski.orknux.server.usage.UsageTracking
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/**
 * An agent passing on the review the person it talks to wrote about the
 * conversation, for Admin > Reports > Ratings.
 *
 * A tool rather than a button, because most of the people an agent talks to
 * are in Slack, where there is no page to put a button on: they write "that
 * was a 4, the second answer was wrong" to the agent, and the agent is the one
 * that can hand it on.
 *
 * The review is the person's and never the agent's own view of how it went -
 * the description says so, because a model asked to grade its work grades it
 * well. Who wrote it is whoever is prompting the session, named as the usage
 * ledger names them, and not an argument: a model handed a name to fill in
 * could put any.
 *
 * Not offered where there is no session, since a review is of one, nor while
 * Admin > Reports > Ratings has it switched off.
 */
@Service
class RatingTools(
    private val ratings: SessionRatingRepository,
    private val sessions: LlmSessionRepository,
    private val usage: UsageTracking,
    private val settings: InstallationSettings,
    private val mapper: ObjectMapper,
) {

    /** Null where there is no session, or while an administrator has ratings off. */
    fun shed(agent: Agent, session: Long?): ToolShed? =
        if (session == null || !settings.sessionRatings()) null else Shed(agent.name, session)

    /** What the model is told, whether or not ratings are on - for the agent form's hover card. */
    fun specs(): List<ToolSpec> = listOf(
        ToolSpec(
            name = RATE,
            description = "Sends Orknux the review the person you are talking to gave this conversation: their " +
                "score from $LOWEST (poor) to $HIGHEST (excellent), and what they wrote about it if anything. " +
                "Only what they gave you - never your own view of how it went. If they wrote a review without " +
                "a score, ask them for one before sending it.",
            parameters = listOf(
                ToolParameterSpec(
                    SCORE,
                    "Their score, a whole number from $LOWEST to $HIGHEST.",
                    required = true,
                    schema = mapOf("type" to "integer", "minimum" to LOWEST, "maximum" to HIGHEST),
                ),
                ToolParameterSpec(REVIEW, "What they wrote about it, in their own words; leave out if nothing.", required = false),
            ),
        ),
    )

    /**
     * Keeps one review of [session], from whichever door it came - the agent's
     * tool or the chat's own menu - so the switch, the scale and the limit are
     * held in one place. Refused with a sentence that says why.
     */
    fun send(session: Long, agentName: String, who: Prompter, score: Int, review: String?, fromChat: Boolean = false): SessionRating {
        if (fromChat && !settings.chatReviews()) throw ReviewRefusedException("Chat reviews are switched off in Admin > Reports.")
        if (!fromChat && !settings.sessionRatings()) throw ReviewRefusedException("Session ratings are switched off in Admin > Reports.")
        if (score !in LOWEST..HIGHEST) throw ReviewRefusedException("$score is not a score: give one from $LOWEST to $HIGHEST.")
        /*
         * Bounded, and refused rather than dropped, as a note is: told the
         * conversation has sent its share, an agent can say so to the person.
         */
        val most = settings.reviewsPerSession()
        if (ratings.countBySessionId(session) >= most) {
            throw ReviewRefusedException("This conversation has already sent $most reviews, which is as many as one may.")
        }
        val conversation = sessions.findByIdOrNull(session)
            ?: throw ReviewRefusedException("This conversation is no longer kept.")
        return ratings.save(
            SessionRating(
                at = OffsetDateTime.now(),
                workspaceId = conversation.workspaceId,
                sessionId = session,
                agentName = agentName,
                score = score,
                review = review?.trim()?.ifEmpty { null },
                source = who.source,
                connectionId = who.connectionId,
                personKey = who.key,
                personName = who.name,
            ),
        )
    }

    private inner class Shed(private val agentName: String, private val session: Long) : ToolShed {

        override fun specs(): List<ToolSpec> = this@RatingTools.specs()

        /*
         * Without this a model never mentions it: the description says what the
         * tool does, not when a person would want it, and the moment that
         * matters is the one where the work let them down.
         */
        override fun briefing(): String =
            "If the person seems unhappy or disappointed with your work, tell them they can review this " +
                "conversation - a score from $LOWEST to $HIGHEST and what went wrong - and send what they give " +
                "you to Orknux with $RATE. Do not ask for one otherwise, and never review your own work."

        override fun handles(name: String): Boolean = name == RATE

        override fun run(call: ToolCall): String {
            val node = runCatching { mapper.readTree(call.arguments.ifBlank { "{}" }) }.getOrNull()
                ?: return refusal("The arguments were not JSON.")
            val score = scoreOf(node.path(SCORE))
                ?: return refusal("Give $SCORE as a whole number from $LOWEST to $HIGHEST; ask the person if they gave none.")
            val review = node.path(REVIEW).takeIf { it.isString }?.stringValue()
            return try {
                val saved = send(session, agentName, usage.prompterOf(session), score, review)
                mapper.writeValueAsString(mapOf("sent" to true, "id" to saved.id))
            } catch (refused: ReviewRefusedException) {
                refusal("${refused.message} Tell the person theirs was not sent.")
            }
        }

        /** A number a model may well have sent as a string. */
        private fun scoreOf(node: JsonNode): Int? = when {
            node.isIntegralNumber -> node.asInt()
            node.isString -> node.stringValue().trim().toIntOrNull()
            else -> null
        }

        private fun refusal(said: String): String = mapper.writeValueAsString(mapOf("error" to said))
    }

    companion object {
        const val RATE = "rate_session"
        const val REVIEW = "review"
        const val SCORE = "score"

        /** The scale, which the table's CHECK holds too: a definition, not a tunable. */
        const val LOWEST = 1
        const val HIGHEST = 5
    }
}

/** A review that was not kept, and why. */
class ReviewRefusedException(message: String) : RuntimeException(message)
