package io.mszymanski.orknux.server.integration

import io.mszymanski.orknux.connector.connection.McpServerRepository
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.server.plugin.Marketplace
import io.mszymanski.orknux.server.plugin.OfferedIntegrationTag
import io.mszymanski.orknux.server.plugin.OfferedRecommendation
import io.mszymanski.orknux.server.security.WebProperties
import io.mszymanski.orknux.server.security.WorkspaceAccess
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.Sort
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

data class IntegrationRecommendations(
    val offered: Boolean,
    val items: List<IntegrationRecommendation>,
    val next: String?,
    val total: Int,
)

/** One card, as the Integrations page draws it; see `integration-recommendations.graphqls`. */
data class IntegrationRecommendation(
    /** Where the page gets its icon - this server's own address, never orknux.ai's - or null where it has none. */
    val icon: String?,
    /** The same for a dark ground; null where the one icon serves both. */
    val iconDark: String?,
    val key: String,
    val name: String,
    val vendor: String?,
    val summary: String,
    val description: String,
    val tags: List<String>,
    val homepage: String?,
    val repository: String?,
    val license: String?,
    val maintainer: String?,
    val popularity: Int?,
    /** Its repository as GitHub reports it - stars, whether archived - or null where it is not on GitHub or not read yet. */
    val github: io.mszymanski.orknux.server.plugin.OfferedGitHubRepository?,
    val kind: String,
    val transport: String?,
    val runsOn: String,
    val sampleTools: List<String>,
    val effort: String,
    val minutes: Int,
    val requirements: List<String>,
    val needsAdmin: Boolean,
    val steps: String,
    val claudePrompt: String,
    val reason: String?,
    val installed: Boolean,
    val tooOld: String?,
)

/**
 * The integrations orknux.ai recommends for a workspace - Blender, KiCad, an
 * IDE - each with where it lives, what setting it up takes, and a prompt for
 * Claude to set it up with the person.
 *
 * Orknux's part ends at handing that prompt over: the page opens the person's
 * own Claude with it, and Claude works through the setup with them on their
 * machine, which is where most of these tools run and where this server
 * cannot act. So the prompt is filled in here with what only this server knows
 * - its address, the workspace's name, the page an MCP server is added on - and
 * nothing secret, because it travels in a link.
 *
 * What orknux.ai is told is coarse: the server's version and the connection
 * types the workspace uses. Whether the workspace already has a card's tool is
 * worked out here, against its MCP servers' names, rather than by sending
 * those names away.
 */
