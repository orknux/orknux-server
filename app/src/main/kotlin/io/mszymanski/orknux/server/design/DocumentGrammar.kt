package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.server.attachment.InstallationSetting
import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.languagetool.JLanguageTool
import org.languagetool.Languages
import org.languagetool.remote.RemoteLanguageTool
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Grammar and spelling for the document editor, in the languages the interface
 * is read in, from LanguageTool.
 *
 * The library by default: a language's rules are loaded the first time a text
 * in it is checked, which costs a few seconds and a few hundred megabytes of
 * heap for that language and nothing before, so an installation where nobody
 * writes Polish never loads Polish. A [JLanguageTool] is not safe to share
 * between threads, so each language's is used by one check at a time.
 *
 * An administrator may point the installation at a LanguageTool server
 * instead - their own, or LanguageTool's - in Admin Settings or through
 * `ORKNUX_GRAMMAR_SERVER_URL`; then nothing is loaded here and the text is
 * sent there through LanguageTool's own HTTP client.
 *
 * Nothing is stored: the editor sends a paragraph, and is answered with where
 * the mistakes in it are, what is wrong and what could go there instead.
 */
@Controller
class DocumentGrammar(
    private val settings: InstallationSettingRepository,
    private val access: WorkspaceAccess,
    private val audit: WorkspaceAuditRecorder,
    @Value("\${orknux.grammar.server-url:}") private val configuredServer: String,
    private val tuning: Tuning = Tuning.DEFAULTS,
) {

    private val local = ConcurrentHashMap<String, JLanguageTool>()

    /**
     * Where the mistakes in [text] are, written in [language] (`en`, `pl`).
     * Offsets are characters into [text].
     */
    @QueryMapping
    fun grammarCheck(@Argument language: String, @Argument text: String): List<GrammarMatchView> {
        access.requireSignedIn()
        val code = DocumentLanguages.of(language) ?: throw DocumentSettingInvalidException("language", language, DocumentLanguages.ALL.joinToString(" or "))
        val most = tuning[Tunable.GRAMMAR_TEXT_MOST]
        if (text.length > most) throw GrammarTextTooLongException(text.length, most)
        if (text.isBlank()) return emptyList()
        return check(code, text)
    }

    /** What the editor needs to know before it asks: how long to wait, how much it may send, in which languages. */
    @QueryMapping
    fun grammarSettings(): GrammarSettingsView {
        access.requireSignedIn()
        return GrammarSettingsView(
            pauseMillis = tuning[Tunable.GRAMMAR_PAUSE_MILLIS],
            textMost = tuning[Tunable.GRAMMAR_TEXT_MOST],
            languages = DocumentLanguages.ALL,
            server = server(),
            serverConfigured = configuredServer.trim().ifEmpty { null },
        )
    }

    /** Points grammar checking at a LanguageTool server; empty goes back to the library, or to what the file says. */
    @MutationMapping
    @Transactional
    fun setGrammarServer(@Argument url: String?): GrammarSettingsView {
        access.requireAdmin()
        val given = url?.trim().orEmpty()
        if (given.isNotEmpty()) {
            val parsed = runCatching { URI(given) }.getOrNull()
            if (parsed == null || parsed.scheme !in setOf("http", "https") || parsed.host.isNullOrBlank()) throw GrammarServerInvalidException(given)
        }
        val held = settings.findByIdOrNull(SERVER) ?: InstallationSetting(name = SERVER)
        held.value = given
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = SecurityContextHolder.getContext().authentication?.name ?: "orknux"
        settings.save(held)
        audit.record(null, WorkspaceAuditCategory.WORKSPACE, if (given.isEmpty()) "Grammar is checked by the built-in LanguageTool" else "Grammar is checked by the LanguageTool server at $given")
        return grammarSettings()
    }

    /** The server the text goes to, or null for the library here. */
    fun server(): String? {
        val held = settings.findByIdOrNull(SERVER)?.value
        return (held ?: configuredServer).trim().ifEmpty { null }
    }

    fun check(language: String, text: String): List<GrammarMatchView> {
        val remote = server()
        return if (remote != null) remote(remote, language, text) else local(language, text)
    }

    private fun local(language: String, text: String): List<GrammarMatchView> {
        val tool = local.computeIfAbsent(language) {
            val started = System.nanoTime()
            JLanguageTool(Languages.getLanguageForShortCode(VARIANTS.getValue(it))).also {
                log.info("LanguageTool loaded {} in {} ms", language, (System.nanoTime() - started) / 1_000_000)
            }
        }
        val found = synchronized(tool) { tool.check(text) }
        return found.map { match ->
            GrammarMatchView(
                offset = match.fromPos,
                length = match.toPos - match.fromPos,
                message = match.message.replace(TAGS, ""),
                shortMessage = match.shortMessage?.takeIf { it.isNotBlank() },
                replacements = match.suggestedReplacements.take(REPLACEMENTS),
                rule = match.rule.id,
                spelling = match.rule.isDictionaryBasedSpellingRule,
            )
        }
    }

    private fun remote(url: String, language: String, text: String): List<GrammarMatchView> {
        val result = try {
            RemoteLanguageTool(URI(url).toURL()).check(text, VARIANTS.getValue(language))
        } catch (failure: Exception) {
            log.warn("The LanguageTool server at {} could not check a text: {}", url, failure.message)
            throw GrammarServerUnreachableException(url)
        }
        return result.matches.map { match ->
            GrammarMatchView(
                offset = match.errorOffset,
                length = match.errorLength,
                message = match.message.replace(TAGS, ""),
                shortMessage = match.shortMessage.orElse(null)?.takeIf { it.isNotBlank() },
                replacements = match.replacements.orElse(emptyList()).take(REPLACEMENTS),
                rule = match.ruleId,
                spelling = match.locQualityIssueType.orElse("") == "misspelling",
            )
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(DocumentGrammar::class.java)

        /** Where an administrator's choice of server is kept. */
        const val SERVER = "grammar.server.url"

        /** The variant each language is checked as: one with a speller, which a bare `en` has not. */
        val VARIANTS = mapOf("en" to "en-US", "pl" to "pl-PL")

        /** How many replacements a match offers. A list a person reads, not a behaviour. */
        const val REPLACEMENTS = 5

        /** LanguageTool marks a suggestion inside its message as <suggestion>; the editor shows plain words. */
        val TAGS = Regex("</?suggestion>")
    }
}

/** One mistake: where, what is wrong, what could go there instead. */
data class GrammarMatchView(
    val offset: Int,
    val length: Int,
    val message: String,
    val shortMessage: String?,
    val replacements: List<String>,
    val rule: String,
    val spelling: Boolean,
)

data class GrammarSettingsView(
    val pauseMillis: Int,
    val textMost: Int,
    val languages: List<String>,
    /** The LanguageTool server the text goes to; null where the built-in library checks it. */
    val server: String?,
    /** What the file says - ORKNUX_GRAMMAR_SERVER_URL. */
    val serverConfigured: String?,
)

class GrammarTextTooLongException(val characters: Int, val most: Int) :
    RuntimeException("A grammar check takes at most $most characters, and this text is $characters"), Refusal {
    override val arguments get() = mapOf("characters" to characters, "most" to most)
}

class GrammarServerInvalidException(val url: String) :
    RuntimeException("$url is not an http or https address of a LanguageTool server"), Refusal {
    override val arguments get() = mapOf("url" to url)
}

class GrammarServerUnreachableException(val url: String) :
    RuntimeException("The LanguageTool server at $url did not answer"), Refusal {
    override val arguments get() = mapOf("url" to url)
}
