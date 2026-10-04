package io.mszymanski.orknux.server.watcher

import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.InvalidPathException
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.Option
import net.minidev.json.JSONStyle
import net.minidev.json.JSONValue
import java.util.regex.PatternSyntaxException

/**
 * Whether what a tool returned is what a watcher is waiting for. Issue #606.
 *
 * Two kinds, because tool results come in two shapes. Most are JSON, and a
 * JSONPath says "this field has this value" exactly - Jayway's, never a path
 * walker written here. Some are text, or JSON whose interesting part is inside
 * a string, and a regular expression found anywhere in the result covers those.
 *
 * **When a path matches.** When it finds something: a definite path such as
 * `$.status` matches when that field exists and is neither null nor false, and
 * a path with a filter or a wildcard matches when it finds at least one thing.
 * So `$.status` alone matches as soon as there is any status at all, which is
 * rarely what is meant - the condition that waits for a value is a filter,
 * `$[?(@.status == 'done')]`. The skill says so, with examples, because it is
 * the one mistake everybody makes once.
 *
 * A result that is not JSON never matches a path; a path that is not a path is
 * refused when the watcher is set, never at its hundredth check.
 */
object WatcherCondition {

    /** Null when the condition can be used, or why it cannot - said to the model that wrote it. */
    fun problemWith(kind: WatcherConditionKind, condition: String): String? = when (kind) {
        WatcherConditionKind.JSONPATH -> if (!condition.trim().startsWith("$")) {
            "A JSONPath starts with \$, as in \$[?(@.status == 'done')]."
        } else try {
            JsonPath.compile(condition)
            null
        } catch (failure: InvalidPathException) {
            "That is not a JSONPath this server can read: ${failure.message}"
        } catch (failure: IllegalArgumentException) {
            "That is not a JSONPath this server can read: ${failure.message}"
        }

        WatcherConditionKind.REGEX -> try {
            Regex(condition)
            null
        } catch (failure: PatternSyntaxException) {
            "That is not a regular expression this server can read: ${failure.description} " +
                "near index ${failure.index}."
        }
    }

    /** What matched, or null where nothing did. */
    fun match(kind: WatcherConditionKind, condition: String, result: String): String? = when (kind) {
        WatcherConditionKind.REGEX -> Regex(condition).find(result)?.value
        WatcherConditionKind.JSONPATH -> runCatching {
            val found: Any? = JsonPath.using(LENIENT).parse(result).read<Any?>(condition)
            when {
                found == null -> null
                found == false -> null
                found is Collection<*> && found.isEmpty() -> null
                found is Map<*, *> && found.isEmpty() -> null
                /*
                 * json-smart's own writer, which unlike the provider's toJson
                 * takes a bare string or number too - and told not to escape a
                 * slash, which it does by default for HTML and which would hand
                 * the model https:\/\/ in what matched.
                 */
                else -> JSONValue.toJSONString(found, AS_WRITTEN)
            }
        }.getOrNull()
    }

    private val AS_WRITTEN = JSONStyle(JSONStyle.FLAG_PROTECT_4WEB)

    /** A path that finds nothing answers null or an empty list rather than throwing. */
    private val LENIENT: Configuration = Configuration.defaultConfiguration()
        .addOptions(Option.SUPPRESS_EXCEPTIONS)
}
