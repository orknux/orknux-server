package io.mszymanski.orknux.server.embedded

import io.mszymanski.orknux.server.attachment.InstallationSetting
import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.chat.BuiltInTools
import io.mszymanski.orknux.server.graphql.Refusal
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.regex.PatternSyntaxException

/**
 * Admin -> Settings -> HTTP tools: whether an agent's `http_get`,
 * `http_request` and `http_download` are offered at all, and where they may
 * go. Issue #602.
 *
 * **The agents' tools and nothing else.** `orknux.http` - what a function, a
 * workspace's JavaScript tool and a plugin call - is a different door with a
 * different reader: somebody wrote that code and chose its addresses, where a
 * model chooses an address because something it read suggested one. That door
 * stays governed by the proxy rules alone, and so do the `http_get` and
 * `http_request` *functions* a graph node calls, for the same reason. The
 * proxy rules still apply to the tools on top of this; nothing here widens
 * them.
 *
 * **Switched off is not revoked.** Off withholds the tools from every agent
 * without touching any agent's grant, so switching them on again gives back
 * exactly what each agent held - an administrator's pause should not cost
 * every workspace an afternoon of re-ticking boxes.
 *
 * **One matcher.** [decide] is what the tools ask before a request and what
 * the tester on the settings page asks, so the screen cannot be confident
 * about an answer the tools would give differently.
 *
 * Stored as installation settings: the switch and the policy one row each, and
 * a rule per row under [RULE_PREFIX], the way the log levels keep a logger per
 * row. A value is the methods, a newline, and the pattern exactly as written,
 * so a regex full of backslashes is not doubled by an escaping it never needed.
 */