@Controller
class IntegrationRecommendationAPI(
    private val marketplace: Marketplace,
    private val access: WorkspaceAccess,
    private val mcpServers: McpServerRepository,
    private val connections: WorkspaceConnectionRepository,
    private val web: WebProperties,
    private val users: io.mszymanski.orknux.server.user.AppUserRepository,
    @Value("\${orknux.version:unknown}") private val version: String,
) {

    @QueryMapping
    fun integrationRecommendations(
        @Argument workspaceId: Long,
        @Argument search: String?,
        @Argument tag: String?,
        @Argument after: String?,
        @Argument order: IntegrationSort?,
        @Argument ascending: Boolean?,
    ): IntegrationRecommendations {
        val workspace = access.requireVisible(workspaceId)
        if (!marketplace.configured) return IntegrationRecommendations(false, emptyList(), null, 0)
        val servers = mcpServers.findByWorkspaceId(workspaceId, Sort.by("name"))
        val context = linkedMapOf<String, Any?>(
            "server" to version,
            "connectionTypes" to connections.findByWorkspaceId(workspaceId, Sort.by("name")).map { it.type.name }.distinct(),
            "language" to language(),
        )
        val page = marketplace.integrations(
            context, search, tag, after,
            // orknux.ai's own order is asked for by leaving it out, unless it is to run the other way.
            order = order?.takeIf { it != IntegrationSort.RECOMMENDED || ascending != null }?.name,
            ascending = ascending.takeIf { order != null },
        )
            ?: return IntegrationRecommendations(false, emptyList(), null, 0)
        val held = servers.map { normal(it.name) }.toSet()
        val filled = mapOf(
            "orknuxUrl" to base(),
            "workspace" to workspace.name,
            "mcpServersUrl" to base() + "/workspace/$workspaceId/integrations",
            "serverVersion" to version,
        )
        return IntegrationRecommendations(
            offered = true,
            items = page.items.map { card(it, filled, held) },
            next = page.next,
            total = page.total,
        )
    }

    @QueryMapping
    fun integrationTags(): List<OfferedIntegrationTag> =
        if (marketplace.configured) marketplace.integrationTags().orEmpty() else emptyList()

    private fun card(offered: OfferedRecommendation, filled: Map<String, String>, held: Set<String>): IntegrationRecommendation {
        val it = offered.integration
        return IntegrationRecommendation(
            icon = it.icon?.let { address -> iconPath(it.key, address, dark = false) },
            iconDark = it.iconDark?.let { address -> iconPath(it.key, address, dark = true) },
            key = it.key,
            name = it.name,
            vendor = it.vendor,
            summary = it.summary,
            description = it.description,
            tags = it.tags,
            homepage = it.homepage?.takeIf(::web),
            repository = it.repository?.takeIf(::web),
            license = it.license,
            maintainer = it.maintainer,
            popularity = it.popularity,
            github = it.github?.takeIf { repo -> web(repo.url) },
            kind = it.kind,
            transport = it.transport,
            runsOn = it.runsOn,
            sampleTools = it.sampleTools,
            effort = it.effort,
            minutes = it.minutes,
            requirements = it.requirements,
            needsAdmin = it.needsAdmin,
            steps = it.steps,
            claudePrompt = fill(it.claudePrompt, filled).take(PROMPT_LENGTH),
            reason = offered.reason,
            installed = offered.installed || normal(it.key) in held || normal(it.name) in held,
            tooOld = it.minimumServer?.takeIf { newer(it, version) }
                ?.let { needed -> "Its setup is written for Orknux $needed or newer, and this server runs $version." },
        )
    }

    private fun base() = web.baseUrl.trimEnd('/')

    /**
     * This server's address for an icon orknux.ai keeps at [address]. orknux.ai's
     * addresses carry the file's digest - logo-3f9ac1e0b2d4.svg - and never change
     * what they serve, so the file's name goes into ours: a new logo is a new
     * address here too, and a browser may keep each one for a year.
     */
    private fun iconPath(key: String, address: String, dark: Boolean): String {
        val name = java.net.URLEncoder.encode(address.substringAfterLast('/').substringBefore('?'), Charsets.UTF_8)
        return "/api/integrations/${java.net.URLEncoder.encode(key, Charsets.UTF_8)}/icon?v=$name" + if (dark) "&dark=true" else ""
    }

    /** The language the person reads the product in, for orknux.ai's reason line; null while they have not said. */
    private fun language(): String? =
        org.springframework.security.core.context.SecurityContextHolder.getContext().authentication?.name
            ?.let(users::findByUsername)?.language

    companion object {
        /**
         * The longest prompt handed over. A claude-cli:// link carries 5,000
         * characters, and the prompt is URL-encoded into it, so this leaves room.
         */
        const val PROMPT_LENGTH = 4_000

        private val PLACEHOLDER = Regex("""\{\{(\w+)}}""")

        /** The placeholders this server knows, filled; any other is left as it is. */
        fun fill(prompt: String, values: Map<String, String>): String =
            PLACEHOLDER.replace(prompt) { match -> values[match.groupValues[1]] ?: match.value }

        /** Only an http(s) address is put in a link on the page; anything else a card said is dropped. */
        fun web(address: String): Boolean =
            runCatching { java.net.URI.create(address) }.getOrNull()
                ?.let { it.scheme?.lowercase() in setOf("http", "https") && !it.host.isNullOrBlank() } == true

        /** A name as an MCP server and a card are compared by: lower case, letters and digits only. */
        fun normal(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

        /** Whether version [needed] is newer than [running], comparing X.Y.Z numerically; an unreadable one is never newer. */
        fun newer(needed: String, running: String): Boolean {
            val want = parts(needed) ?: return false
            val have = parts(running) ?: return false
            for (i in 0 until maxOf(want.size, have.size)) {
                val a = want.getOrElse(i) { 0 }
                val b = have.getOrElse(i) { 0 }
                if (a != b) return a > b
            }
            return false
        }

        private fun parts(version: String): List<Int>? =
            version.substringBefore('-').split('.').map { it.toIntOrNull() ?: return null }
    }
}

/**
 * An integration's icon, fetched from orknux.ai by the server and handed to the
 * page from here: the browser never talks to orknux.ai. Served as a picture
 * that can do nothing else - no script runs in an SVG drawn as an image, and
 * the policy forbids it even opened on its own - and kept by the browser for
 * a year, since its address changes whenever the logo does.
 */
@org.springframework.web.bind.annotation.RestController
class IntegrationIconAPI(private val marketplace: io.mszymanski.orknux.server.plugin.Marketplace) {

    @org.springframework.web.bind.annotation.GetMapping("/api/integrations/{key}/icon")
    fun icon(
        @org.springframework.web.bind.annotation.PathVariable key: String,
        @org.springframework.web.bind.annotation.RequestParam(required = false, defaultValue = "false") dark: Boolean,
    ): org.springframework.http.ResponseEntity<ByteArray> {
        val icon = marketplace.icon(key, dark) ?: return org.springframework.http.ResponseEntity.notFound().build()
        return org.springframework.http.ResponseEntity.ok()
            .contentType(org.springframework.http.MediaType.parseMediaType(icon.type))
            // The address names the file's digest (see iconPath), so what it serves never changes.
            .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofDays(365)).cachePrivate().immutable())
            .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox")
            .header("X-Content-Type-Options", "nosniff")
            .body(icon.bytes)
    }
}

/** How a person may order the cards: orknux.ai's judgement, how used, or by name. Named as orknux.ai names them. */
enum class IntegrationSort { RECOMMENDED, POPULAR, NAME, NEWEST }
