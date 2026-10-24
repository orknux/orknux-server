package io.mszymanski.orknux.server.security

import io.mszymanski.orknux.connector.Addresses
import io.mszymanski.orknux.server.attachment.InstallationSetting
import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.graphql.Refusal
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.time.OffsetDateTime

/**
 * Where this installation is reached from: what every link it writes into
 * something that leaves it is built on - a mail, an answer an agent pastes into
 * Slack, `app_link`.
 *
 * Admin -> Settings -> Address overrides [WebProperties.baseUrl]
 * (ORKNUX_BASE_URL), so an installation moved behind a new name is corrected
 * from the browser rather than by a restart with a changed environment. Read
 * here and nowhere else: a reader of the property alone would go on linking to
 * the old name after the administrator had changed it.
 *
 * @param settings null only where a test builds this by hand, which then reads
 *   the configured address alone.
 */
@Service
class InstallationAddress(
    private val web: WebProperties,
    private val settings: InstallationSettingRepository?,
) {

    /**
     * The address links are built on, with no trailing slash; empty where
     * neither the setting nor the configuration names one - each caller decides
     * what that means for what it writes.
     */
    fun base(): String = stored() ?: configured()

    /** What the configuration says - ORKNUX_BASE_URL. */
    fun configured(): String = web.baseUrl.trim().trimEnd('/')

    /** What Admin Settings set, or null where it set nothing. */
    fun stored(): String? = settings?.findByIdOrNull(NAME)?.value?.trim()?.trimEnd('/')?.ifEmpty { null }

    /**
     * Sets the address, or clears it back to the configured one when [url] is
     * blank. Typed without a scheme it is taken as http ([Addresses.withScheme]);
     * refused unless it is then an absolute http or https address - a link
     * built on anything else opens nowhere.
     */
    @Transactional
    fun set(url: String?, by: String) {
        val repository = requireNotNull(settings)
        val given = Addresses.withScheme(url.orEmpty()).trimEnd('/')
        if (given.isEmpty()) {
            repository.deleteById(NAME)
            return
        }
        if (!isAddress(given)) throw InstallationAddressInvalidException(given)
        val held = repository.findByIdOrNull(NAME) ?: InstallationSetting(name = NAME)
        held.value = given
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = by
        repository.save(held)
    }

    private fun isAddress(given: String): Boolean = runCatching {
        val uri = URI(given)
        (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank() &&
            uri.rawQuery == null && uri.rawFragment == null && given.length <= MAX_LENGTH
    }.getOrDefault(false)

    companion object {
        const val NAME = "web.base.url"

        /** The settings column's length. */
        const val MAX_LENGTH = 500

        /** The configured address alone, for a test that builds its collaborators by hand. */
        fun fixed(url: String): InstallationAddress = InstallationAddress(WebProperties(baseUrl = url), null)
    }
}

class InstallationAddressInvalidException(val url: String) : RuntimeException(
    "$url is not an address this installation can be reached at. Give an http or https address, " +
        "such as https://orknux.example.com.",
), Refusal {
    override val arguments get() = mapOf("url" to url)
}
