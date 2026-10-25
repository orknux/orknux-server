package io.mszymanski.orknux.server.research

import io.mszymanski.orknux.connector.model.Brief
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * The questions a finished research suggests asking next, each one a research
 * of its own a click away.
 *
 * **A call of their own, after the report is final.** Asking the writer for
 * them in the same answer as the report would put them in the draft the page
 * shows as it is written, in what the reviewer reads and the writer revises,
 * and in the PDF unless every one of those places learnt to cut them out - and
 * a model told to end its report with a JSON tail does not always end it so.
 * So they are asked for once the report is what will be saved: one short call
 * with the research's own model, thinking off and the answer bounded, from the
 * question, the parts it was broken into and the report. The report is never
 * touched.
 *
 * **How many** is the research's own number, fixed when it started: what its
 * start form said, or the workspace's number, or the installation's
 * [Tunable.RESEARCH_FOLLOW_UPS]. Zero asks for none and makes no call.
 */
@Component
class ResearchFollowUps(
    private val researches: ResearchRepository,
    private val workspaces: WorkspaceRepository,
    private val models: ModelChatClient,
    private val pipeline: ResearchPipeline,
    private val tuning: Tuning,
    private val mapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** How many a research started now in [workspaceId] is given when its start does not say. */
    fun countFor(workspaceId: Long): Int {
        val bounds = Tunable.RESEARCH_FOLLOW_UPS.least..Tunable.RESEARCH_FOLLOW_UPS.most
        return workspaces.findByIdOrNull(workspaceId)?.researchFollowUps?.takeIf { it in bounds }
            ?: tuning[Tunable.RESEARCH_FOLLOW_UPS]
    }

    /**
     * Asks for the research's follow-up questions and keeps them on it; none
     * where it was asked for none, and none - said in the log - where the model
     * did not answer. Never fails what called it: a research whose report is
     * saved is finished whatever this says.
     */
    fun suggest(researchId: Long?, report: String) {
        val research = researchId?.let { researches.findByIdOrNull(it) } ?: return
        val count = research.followUpCount ?: countFor(research.workspaceId)
        if (count <= 0) return
        val modelId = research.modelId ?: return
        val plan = pipeline.subQuestionsOf(research.progress)
        val asked = runCatching {
            models.complete(
                modelId,
                listOf(
                    ChatTurn("system", ResearchPipeline.FOLLOW_UPS),
                    ChatTurn("user", buildString {
                        appendLine("Question: ${research.question}")
                        appendLine()
                        appendLine("Already covered:")
                        plan.ifEmpty { listOf(research.question) }.forEach { appendLine("- $it") }
                        appendLine()
                        appendLine("Write exactly $count follow-up questions.")
                        appendLine()
                        appendLine("Report:")
                        appendLine()
                        append(report)
                    }),
                ),
                brief = Brief(maxOutput = tuning[Tunable.RESEARCH_FOLLOW_UPS_TOKENS], noThinking = true),
            )
        }.getOrElse { failure ->
            log.warn("Research {} could not be given follow-up questions", research.id, failure)
            return
        }
        val said = (asked as? ChatCompletion.Answered)?.content ?: run {
            log.warn("Research {} was given no follow-up questions: {}", research.id, (asked as? ChatCompletion.Failed)?.reason)
            return
        }
        val covered = plan.map { it.trim().lowercase() }.toSet() + research.question.trim().lowercase()
        val questions = pipeline.listFrom(said)
            .map { it.trim().trimStart('-', '*', ' ').trim() }
            .filter { it.isNotEmpty() && it.length <= MOST_QUESTION && it.lowercase() !in covered }
            .distinct()
            .take(count)
        runCatching { researches.noteFollowUps(requireNotNull(research.id), mapper.writeValueAsString(questions)) }
            .onFailure { log.warn("Could not note the follow-up questions of research {}", research.id, it) }
    }

    /** The follow-up questions a row holds; none where it holds none. */
    fun of(held: String?): List<String> =
        held?.let { runCatching { mapper.readTree(it) }.getOrNull() }?.takeIf { it.isArray }
            ?.toList()?.mapNotNull { one -> one.takeIf { it.isTextual }?.stringValue() }
            .orEmpty()

    private companion object {
        /** A question longer than this is not a question but a model that kept writing; the format of one, not a behaviour. */
        const val MOST_QUESTION = 500
    }
}
