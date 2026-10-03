package io.mszymanski.orknux.server.plugin

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Installing from the marketplace, and switching a plugin off.
 *
 * The catalog is read through: what this server stores is not what a listing
 * claimed but what the *code* said when it was loaded — so the test's stub
 * offers a plugin whose declaration differs from the listing's prose, and the
 * assertions are on the code's version of events.
 *
 * Three claims carry the feature and each is a test: an install is refused
 * until what the plugin ships is accepted; an accepted install records where
 * it came from, so an update can be offered; and a plugin switched off keeps
 * everything and offers nothing.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class MarketplaceInstallTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val catalog: MarketplaceAPI,
    @Autowired val plugins: PluginRepository,
    @Autowired val libraries: PluginLibraryRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val tools: io.mszymanski.orknux.server.agent.PluginToolCaller,
    @Autowired val audit: WorkspaceAuditRepository,
    /** For pointing this installation's marketplace through a proxy; see the test at the end. */
    @Autowired val proxyRules: io.mszymanski.orknux.connector.proxy.ProxyRuleRepository,
    @Autowired val proxies: io.mszymanski.orknux.connector.proxy.ProxyRouter,
) {

    @BeforeEach
    fun reset() {
        plugins.deleteAll()
        functions.deleteAll()
        audit.deleteAll()
        offeredVersion = "1.0.0"
        offeredDigest = digestOf(plugin)
        offeredIcon = "http://${where()}/icons/greeter.svg"
        offeredAvailable = true
        refuseNewFields = false
        refuseTags = false
        offeredCategory = "chat"
        proxyRules.deleteAll()
        proxies.reload()
        relayed.clear()
    }

    @Test
    fun `the catalog says what is on offer, and what is installed here`() {
        val before = catalog.marketplacePlugins().single()

        assertThat(before.key).isEqualTo("greeter")
        assertThat(before.author).isEqualTo("Orknux")
        /*
         * The drawing, not the URL it is at.
         *
         * A screen putting the marketplace's URL in an `<img>` is the one call
         * the catalog makes that this server does not - so on an installation
         * whose egress is a proxy the rules were right, the catalog loaded,
         * and every icon on the page was a broken square.
         */
        assertThat(before.icon).startsWith("<svg").contains("greeter-face")
        assertThat(before.installed).isFalse()
        assertThat(before.updatable).isFalse()
    }

    @Test
    fun `an install is refused until what the plugin ships is accepted, and nothing is stored`() {
        val refused = catalog.installMarketplacePlugin("greeter", accept = null)

        assertThat(refused.plugin).isNull()
        assertThat(refused.needsLibraries).containsExactly("lib/words.js")
        assertThat(refused.message).contains("library file")
        assertThat(plugins.findAll()).isEmpty()
    }

    @Test
    fun `an accepted install loads the plugin with its files, and records where it came from`() {
        val installed = catalog.installMarketplacePlugin("greeter", accept = "lib/words.js")

        val plugin = requireNotNull(installed.plugin)
        assertThat(plugin.key).isEqualTo("greeter")
        assertThat(plugin.libraries).containsExactly("lib/words.js")
        assertThat(plugin.marketplaceKey).isEqualTo("greeter")
        assertThat(plugin.marketplaceVersion).isEqualTo("1.0.0")
        assertThat(plugin.enabled).isTrue()
        /*
         * What the listing said about it, kept.
         *
         * This plugin ships no `plugin.json`, and for a long time that meant a
         * marketplace install stored no author and no summary at all - the
         * catalog knew both and nothing asked it, so the Installed table drew
         * an em dash under Author for every plugin anybody installed.
         */
        /*
         * The catalog's words, and the catalog outranks the file.
         *
         * This plugin ships a `plugin.json` that disagrees on every point, and
         * the listing wins each of them. That way round because this is an
         * install *from the catalog*: the catalog has an account that
         * published this version, and a manifest has a string somebody typed.
         * The first-party plugins all ship `"author": "Orknux"` while their
         * listings name the person who published them - so the file's word put
         * a different name in the row than the one on the page the plugin was
         * installed from.
         *
         * For a long time neither was kept at all: the author column was empty
         * for every marketplace install, because the catalog knew and nothing
         * asked it.
         */
        assertThat(plugin.author).isEqualTo("Orknux")
        assertThat(plugin.summary).isEqualTo("Says hello.")
        assertThat(plugin.name).isEqualTo("Greeter")
        assertThat(plugin.marketplaceVersion).isEqualTo("1.0.0")
        /*
         * The face came across rather than being pointed at: what is stored is
         * the drawing, so this installation draws it whether or not it can
         * reach the marketplace again.
         */
        assertThat(plugin.icon).startsWith("<svg").contains("greeter-face")

        // What it declares is callable, under the plugin's own prefix.
        assertThat(functions.findAll().map { it.name }).contains("greeter_greet")

        // And the catalog now says so, without an update to offer.
        val listing = catalog.marketplacePlugins().single()
        assertThat(listing.installed).isTrue()
        assertThat(listing.installedVersion).isEqualTo("1.0.0")
        assertThat(listing.updatable).isFalse()
    }

    /**
     * An update is the same act as an install, and the catalog is what says
     * one is available — the version recorded here against the one offered.
     */
    @Test
    fun `a newer version in the catalog is offered as an update, and installs over the old one`() {
        catalog.installMarketplacePlugin("greeter", accept = "lib/words.js")

        offeredVersion = "1.1.0"
        val listing = catalog.marketplacePlugins().single()
        assertThat(listing.updatable).describedAs("the catalog has moved on and this installation has not").isTrue()

        val updated = requireNotNull(catalog.installMarketplacePlugin("greeter", accept = "lib/words.js").plugin)
        assertThat(updated.marketplaceVersion).isEqualTo("1.1.0")
        assertThat(plugins.findAll()).describedAs("updated in place, not installed twice").hasSize(1)
        assertThat(libraries.findByPluginIdOrderByPositionAsc(updated.id.toLong()).map { it.path })
            .containsExactly("lib/words.js")
    }

    /**
     * A plugin loaded from a file is not offered an update to the version it
     * already is.
     *
     * Nothing records a marketplace version for a file install - there was no
     * marketplace in it - so holding that against the catalog said "installed:
     * nothing, and here is an update" about a plugin whose own version matched
     * the offer exactly. On the screen that read as *0.13.1, update to
     * 0.13.1*, which is the catalog telling somebody to reinstall the bytes
     * they have.
     */
    @Test
    fun `a plugin loaded from a file is current when its own version matches the catalog`() {
        plugins.save(
            io.mszymanski.orknux.server.plugin.Plugin(
                key = "greeter",
                name = "Greeter",
                filename = "greeter.js",
                source = HAND_LOADED,
                sizeBytes = HAND_LOADED.length.toLong(),
                sha256 = digestOf(HAND_LOADED),
                apiVersion = 1,
                // Loaded by hand: no marketplace, and its own claim instead.
                marketplaceKey = null,
                marketplaceVersion = null,
                version = "1.0.0",
            ),
        )

        val listing = catalog.marketplacePlugins().single()

        assertThat(listing.installed).isTrue()
        assertThat(listing.installedVersion)
            .describedAs("what is here, however it got here")
            .isEqualTo("1.0.0")
        assertThat(listing.updatable)
            .describedAs("the same version is not an update")
            .isFalse()
    }

    /** And one the catalog has moved past is still offered the newer bytes. */
    @Test
    fun `a plugin loaded from a file behind the catalog is updatable`() {
        plugins.save(
            io.mszymanski.orknux.server.plugin.Plugin(
                key = "greeter",
                name = "Greeter",
                filename = "greeter.js",
                source = HAND_LOADED,
                sizeBytes = HAND_LOADED.length.toLong(),
                sha256 = digestOf(HAND_LOADED),
                apiVersion = 1,
                marketplaceKey = null,
                marketplaceVersion = null,
                version = "0.9.0",
            ),
        )

        val listing = catalog.marketplacePlugins().single()

        assertThat(listing.installedVersion).isEqualTo("0.9.0")
        assertThat(listing.updatable).isTrue()
    }

    /* --------------------------------------------- what the catalog vouches for */

    /**
     * The bytes that arrive are the bytes the catalog published, or nothing is
     * installed.
     *
     * Everything before this trusted the download completely: the URL is the
     * catalog's own and the request goes through this installation's proxy
     * rules, which is good and is not the same as knowing the file did not
     * change on the way.
     */
    @Test
    fun `a download that is not what the catalog published is refused, and nothing is stored`() {
        offeredDigest = digestOf("export default class Nothing {}")

        val refused = org.junit.jupiter.api.assertThrows<PluginDigestMismatchException> {
            catalog.installMarketplacePlugin("greeter", accept = "lib/words.js")
        }

        assertThat(refused.message)
            .describedAs("both hashes: a proxy rewriting a response and a half-finished transfer look alike")
            .contains(offeredDigest)
            .contains(digestOf(plugin))
        assertThat(plugins.findAll()).isEmpty()
        assertThat(functions.findAll()).isEmpty()
    }

    /**
     * And the page is told so in a sentence, not as INTERNAL_ERROR and an id -
     * which is how a Prometheus update a stale cache answered reached somebody.
     * The code is what the interface translates it from.
     */
    @Test
    fun `a download that is not what the catalog published reaches the page as a sentence`() {
        offeredDigest = digestOf("export default class Nothing {}")

        graphQlTester.document(
            """mutation { installMarketplacePlugin(key: "greeter", accept: "lib/words.js") { plugin { key } } }""",
        )
            .execute()
            .errors()
            .satisfy { errors ->
                val refusal = errors.single()
                assertThat(refusal.extensions["code"]).isEqualTo("PluginDigestMismatch")
                assertThat(refusal.message).contains("did not arrive as the marketplace published it")
                assertThat(refusal.message).contains("try again in a few minutes")
            }
        assertThat(plugins.findAll()).isEmpty()
    }

    /**
     * A release the catalog remembers and no longer holds is refused before
     * anything is fetched - rather than as a 404 halfway through, which reads
     * as the marketplace being broken.
     */
    @Test
    fun `a version whose files the catalog no longer holds is refused by name`() {
        offeredAvailable = false

        val refused = org.junit.jupiter.api.assertThrows<PluginReleaseGoneException> {
            catalog.installMarketplacePlugin("greeter", accept = "lib/words.js")
        }

        assertThat(refused.message).contains("greeter").contains("1.0.0").contains("newer version")
        assertThat(plugins.findAll()).isEmpty()
    }

    /**
     * A marketplace that will not answer the newer fields is still a
     * marketplace.
     *
     * The fields a listing carries grew and the marketplaces this server talks
     * to did not grow at the same moment. A query naming a field the far end
     * cannot fill fails entirely - no answer, not a thinner one - so the
     * client asks again for the fields that have always been there, and the
     * Catalog screen goes on working. Without a digest, which is then a check
     * nobody can perform rather than a reason to refuse an install.
     */
    /**
     * And a marketplace still on `category` has its one word read as a tag.
     *
     * Which is what it was: one word for what a plugin is for. Without this
     * rung the shelf loses its filter entirely against every marketplace that
     * has not switched yet - the field is gone, not the plugins' need to be
     * found by what they do.
     */
    @Test
    fun `a marketplace still filing plugins under one category has it read as a tag`() {
        refuseTags = true

        val listing = catalog.marketplacePlugins().single()

        assertThat(listing.tags).containsExactly("chat")
        assertThat(listing.versions).isEmpty()
    }

    @Test
    fun `a marketplace that refuses the newer fields still lists and still installs`() {
        refuseNewFields = true

        val listing = catalog.marketplacePlugins().single()
        assertThat(listing.key).isEqualTo("greeter")
        /*
         * The history is gone and the tags are not.
         *
         * A rung at a time: the marketplace this was written against declares
         * `versions` and answers null for it, and asking for nothing new over
         * that would hide tags it answers perfectly well.
         */
        assertThat(listing.tags).containsExactly("chat", "search")
        assertThat(listing.versions).isEmpty()

        val installed = requireNotNull(catalog.installMarketplacePlugin("greeter", accept = "lib/words.js").plugin)
        assertThat(installed.marketplaceVersion).isEqualTo("1.0.0")
    }

    /** What the plugin is for, and what it has shipped, reach the screen. */
    @Test
    fun `the listing carries its tags and its releases`() {
        val listing = catalog.marketplacePlugins().single()

        assertThat(listing.tags)
            .describedAs("more than one, because a plugin is usually more than one thing")
            .containsExactly("chat", "search")
        assertThat(listing.versions.map { it.version }).containsExactly("1.0.0", "0.9.0")
        assertThat(listing.versions.map { it.available })
            .describedAs("the older one is remembered and its files are gone")
            .containsExactly(true, false)
        assertThat(listing.versions.first().files).isEqualTo(2)
    }

    /* ------------------------------------------------- where the bytes travel */

    /**
     * Both of the marketplace's doors go through the proxy a rule names.
     *
     * The catalog is a GraphQL call and an install is a series of file
     * fetches, and they are made by two different clients in two different
     * classes - so "the marketplace is proxied" is two claims, and a
     * refactoring that moved one of them off the seam would leave an
     * installation able to browse a catalog it cannot install from, or the
     * other way about.
     *
     * Asserted against a real proxy on the loopback address rather than
     * against configuration. A request routed through a forward proxy arrives
     * with the whole URL on its request line rather than just the path, so a
     * recorded absolute URL is proof the bytes went through there and not
     * straight to the stub - which is a thing no settings object can show.
     */
    @Test
    fun `the catalog and the files it installs both go through the proxy a rule names`() {
        proxyRules.save(
            io.mszymanski.orknux.connector.proxy.ProxyRule(
                name = "the stub marketplace",
                pattern = Regex.escape(where()),
                proxyHost = relay.address.hostString,
                proxyPort = relay.address.port,
                enabled = true,
            ),
        )
        proxies.reload()

        val listing = catalog.marketplacePlugins().single()
        assertThat(listing.key).isEqualTo("greeter")
        assertThat(relayed.filter { "/graphql" in it })
            .describedAs("the catalog query, on the proxy's request line as a whole URL")
            .isNotEmpty()

        val installed = requireNotNull(catalog.installMarketplacePlugin("greeter", accept = "lib/words.js").plugin)
        assertThat(installed.marketplaceVersion).isEqualTo("1.0.0")

        assertThat(relayed.filter { it.endsWith("/greeter.js") })
            .describedAs("the plugin itself, fetched by a different client in a different class")
            .isNotEmpty()
        assertThat(relayed.filter { it.endsWith("/words.js") })
            .describedAs("and the library it ships with")
            .isNotEmpty()
        assertThat(relayed.filter { it.endsWith("/greeter.svg") })
            .describedAs("and its face, which the catalog fetched before a browser could be asked to")
            .isNotEmpty()

        /*
         * Intact, which is the other half of what a proxy must not break. The
         * install hashes what arrived against what the catalog published, so a
         * relay that mangled a byte would be a digest failure rather than a
         * quiet difference - and a proxy that swallowed the install key would
         * be a 401 from the stub.
         */
        assertThat(plugins.findAll()).hasSize(1)
    }

    /**
     * An icon the catalog points somewhere else is left as the URL it is.
     *
     * The marketplace's own host and nowhere else, because a catalog is
     * somebody else's JSON: a listing whose icon pointed at an address on this
     * network would otherwise be this server fetching it and handing the
     * answer to a screen. Left as it stands rather than dropped - that is what
     * the screen did with every icon before, so it is no worse than yesterday.
     */
    @Test
    fun `an icon hosted away from the marketplace is not fetched by this server`() {
        offeredIcon = "https://elsewhere.invalid/icon.svg"

        val listing = catalog.marketplacePlugins().single()

        assertThat(listing.icon).isEqualTo("https://elsewhere.invalid/icon.svg")
    }

    @Test
    fun `a plugin switched off keeps everything and offers nothing`() {
        val installed = requireNotNull(catalog.installMarketplacePlugin("greeter", accept = "lib/words.js").plugin)
        assertThat(tools.all().map { it.name }).contains("greeter_greet")

        val off = catalog.setPluginEnabled(installed.id.toLong(), enabled = false)

        assertThat(off.enabled).isFalse()
        assertThat(tools.all().map { it.name })
            .describedAs("its tools leave the agents' menus")
            .doesNotContain("greeter_greet")
        assertThat(functions.findAll().map { it.name })
            .describedAs("and its functions stay, so a graph naming one still draws")
            .contains("greeter_greet")

        // Back on puts it back, which is the whole point of it being a switch.
        val on = catalog.setPluginEnabled(installed.id.toLong(), enabled = true)
        assertThat(on.enabled).isTrue()
        assertThat(tools.all().map { it.name }).contains("greeter_greet")
    }

    companion object {

        /** What the stub catalog currently offers; a test moves it. */
        var offeredVersion = "1.0.0"

        /** Something to stand in for a plugin somebody loaded from a file. */
        const val HAND_LOADED = "export default class G extends OrknuxPlugin { id() { return 'greeter' } }"

        /** What a marketplace still on the old field files it under. */
        var offeredCategory = "chat"

        /** Where the listing says its face is; a test moves it off the marketplace. */
        var offeredIcon = ""

        /** What the catalog says the plugin's bytes hash to; a test spoils it. */
        var offeredDigest = ""

        /** Whether the catalog still holds this version's files. */
        var offeredAvailable = true

        /**
         * A marketplace that has the older fields and not the newer ones.
         *
         * Refused the way a real one does - an `errors` payload over a 200,
         * which fails the whole query rather than thinning the answer.
         */
        var refuseNewFields = false

        /**
         * A marketplace that has `category` and has never heard of `tags`,
         * which is every one deployed before the switch.
         */
        var refuseTags = false

        /** The same hash the server computes over a downloaded file. */
        fun digestOf(source: String): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(source.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        private val plugin = """
            import { HELLO } from './lib/words.js';

            export default class Greeter extends OrknuxPlugin {
              id() { return 'greeter'; }
              apiVersion() { return 1; }
              libraries() { return ['lib/words.js']; }
              functions() {
                return [new OrknuxFunction({
                  name: 'greet',
                  params: [{ name: 'who', type: 'string' }],
                  returnType: 'string',
                  run: (who) => HELLO + ', ' + who,
                })];
              }
              tools() { return [new OrknuxFunctionTool({ function: 'greet' })]; }
            }
        """.trimIndent()

        private val words = "export const HELLO = 'hello';"

        /**
         * One stub standing in for both halves of the marketplace: the GraphQL
         * catalog, and the raw files an install fetches from beside the URL it
         * answered with.
         */
        private val stub: HttpServer =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                fun answer(exchange: HttpExchange, body: String) {
                    val bytes = body.toByteArray(StandardCharsets.UTF_8)
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                    exchange.close()
                }
                createContext("/graphql") { exchange ->
                    if (!keyed(exchange)) return@createContext refuse(exchange)
                    val asked = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
                    if (refuseTags && asked.contains(" tags ")) {
                        return@createContext answer(
                            exchange,
                            """{"errors":[{"message":"Field 'tags' is undefined"}]}""",
                        )
                    }
                    if ((refuseNewFields || refuseTags) && asked.contains("versions {")) {
                        return@createContext answer(
                            exchange,
                            """{"errors":[{"message":"Field 'versions' is undefined"}]}""",
                        )
                    }
                    val history = """
                        "versions":[
                          {"version":"$offeredVersion","published":"2026-09-19","replaced":"2026-09-19",
                           "digest":"$offeredDigest","files":2,"available":$offeredAvailable},
                          {"version":"0.9.0","published":"2026-08-01","replaced":"2026-08-01",
                           "digest":"","files":1,"available":false}
                        ]
                    """.trimIndent()
                    /*
                     * Only what was asked for, the way a real marketplace
                     * answers: a client that fell back to the older fields
                     * gets the older fields, so the fallback is measured
                     * rather than papered over by a stub that always
                     * answers everything.
                     */
                    val extras = when {
                        asked.contains("versions {") -> """"tags":["chat","search"],$history"""
                        asked.contains(" tags ") -> """"tags":["chat","search"]"""
                        // The marketplace that has not switched yet: one word
                        // for what a plugin is for, and no tags field at all.
                        asked.contains(" category ") -> """"category":"$offeredCategory""""
                        else -> """"tags":[]"""
                    }
                    val offering = """
                        {"key":"greeter","name":"Greeter","author":"Orknux","summary":"Says hello.",
                         "description":"# Greeter","version":"$offeredVersion",
                         "url":"http://${where()}/plugins/greeter/greeter.js",
                         "icon":"$offeredIcon",
                         "downloads":7,"rating":null,"reviews":0,"published":"2026-09-19",
                         $extras}
                    """.trimIndent()
                    // The two queries the client makes, told apart by name.
                    val data = if (asked.contains("marketplacePlugin(")) {
                        """{"marketplacePlugin":$offering}"""
                    } else {
                        """{"marketplacePlugins":[$offering]}"""
                    }
                    answer(exchange, """{"data":$data}""")
                }
                createContext("/plugins/greeter/greeter.js") {
                    if (keyed(it)) answer(it, plugin) else refuse(it)
                }
                createContext("/plugins/greeter/lib/words.js") {
                    if (keyed(it)) answer(it, words) else refuse(it)
                }
                /*
                 * A manifest that disagrees with the listing on every point,
                 * so which one the row keeps is a fact this test establishes
                 * rather than one it happens not to exercise.
                 */
                createContext("/plugins/greeter/plugin.json") {
                    if (keyed(it)) {
                        answer(
                            it,
                            """{"key":"greeter","name":"Not This","summary":"Nor this.",
                               "author":"Someone Else","version":"9.9.9"}""",
                        )
                    } else {
                        refuse(it)
                    }
                }
                /*
                 * The one door that answers anybody, and deliberately: a
                 * listing's icon is a picture on a page a person is reading,
                 * so the marketplace does not key it. Unguarded here so the
                 * install that fetches it keeps working without a header -
                 * and so a change that started demanding one is caught.
                 */
                createContext("/icons/greeter.svg") {
                    answer(it, """<svg xmlns="http://www.w3.org/2000/svg" id="greeter-face"><circle r="8"/></svg>""")
                }
                start()
            }

        fun where() = "${stub.address.hostString}:${stub.address.port}"

        /** Every absolute URL the relay was asked to fetch, newest last. */
        val relayed = java.util.concurrent.CopyOnWriteArrayList<String>()

        /**
         * A forward proxy, standing where an installation's own would.
         *
         * It really fetches and really relays, headers and body both, because
         * the two things under test on the far side of it are a keyed request
         * and a digest: a stub that answered on the target's behalf would
         * prove the request arrived here and nothing about whether what came
         * back was usable.
         */
        private val relay: HttpServer =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                createContext("/") { exchange ->
                    // Whole URL on the request line: the one difference
                    // between a proxied request and a direct one.
                    val target = exchange.requestURI.toString()
                    relayed += target
                    val sent = exchange.requestBody.readBytes()
                    val building = java.net.http.HttpRequest.newBuilder(java.net.URI.create(target))
                        .method(
                            exchange.requestMethod,
                            if (sent.isEmpty()) {
                                java.net.http.HttpRequest.BodyPublishers.noBody()
                            } else {
                                java.net.http.HttpRequest.BodyPublishers.ofByteArray(sent)
                            },
                        )
                    // Everything the caller sent, minus what belongs to this
                    // hop: the install key has to reach the stub or its door
                    // answers 401, and Host/Content-Length are recomputed.
                    exchange.requestHeaders
                        .filterKeys { it.lowercase() !in setOf("host", "content-length", "connection", "upgrade") }
                        .forEach { (name, values) -> values.forEach { building.header(name, it) } }
                    val answer = java.net.http.HttpClient.newHttpClient()
                        .send(building.build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray())
                    exchange.sendResponseHeaders(answer.statusCode(), answer.body().size.toLong())
                    exchange.responseBody.use { it.write(answer.body()) }
                    exchange.close()
                }
                start()
            }

        /**
         * The secret both ends share, for the length of this test.
         *
         * The stub checks every request for the day's HMAC of it, so a door
         * that stopped sending the header fails here - which is the whole
         * reason the stub bothers rather than answering anything that asks.
         */
        const val SECRET = "a-shared-secret"

        /** Today's value, computed the way the contract writes it. */
        private fun today(): String =
            javax.crypto.Mac.getInstance("HmacSHA256").run {
                init(javax.crypto.spec.SecretKeySpec(SECRET.toByteArray(), "HmacSHA256"))
                doFinal(
                    java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString().toByteArray(),
                ).joinToString("") { "%02x".format(it) }
            }

        private fun keyed(exchange: com.sun.net.httpserver.HttpExchange): Boolean =
            exchange.requestHeaders.getFirst("X-Orknux-Install-Key") == today()

        /** A bare 401, the way the marketplace refuses: no body, nothing read. */
        private fun refuse(exchange: com.sun.net.httpserver.HttpExchange) {
            exchange.responseHeaders.add("WWW-Authenticate", "Orknux-Install-Key")
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }

        @JvmStatic
        @AfterAll
        fun stop() = stub.stop(0)

        @JvmStatic
        @DynamicPropertySource
        fun marketplace(registry: DynamicPropertyRegistry) {
            registry.add("orknux.marketplace.url") { "http://${where()}/graphql" }
            registry.add("orknux.marketplace.install-key") { SECRET }
        }
    }
}
