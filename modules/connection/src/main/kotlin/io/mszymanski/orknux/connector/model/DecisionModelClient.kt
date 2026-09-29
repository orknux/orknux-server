package io.mszymanski.orknux.connector.model

import io.mszymanski.orknux.connector.connection.ConnectionProbe
import io.mszymanski.orknux.connector.connection.ConnectionProperties
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Duration

/** The three kinds of question a decision model answers. */
enum class DecisionQuestionKind(
    /** How the API spells it: `"type": "choice"`. */
    val wire: String,
) {
    /** Picks one of several options, and says how likely each one was. */
    CHOICE("choice"),

    /** Places the state on ordered levels, lowest first. */
    SCORE("score"),

    /** The probability that a yes-or-no statement holds. */
    NOUL("noul"),
}

/**
 * One option of a choice, one level of a score, or one side of a noul.
 *
 * [name] is what comes back: a choice answers with the name it picked. The
 * [description] is what the model reads to tell the options apart, and is the
 * name again where nobody wrote one. A score's levels come back by position,
 * so there a name is only a label for people. A noul's two are called `true`
 * and `false`, because that is what its criteria object is keyed on.
 */
data class DecisionOption(val name: String, val description: String = "")

/** One typed question, under the key its answer comes back under. */
data class DecisionQuestion(
    val key: String,
    val kind: DecisionQuestionKind,
    /** What is being asked: "Which team should handle this?" */
    val instructions: String,
    val options: List<DecisionOption> = emptyList(),
)

/** What a decision model said, or why it said nothing. */
sealed interface Decision {

    /**
     * @param answers the `answers` object as the provider sent it, keyed by the
     *   question keys. Handed on whole rather than read into a class of ours:
     *   what a run carries is what the model said, and a field TypeSafe adds
     *   next month reaches the workflow without anything here learning its name.
     */
    data class Answered(
        val answers: ObjectNode,
        /** The model that answered, as it named itself: `jev-1.13.0`. */
        val model: String?,
        val inputTokens: Long,
        val outputTokens: Long,
        val millis: Long,
        /**
         * What could not be read out of the answer, one sentence per question
         * left unanswered - an option the model was not offered, a probability
         * it did not give. Only a chat model produces any; see [ChatDecisions].
         */
        val notes: List<String> = emptyList(),
    ) : Decision

    /**
     * @param permanent whether asking again could change anything: a key that
     *   was refused, or a request the provider found invalid, is refused the
     *   same way the next time. A rate limit or an overloaded provider is not,
     *   and is left to the node's own retry policy.
     */
    data class Failed(val reason: String, val permanent: Boolean = false) : Decision
}

/**
 * Asks a decision model a set of typed questions about a state.
 *
 * Jev and Laya speak one API, which TypeSafe documents at
 * https://docs.typesafe.ai/api and Laya's `laya/serve.py` implements:
 *
 * ```
 * POST {base}/v1/systemone
 * Authorization: Bearer <key>                       (optional for Laya)
 * {"state": "..." | {...}, "model": "jev-latest",
 *  "questions": {"<key>": {"type": "choice", "instructions": "...",
 *                          "criteria": {"<option>": "<what it means>"}}}}
 * ```
 *
 * and answers `{"model": ..., "answers": {"<key>": {...}}, "usage":
 * {"input_tokens": n, "output_tokens": n}}`. A choice's criteria are an object
 * of option to description and its answer carries `choice`, `confidence` and
 * `probabilities`; a score's criteria are an ordered array and its answer
 * carries `score`, `confidence`, `probabilities` and `legend` keyed by level
 * number; a noul's criteria are an optional `{"true": ..., "false": ...}` and
 * its answer is `noul`, the probability that it holds.
 *
 * There is no SDK to hand this to on the JVM - see the pom - so this is
 * Spring's RestClient over the documented call, on ProxyRouter's own HTTP
 * client, vetted like every other outbound address. Issue #577.
 */