@Service
class HttpToolPolicy(
    private val settings: InstallationSettingRepository,
) {

    /** Whether the HTTP tools are offered at all. On unless somebody said otherwise. */
    fun enabled(): Boolean = settings.findByIdOrNull(ENABLED)?.value?.trim()?.toBooleanStrictOrNull() ?: true

    /** The policy as stored. */
    fun current(): HttpToolPolicyView = HttpToolPolicyView(
        enabled = enabled(),
        kind = settings.findByIdOrNull(KIND)?.value?.trim()
            ?.let { held -> HttpToolPolicyKind.entries.firstOrNull { it.name == held } }
            ?: HttpToolPolicyKind.ANY,
        rules = storedRules(),
    )

    /**
     * Whether a tool of this name may be offered, as far as this switch goes.
     * True for every name that is not one of the HTTP tools.
     */
    fun offers(name: String): Boolean = name !in SWITCHED || enabled()

    @Transactional
    fun setEnabled(enabled: Boolean, by: String) {
        write(ENABLED, enabled.toString(), by)
    }

    /**
     * The policy and its rules, replacing what was there. Checked whole before
     * anything is written, so a refused save leaves the stored list as it was.
     */
    @Transactional
    fun save(kind: HttpToolPolicyKind, rules: List<HttpToolRule>, by: String): HttpToolPolicyView {
        val checked = checked(rules)
        write(KIND, kind.name, by)
        val held = settings.findAll().filter { it.name.startsWith(RULE_PREFIX) }.associateBy { it.name }
        checked.forEachIndexed { at, rule -> write(ruleKey(at), rule.methods.joinToString(",") + NL + rule.url, by) }
        val kept = checked.indices.map(::ruleKey).toSet()
        settings.deleteAll(held.values.filter { it.name !in kept })
        settings.flush()
        return current()
    }

    /**
     * What a request would meet: the stored policy, or [draft] where a screen
     * is asking about rules it has not saved yet. A draft is checked exactly as
     * a save would check it, so a pattern the tester accepts is one Save takes.
     */
    fun decide(url: String, method: String, draft: HttpToolDraft? = null): HttpToolDecision {
        val asked = method.trim().uppercase()
        if (!enabled()) return HttpToolDecision(url, asked, HttpToolOutcome.SWITCHED_OFF, null, emptyList())
        val kind = draft?.kind ?: current().kind
        if (kind == HttpToolPolicyKind.ANY) return HttpToolDecision(url, asked, HttpToolOutcome.ANY_URL, null, emptyList())

        val rules = if (draft != null) checked(draft.rules) else storedRules()
        val matching = rules.mapIndexedNotNull { at, rule ->
            val pattern = runCatching { Regex(rule.url) }.getOrNull() ?: return@mapIndexedNotNull null
            if (pattern.matches(url)) at else null
        }
        val allowing = matching.firstOrNull { asked in rules[it].methods }
        return when {
            allowing != null -> HttpToolDecision(
                url, asked, HttpToolOutcome.ALLOWED, HttpToolMatch(allowing + 1, rules[allowing]), matching.map { it + 1 },
            )
            matching.isEmpty() -> HttpToolDecision(url, asked, HttpToolOutcome.NO_RULE_MATCHES, null, emptyList())
            else -> HttpToolDecision(
                url, asked, HttpToolOutcome.METHOD_NOT_LISTED, null, matching.map { it + 1 },
                matchedRules = matching.map { rules[it] },
            )
        }
    }

    /**
     * What `http_allowList` answers: the policy, and how to read it. A model
     * that was refused is pointed here, so the text says what a refusal meant.
     */
    fun describe(): Map<String, Any?> {
        if (!enabled()) return mapOf("error" to SWITCHED_OFF_SENTENCE)
        val held = current()
        return if (held.kind == HttpToolPolicyKind.ANY) {
            linkedMapOf(
                "policy" to "any",
                "howToRead" to "Any URL may be requested with any method the HTTP tools send. " +
                    "The installation's proxy rules still decide what can actually be reached. " +
                    "Redirects are not followed: a 3xx answer comes back with its location, and fetching that " +
                    "location is a new request.",
            )
        } else {
            linkedMapOf(
                "policy" to "list",
                "rules" to held.rules.map { linkedMapOf("url" to it.url, "methods" to it.methods) },
                "howToRead" to "Each rule's url is a regular expression matched against the whole URL - scheme, " +
                    "host, path and query - not a part of it. A request is allowed when some rule matches its URL " +
                    "and lists its method; http_get and http_download are GET. A URL no rule matches is refused " +
                    "whatever the method. Redirects are not followed: a 3xx answer comes back with its location, " +
                    "and fetching that location is a new request checked the same way. " +
                    (if (held.rules.isEmpty()) "There are no rules, so nothing may be requested." else ""),
            )
        }
    }

    private fun storedRules(): List<HttpToolRule> = settings.findAll()
        .filter { it.name.startsWith(RULE_PREFIX) }
        .sortedBy { it.name }
        .mapNotNull { row ->
            val methods = row.value.substringBefore(NL, "")
            val url = row.value.substringAfter(NL, "")
            if (url.isEmpty()) null else HttpToolRule(url, methods.split(',').map { it.trim() }.filter { it.isNotEmpty() })
        }

    /** Every rule as it would be stored, or the refusal for the first one that cannot be. */
    private fun checked(rules: List<HttpToolRule>): List<HttpToolRule> = rules.mapIndexed { at, rule ->
        val position = at + 1
        val url = rule.url.trim()
        if (url.isEmpty()) throw HttpToolRulePatternMissingException(position)
        if (url.length > MAX_PATTERN) throw HttpToolRulePatternTooLongException(position, MAX_PATTERN)
        try {
            Regex(url)
        } catch (wrong: PatternSyntaxException) {
            throw HttpToolRulePatternInvalidException(position, url, wrong.description ?: "it does not compile")
        }
        val methods = rule.methods.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
        if (methods.isEmpty()) throw HttpToolRuleMethodsMissingException(position)
        methods.firstOrNull { it !in METHODS }?.let { throw HttpToolRuleMethodUnknownException(position, it) }
        HttpToolRule(url, METHODS.filter { it in methods })
    }

    private fun write(name: String, value: String, by: String) {
        val held = settings.findByIdOrNull(name) ?: InstallationSetting(name = name)
        held.value = value
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = by
        settings.save(held)
    }

    companion object {
        const val ENABLED = "http.tools.enabled"
        const val KIND = "http.tools.policy"
        const val RULE_PREFIX = "http.tools.rule."

        /** What the tools send, and so what a rule may list. */
        val METHODS = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")

        /**
         * A pattern's longest. The row holds 500 characters and the methods
         * before it; this leaves them room with some to spare.
         */
        const val MAX_PATTERN = 400

        /** The tools this switches: the three that make requests, and the one that reads their policy. */
        val SWITCHED: Set<String> = (BuiltInTools.HTTP + BuiltInTools.HTTP_ALLOW_LIST).toSet()

        const val SWITCHED_OFF_SENTENCE =
            "The HTTP tools are switched off in this installation's Admin Settings, so no request was made."

        private val NL = 10.toChar().toString()

        private fun ruleKey(at: Int) = RULE_PREFIX + at.toString().padStart(3, '0')
    }
}

enum class HttpToolPolicyKind {
    /** Any URL, any method the tools send. */
    ANY,

