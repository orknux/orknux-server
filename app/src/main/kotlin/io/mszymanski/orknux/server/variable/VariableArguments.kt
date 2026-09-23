package io.mszymanski.orknux.server.variable

import io.mszymanski.orknux.server.action.FunctionExternal
import io.mszymanski.orknux.server.action.WorkflowFunction
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * What a function's external parameters come to, as arguments.
 *
 * Everything crossing into the sandbox is JSON, so this is where a variable
 * stops being the text a column holds and becomes the value the script sees: a
 * number arrives as a number, a boolean as a boolean, a string quoted.
 *
 * One place, because three callers need it — a node running a function, a
 * condition asking one, and a webhook checking who is calling — and a secret
 * decoded differently in three places is a secret that will eventually be logged
 * in one of them.
 */
@Component
class VariableArguments(
    private val variables: WorkspaceVariableRepository,
    private val mapper: ObjectMapper,
) {

    /**
     * The function's externals, in the order it receives them.
     *
     * A variable that has been deleted, or has no value yet, arrives as `null`
     * rather than stopping the call: a script checking a signature against
     * nothing should answer no, which is what it will do, and that is a better
     * failure than a run that dies before it can.
     */
    /**
     * @param instead values to hand over in place of what the workspace holds,
     *   by variable name and already JSON. Empty everywhere except a test run:
     *   see [FunctionCaller.call].
     */
    fun of(function: WorkflowFunction, instead: Map<String, String> = emptyMap()): List<String> =
        of(function.externals, function.name, instead)

    /**
     * The same translation for anything else that carries externals — a tool's
     * are the same embeddable and arrive the same way. [called] is only for the
     * warning when a variable has gone missing.
     */
    fun of(externals: List<FunctionExternal>, called: String, instead: Map<String, String> = emptyMap()): List<String> =
        externals.map { external ->
            val variable = variables.findByIdOrNull(external.variableId)
            if (variable == null) {
                log.warn("{} is handed a variable that no longer exists", called)
                return@map "null"
            }
            instead[variable.name] ?: json(variable)
        }

    /** The names those arguments arrive under, for anything that has to say so. */
    fun namesOf(function: WorkflowFunction): List<String> = function.externals.mapNotNull { external ->
        variables.findByIdOrNull(external.variableId)?.name
    }

    private fun json(variable: WorkspaceVariable): String {
        val held = variable.value ?: return "null"
        return when (variable.type) {
            VariableType.LIST -> list(held, variable.elementType ?: VariableType.STRING)
            else -> scalar(variable.type, held)
        }
    }

    private fun scalar(type: VariableType, held: String): String = when (type) {
        VariableType.STRING -> mapper.writeValueAsString(held)
        // Written as typed, checked here: a number nobody could parse is a
        // configuration mistake, and `null` is the honest version of it.
        VariableType.NUMBER -> held.trim().toBigDecimalOrNull()?.toString() ?: "null"
        VariableType.BOOLEAN -> when (held.trim().lowercase()) {
            "true" -> "true"
            "false" -> "false"
            else -> "null"
        }
        // A list inside a list has no shape here; the save refuses it.
        VariableType.LIST -> "null"
    }

    /**
     * A list, element by element, each held to the element type. Issue #377.
     *
     * The stored text is a JSON array; the elements are written as the screen
     * typed them - strings even for a list of numbers - so each goes through
     * the same conversion a scalar does. A list that is not a JSON array is
     * `null`, the way a number that is not a number is.
     */
    private fun list(held: String, elementType: VariableType): String {
        val parsed = runCatching { mapper.readTree(held) }.getOrNull() ?: return "null"
        if (!parsed.isArray) return "null"
        return parsed.values().joinToString(separator = ",", prefix = "[", postfix = "]") { one ->
            scalar(elementType, if (one.isTextual) one.asString() else one.toString())
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(VariableArguments::class.java)
    }
}