@Service
class DecisionModelClient(
    private val providers: ModelProviderRepository,
    private val models: LlmModelRepository,
    private val probe: ModelProviderProbe,
    private val connections: ConnectionProbe,
    private val properties: ConnectionProperties,
    private val usage: ModelUsageRecorder,
    private val mapper: ObjectMapper,
    /** The same questions asked of a chat model, for a node that runs on one. */
    private val chats: ChatDecisions,
    proxies: ProxyRouter,
) {

    private val rest: RestClient = RestClient.builder()
        .requestFactory(
            JdkClientHttpRequestFactory(
                proxies.builder()
                    .connectTimeout(Duration.ofSeconds(properties.probeTimeoutSeconds))
                    .build(),
            ).apply { setReadTimeout(Duration.ofSeconds(properties.requestTimeoutSeconds)) },
        )
        .build()

    /**
     * @param modelId one of the workspace's [ModelKind.DECISION] models, or a
     *   [ModelKind.CHAT] one, which is asked the same questions in words and
     *   answers in the same shape - see [ChatDecisions].
     * @param state what the questions are about: text, or an object whose
     *   fields the model reads. Anything else is sent as its text.
     */
    fun decide(modelId: Long, state: JsonNode, questions: List<DecisionQuestion>): Decision {
        val model = models.findByIdOrNull(modelId)
            ?: return Decision.Failed("That decision model no longer exists", permanent = true)
        if (model.kind != ModelKind.DECISION && model.kind != ModelKind.CHAT) {
            return Decision.Failed("${model.name} is neither a decision model nor a chat model", permanent = true)
        }
        if (!model.enabled) return Decision.Failed("${model.name} is turned off", permanent = true)
        if (questions.isEmpty()) return Decision.Failed("There is nothing to ask", permanent = true)
        if (model.kind == ModelKind.CHAT) return chats.decide(model, state, questions)

        val provider = providers.findByIdOrNull(model.providerId)
            ?: return Decision.Failed("The provider ${model.name} belongs to has been removed", permanent = true)
        if (provider.type != ProviderType.SYSTEM_ONE) {
            return Decision.Failed("${provider.name} does not speak the decision API", permanent = true)
        }

        val url = "${provider.systemOneBase()}/v1/systemone"
        // Before the credential is read, so a call that will not be made
        // decrypts nothing - the rule every client here follows.
        connections.vet(url)?.let { return Decision.Failed("${provider.name} cannot be called: $it", permanent = true) }

        val header = when (val credential = probe.systemOneCredential(provider)) {
            null -> null
            is ModelProviderProbe.Credential.Failed -> return Decision.Failed(credential.reason, permanent = true)
            is ModelProviderProbe.Credential.Header -> credential.header
        }

        val body = request(model, state, questions)
        val started = System.currentTimeMillis()
        return try {
            val answer = rest.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .headers { headers -> header?.let { headers.set(it.name, it.value) } }
                .body(mapper.writeValueAsString(body))
                .exchange { _, response ->
                    Exchanged(response.statusCode.value(), response.body.readAllBytes().toString(Charsets.UTF_8))
                }
            val millis = System.currentTimeMillis() - started
            read(model, answer, millis).also { decided ->
                if (decided is Decision.Answered) {
                    runCatching { usage.record(modelId, decided.inputTokens, decided.outputTokens, millis) }
                }
            }
        } catch (failure: Exception) {
            // A timeout or a refused connection: nothing was said, and the
            // next attempt may well be answered.
            log.warn("Asking {} at {} failed: {}", model.name, url, failure.toString())
            log.debug("Asking {} at {} failed", model.name, url, failure)
            Decision.Failed("${model.name} could not be reached: ${failure.message ?: failure.javaClass.simpleName}")
        }
    }

    /** The documented body: the state, the model, and each question keyed by its own key. */
    private fun request(model: LlmModel, state: JsonNode, questions: List<DecisionQuestion>): ObjectNode {
        val body = mapper.createObjectNode()
        body.set("state", if (state.isObject || state.isArray) state else mapper.stringNode(textOf(state)))
        // Laya routes to a checkpoint of its own choosing when this is absent;
        // Jev wants a name. The model row always has one.
        model.modelId.trim().takeIf { it.isNotEmpty() }?.let { body.put("model", it) }

        val asked = body.putObject("questions")
        questions.forEach { question ->
            val one = asked.putObject(question.key)
            one.put("type", question.kind.wire)
            one.put("instructions", question.instructions)
            when (question.kind) {
                DecisionQuestionKind.CHOICE -> {
                    val criteria = one.putObject("criteria")
                    question.options.forEach { criteria.put(it.name, it.description.ifBlank { it.name }) }
                }

                DecisionQuestionKind.SCORE -> {
                    val criteria = one.putArray("criteria")
                    question.options.forEach { criteria.add(it.description.ifBlank { it.name }) }
                }

                // Optional: a noul with nothing said about what true and false
                // mean is still a question, and is sent without them.
                DecisionQuestionKind.NOUL -> {
                    val sides = question.options.filter { it.name == "true" || it.name == "false" }
                        .filter { it.description.isNotBlank() }
                    if (sides.isNotEmpty()) {
                        val criteria = one.putObject("criteria")
                        sides.forEach { criteria.put(it.name, it.description) }
                    }
                }
            }
        }
        return body
    }

    /** What came back, as an answer or as the sentence that explains it. */
    private fun read(model: LlmModel, answer: Exchanged, millis: Long): Decision {
        val tree = runCatching { mapper.readTree(answer.body) }.getOrNull()
        return when (val status = answer.status) {
            in 200..299 -> {
                val answers = tree?.path("answers")
                if (answers == null || !answers.isObject) {
                    return Decision.Failed("${model.name} answered without any answers")
                }
                Decision.Answered(
                    answers = answers as ObjectNode,
                    model = tree.path("model").stringValueOpt().orElse(null),
                    inputTokens = tree.path("usage").path("input_tokens").asLong(0),
                    outputTokens = tree.path("usage").path("output_tokens").asLong(0),
                    millis = millis,
                )
            }

            401, 403 -> Decision.Failed("${model.name} rejected the credentials ($status)", permanent = true)
            // "Request body failed validation": the same request fails the same
            // way, so it is said once, with whatever the provider said about it.
            400, 422 -> Decision.Failed("${model.name} refused the questions ($status)${said(tree)}", permanent = true)
            404 -> Decision.Failed("There is no decision API at that address (404)", permanent = true)
            else -> Decision.Failed("${model.name} answered $status${said(tree)}")
        }
    }

    /** The provider's own words about a refusal, ready to append, or nothing. */
    private fun said(tree: JsonNode?): String {
        if (tree == null) return ""
        val detail = tree.path("detail")
        val message = listOf(tree.path("error").path("message"), tree.path("error"), tree.path("message"), detail)
            .firstNotNullOfOrNull { it.stringValueOpt().orElse(null) }
            ?: detail.takeIf { it.isArray || it.isObject }?.toString()
            ?: return ""
        return ": " + message.trim().lineSequence().first().trim().take(MESSAGE_LIMIT)
    }

    private fun textOf(state: JsonNode): String = if (state.isString) state.stringValue() else state.toString()

    private data class Exchanged(val status: Int, val body: String)

    private companion object {
        val log = LoggerFactory.getLogger(DecisionModelClient::class.java)

        /** A sentence's worth of what a provider said; the rest is a validation dump. */
        const val MESSAGE_LIMIT = 300
    }
}