    /** Only what a rule allows. */
    LIST,
}

/** One rule: a pattern for the whole URL, and the methods it allows. */
data class HttpToolRule(val url: String, val methods: List<String>)

/** Rules a screen has not saved yet, for the tester. */
data class HttpToolDraft(val kind: HttpToolPolicyKind, val rules: List<HttpToolRule>)

data class HttpToolPolicyView(
    val enabled: Boolean,
    val kind: HttpToolPolicyKind,
    val rules: List<HttpToolRule>,
) {
    /** What a rule may list, for the screen to offer. */
    val methods: List<String> get() = HttpToolPolicy.METHODS
}

enum class HttpToolOutcome {
    /** The tools are switched off, so nothing is requested at all. */
    SWITCHED_OFF,

    /** The policy is any URL. */
    ANY_URL,

    /** A rule matches the URL and lists the method. */
    ALLOWED,

    /** No rule matches the URL. */
    NO_RULE_MATCHES,

    /** Some rule matches the URL, and none of those lists the method. */
    METHOD_NOT_LISTED,
}

/** The rule that allowed a request, by its place in the list from 1. */
data class HttpToolMatch(val position: Int, val rule: HttpToolRule)

/** What a request would meet, and why - for the tools' refusals and the tester alike. */
data class HttpToolDecision(
    val url: String,
    val method: String,
    val outcome: HttpToolOutcome,
    /** The first rule that allowed it, where one did. */
    val matched: HttpToolMatch?,
    /** Every rule whose pattern matches the URL, by position from 1, whatever their methods. */
    val urlMatches: List<Int>,
    private val matchedRules: List<HttpToolRule> = emptyList(),
) {
    val allowed: Boolean get() = outcome == HttpToolOutcome.ANY_URL || outcome == HttpToolOutcome.ALLOWED

    /** The sentence a model or a person is told. */
    val message: String
        get() = when (outcome) {
            HttpToolOutcome.SWITCHED_OFF -> HttpToolPolicy.SWITCHED_OFF_SENTENCE
            HttpToolOutcome.ANY_URL -> "Allowed: the policy allows any URL."
            HttpToolOutcome.ALLOWED -> "Allowed by rule ${matched!!.position}, ${matched.rule.url}, " +
                "which lists ${matched.rule.methods.joinToString(", ")}."
            HttpToolOutcome.NO_RULE_MATCHES ->
                "$method $url is refused by this installation's HTTP allow list: no rule's pattern matches this URL. " +
                    "Call ${BuiltInTools.HTTP_ALLOW_LIST} to see what may be requested."
            HttpToolOutcome.METHOD_NOT_LISTED -> {
                val methods = matchedRules.flatMap { it.methods }.distinct()
                val one = urlMatches.size == 1
                val rules = if (one) "rule ${urlMatches.single()}" else "rules ${urlMatches.joinToString(", ")}"
                "$method $url is refused by this installation's HTTP allow list: the URL matches $rules, " +
                    "which ${if (one) "allows" else "allow"} ${methods.joinToString(", ")} but not $method. " +
                    "Call ${BuiltInTools.HTTP_ALLOW_LIST} to see what may be requested."
            }
        }
}

class HttpToolRulePatternMissingException(val position: Int) : RuntimeException(
    "Rule $position has no URL pattern. Write a regular expression for the whole URL, or remove the rule.",
), Refusal {
    override val arguments get() = mapOf("position" to position)
}

class HttpToolRulePatternTooLongException(val position: Int, val max: Int) : RuntimeException(
    "The URL pattern of rule $position is longer than $max characters.",
), Refusal {
    override val arguments get() = mapOf("position" to position, "max" to max)
}

class HttpToolRulePatternInvalidException(val position: Int, val pattern: String, val reason: String) : RuntimeException(
    "The URL pattern of rule $position is not a regular expression: $reason.",
), Refusal {
    override val arguments get() = mapOf("position" to position, "pattern" to pattern, "reason" to reason)
}

class HttpToolRuleMethodsMissingException(val position: Int) : RuntimeException(
    "Rule $position allows no method. Choose at least one of ${HttpToolPolicy.METHODS.joinToString(", ")}.",
), Refusal {
    override val arguments get() = mapOf("position" to position)
}

class HttpToolRuleMethodUnknownException(val position: Int, val method: String) : RuntimeException(
    "Rule $position lists $method, which the HTTP tools do not send. " +
        "Choose from ${HttpToolPolicy.METHODS.joinToString(", ")}.",
), Refusal {
    override val arguments get() = mapOf("position" to position, "method" to method)
}
