package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.connector.model.LlmModelView
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import org.springframework.data.domain.PageRequest
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller

/**
 * The speeds a provider reported, on a session and on a model.
 *
 * Fields of objects the caller has already been let see: `llmSession` and
 * `model` answer null for a session or model in a workspace the caller cannot
 * see, so nothing here is reached for one, and nothing here checks again.
 *
 * The session's figure is over all of its calls, because a session is a bounded
 * thing and its average is the honest one; the model's is over its newest
 * calls, as many as [Tunable.MODEL_SPEED_CALLS], because a model lives for
 * months and a server it runs on is upgraded under it.
 */
@Controller
class ModelSpeedAPI(
    private val calls: SessionModelCallRepository,
    private val tuning: Tuning = Tuning.DEFAULTS,
) {

    @SchemaMapping(typeName = "LlmSession", field = "speed")
    fun sessionSpeed(session: LlmSessionView): ModelSpeedView? =
        ModelSpeedView.ofSums(calls.timedSums(session.id).firstOrNull())

    @SchemaMapping(typeName = "LlmSession", field = "modelCalls")
    fun modelCalls(session: LlmSessionView, @Argument timedOnly: Boolean?): List<SessionModelCallView> {
        val page = PageRequest.of(0, window())
        val found = if (timedOnly == true) {
            calls.findBySessionIdAndPromptTokensIsNotNullOrderByIdDesc(session.id, page)
        } else {
            calls.findBySessionIdOrderByIdDesc(session.id, page)
        }
        return found.map(::SessionModelCallView)
    }

    @SchemaMapping(typeName = "Model", field = "speed")
    fun modelSpeed(model: LlmModelView): ModelSpeedView? =
        ModelSpeedView.over(calls.findByModelIdAndPromptTokensIsNotNullOrderByIdDesc(model.id, PageRequest.of(0, window())))

    private fun window(): Int = tuning[Tunable.MODEL_SPEED_CALLS]
}
