package io.mszymanski.orknux.server.plugin

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.action.FunctionScope
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.script.PluginBundle
import io.mszymanski.orknux.workflow.script.PluginCapability
import io.mszymanski.orknux.workflow.script.PluginInspection
import io.mszymanski.orknux.workflow.script.PluginLibraryFile
import io.mszymanski.orknux.workflow.script.PluginPermission
import io.mszymanski.orknux.workflow.script.PluginRunner
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.OffsetDateTime

/**
 * Loading a plugin into the installation, and taking it out again.
 *
 * Administrators only, all of it: a plugin is installation-wide, so this is not a
 * workspace's decision to make.
 *
 * The upload is REST for the same reason attachments are — what crosses is a
 * file, and a multipart form is what a browser produces from a file picker.
 * Listing and unloading are GraphQL like everything else.
 *
 * The plugin is loaded and questioned as it arrives - what it calls itself, what
 * it offers, what it has to be told - and refused if it cannot answer. What each
 * workspace answers its parameters with is not decided here: see
 * [WorkspacePluginAPI], further down, for whose decision that is.
 */
@RestController
class PluginUploadAPI(
    private val plugins: PluginRepository,
    private val access: WorkspaceAccess,
    private val runner: PluginRunner,
    private val declarations: PluginDeclarations,
    private val registry: PluginFunctionRegistry,
    private val shapeRegistry: PluginObjectRegistry,
    private val permissions: PluginPermissions,
    /** What it asks the server to do for it; see [PluginCapabilities]. */
    private val capabilities: PluginCapabilities,
    /** Folds edited functions over the bundle when it is downloaded. */
    private val overrides: PluginOverrides,
    /** The library files a plugin ships with, stored beside its row. */
    private val libraryRows: PluginLibraryRepository,
    /** The proxy rules, which govern this outbound caller like every other. */
    proxies: io.mszymanski.orknux.connector.proxy.ProxyRouter,
    /** Where the source-size cap lives now that an administrator can set it. */
    private val installation: io.mszymanski.orknux.server.attachment.InstallationSettings,
    /** For reading a plugin's manifest, which is the one JSON this door parses. */
    private val mapper: tools.jackson.databind.ObjectMapper,
    /**
     * What the marketplace wants on a request for a file — and the question of
     * whether an address is the marketplace's at all, since this door also
     * fetches plugins from URLs somebody typed.
     */
    private val installKey: MarketplaceInstallKey,
) {

    /** How large one source file may be right now; asked per load, so the screen's answer is this one. */
    private fun maxSource(): Long = installation.pluginMaxSourceBytes()

    /**
     * What a load-from-URL fetches with. Built from [io.mszymanski.orknux.connector.proxy.ProxyRouter.builder]
     * so the installation's proxy rules and trusted certificates reach it -
     * the same seam every other outbound caller sits behind.
     */
    private val fetching: java.net.http.HttpClient = proxies.builder()
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
        .build()

    /**
     * A plugin to start from.
     *
     * Generated by the server rather than kept in the browser, so it cannot
     * disagree with what this server actually accepts: the API version in it is
     * the one this build supports, and the example function declares itself the
     * way the loader expects to read it.
     */
    @GetMapping("/api/plugins/template")
    fun template(): ResponseEntity<String> {
        access.requireAdmin()
        return ResponseEntity.ok()
            /*
             * TypeScript, and the interface compiles it on the way back in.
             *
             * The sandbox runs JavaScript and this server has no compiler, so what is
             * stored and run is always JavaScript — but nobody writes a plugin against
             * an undocumented contract by choice. The declarations at the top of the
             * template are the contract, they are checked by any editor, and they
             * compile to nothing.
             */
            .contentType(MediaType.valueOf("text/plain"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"orknux-plugin.ts\"")
            .body(
                /*
                 * Filled in from what this server actually enforces — the version it
                 * runs, the versions it still accepts, and the value types it has — so a
                 * template can never describe a contract different from the one that
                 * will judge it.
                 */
                TEMPLATE.trimIndent()
                    .replace("@API_VERSION@", PluginApiVersions.CURRENT.toString())
                    .replace("@SUPPORTED@", PluginApiVersions.SUPPORTED.sorted().joinToString(", "))
                    /*
                     * The same list again, as a TypeScript union, so an editor refuses
                     * a type this server does not have rather than leaving it to be
                     * discovered on upload. Derived from the enum like everything else
                     * here: a template cannot describe a contract that is not the one
                     * doing the judging.
                     */
                    .replace(
                        "@VALUE_TYPE_UNION@",
                        declarations.usableTypes().joinToString(" | ") { "'$it'" },
                    )
                    /*
                     * Narrower than the union above, and deliberately so: a
                     * parameter is answered either by typing a value or by pointing
                     * at one of a workspace's variables, and a variable holds a
                     * scalar. The template offers exactly what the loader accepts.
                     */
                    /*
                     * The connection kinds a `connection` parameter may name,
                     * written from the enumeration that does the accepting so the
                     * editor and the loader cannot come to disagree about which
                     * exist.
                     */
                    .replace(
                        "@CONNECTION_TYPE_UNION@",
                        declarations.connectionTypes().joinToString(" | ") { "'$it'" },
                    )
                    .replace(
                        "@PARAMETER_TYPE_UNION@",
                        declarations.parameterTypes().joinToString(" | ") { "'$it'" },
                    )
                    /*
                     * The permissions this build can grant, as a union, so an
                     * editor refuses one this server has never heard of rather
                     * than leaving it to be discovered on upload. Written from the
                     * enumeration that does the granting: a template cannot
                     * describe a contract different from the one doing the judging.
                     */
                    .replace(
                        "@PERMISSION_UNION@",
                        PluginPermission.entries.joinToString(" | ") { "'${it.name}'" },
                    )
                    .replace(
                        "@PERMISSION_LIST@",
                        PluginPermission.entries.joinToString("\n") {
                            "   *   - `${it.name}` - ${it.summary.lowercase()}"
                        },
                    )
                    // The capabilities the same way, from the enumeration that
                    // does the granting, for the same reason as everything above.
                    .replace(
                        "@CAPABILITY_UNION@",
                        PluginCapability.entries.joinToString(" | ") { "'${it.name}'" },
                    )
                    .replace(
                        "@CAPABILITY_LIST@",
                        PluginCapability.entries.joinToString("\n") {
                            "   *   - `${it.name}` - ${it.summary.lowercase()}"
                        },
                    ),
            )
    }

    /**
     * Loads one plugin.
     *
     * A name already loaded is replaced rather than refused: iterating on a
     * plugin means uploading it again, and making somebody unload first to do
     * that is a step with nothing behind it. The row keeps its id, so anything
     * that comes to refer to a plugin refers to the same one across an update.
     */
    /**
     * The plugin as it was written, for downloading.
     *
     * TypeScript where there is any, JavaScript otherwise — that is what "the plugin"
     * means to whoever wrote it. Handing back the compiled output instead would hand
     * them something they did not write, with the annotations gone and no way back.
     */
    @GetMapping("/api/plugins/{id}/source")
    fun download(@PathVariable id: Long): ResponseEntity<String> {
        access.requireAdmin()
        val plugin = plugins.findByIdOrNull(id) ?: throw PluginNotFoundException(id)

        /*
         * Edited functions ride along, or the export would lie.
         *
         * The bundle is what the author uploaded; an edit lives on the function
         * row and never rewrites it. A download that handed back the artifact
         * alone would hand back a plugin that does something other than this
         * installation does - so where edits exist, what downloads is the
         * bundle with the edits folded over it, in JavaScript, since the edits
         * are held both ways and only the JavaScript half is what runs.
         */
        val edited = overrides.editedOf(plugin)
        if (edited.isNotEmpty()) {
            val folded = overrides.folded(plugin, edited)
                // A download, so the refusal is the download's own status and
                // sentence rather than an exception dressed as a server fault.
                ?: return ResponseEntity.status(HttpStatus.CONFLICT)
                    .contentType(MediaType.valueOf("text/plain"))
                    .body(PluginExportUnfoldableException(plugin.name).message)
            return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/plain"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${plugin.key}.js\"")
                .body(folded)
        }

        val typescript = plugin.typescript
        val extension = if (typescript == null) "js" else "ts"
        return ResponseEntity.ok()
            .contentType(MediaType.valueOf("text/plain"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${plugin.key}.$extension\"")
            .body(typescript ?: plugin.source)
    }

    @PostMapping("/api/plugins")
    @Transactional
    fun upload(
        @RequestParam("file") file: MultipartFile,
        /*
         * What it was written in, when that was TypeScript.
         *
         * Sent by the interface, which compiles before uploading: the sandbox runs
         * JavaScript and this server has no compiler, so `file` is always the
         * compiled code. This is kept only so the plugin can be downloaded as the
         * thing somebody actually wrote — and it is never evaluated.
         */
        @RequestParam("typescript", required = false) typescript: String?,
        /*
         * Which permissions the person loading it is agreeing to, by name.
         *
         * A list rather than a flag, and that is the point of it. "Yes" would be
         * an agreement to whatever the file happened to ask for, including
         * whatever it started asking for since it was last looked at; naming them
         * is an agreement to these. A load whose names do not match exactly what
         * the plugin declares is refused, so this cannot drift from what was shown.
         */
        @RequestParam("accept", required = false) accept: String?,
    ): ResponseEntity<Any> {
        access.requireAdmin()

        if (file.isEmpty) throw PluginEmptyException()
        if (file.size > maxSource()) throw PluginTooLargeException(maxSource() / 1024)

        /*
         * One file, or a zip of them. A single .js is the plugin as it always
         * was; a .zip is a plugin that ships libraries - the file at the
         * archive's root is the plugin, everything else is a library under
         * its declared path. What the archive holds and what libraries()
         * declares are checked against each other below, so nothing rides in
         * under cover of the other.
         */
        val sent = file.originalFilename?.trim()?.ifEmpty { null } ?: "plugin.js"
        val zipped = sent.endsWith(".zip")
        if (!zipped && !sent.endsWith(".js") && !sent.endsWith(".mjs")) throw PluginNotJavaScriptException(sent)

        val opened = if (zipped) {
            unzipped(file.bytes)
        } else {
            Unzipped(sent, text(file.bytes), emptyList(), manifest = null, icon = null)
        }

        return loaded(
            opened.filename,
            opened.source,
            opened.libraries,
            typescript,
            accept,
            manifest = opened.manifest,
            icon = opened.icon,
            iconDark = opened.iconDark,
        )
    }

    /**
     * Loads a plugin from where it lives, libraries and all.
     *
     * The URL names the plugin's own file; its imports are what says what else
     * to fetch, resolved against that URL - a relative path is the whole of
     * what a specifier may be, so nothing can be fetched from anywhere but
     * beside the plugin. What arrives then answers to exactly the same
     * questions an uploaded file does, the agreement included: the first load
     * of a plugin that ships libraries is refused with the list, and the
     * second - naming it in `accept` - is the permission the person gave.
     *
     * Fetching happens before the ask, deliberately: the list somebody is
     * shown is the true closure, read out of the files themselves, not a
     * promise about them. Nothing is stored until the agreement holds.
     */
    @PostMapping("/api/plugins/url")
    @Transactional
    fun uploadFromUrl(@org.springframework.web.bind.annotation.RequestBody request: PluginUrlRequest): ResponseEntity<Any> {
        access.requireAdmin()

        val address = request.url.trim()
        val (filename, source, files) = fetchedBundle(address)

        /*
         * What it says about itself, from beside where it lives. Optional in
         * every sense: a plugin without a manifest is the ordinary case, and
         * a fetch that fails takes nothing with it.
         */
        val base = java.net.URI.create(address)
        val said = manifest(runCatching { fetched(base.resolve(MANIFEST)) }.getOrNull())
        fun drawn(path: String?): String? = path?.let {
            runCatching { fetched(base.resolve(it)) }.getOrNull()?.let(::drawing)
        }

        val face = drawn(said?.icon)
        /*
         * And the white one, where the manifest names it or the convention
         * puts it. A fetch that 404s costs one request and answers null, which
         * is the same as a plugin that ships a single icon - so following the
         * convention here is cheap and makes a plugin loaded by hand draw the
         * way the same plugin drawn from the catalog does.
         */
        val faceDark = drawn(said?.iconDark ?: besideIt(said?.icon))

        return loaded(
            filename,
            source,
            files,
            typescript = null,
            accept = request.accept,
            icon = face,
            iconDark = faceDark,
            manifest = said,
        )
    }

    /**
     * The plugin at an address, and the closure of what it imports.
     *
     * Each fetched file is scanned and what it names is fetched next, resolved
     * against the plugin's own URL — so every file comes from beside it and
     * nowhere else. The bounds are the library bounds: a closure past them is
     * a plugin this server would refuse anyway, so it is refused before the
     * next request rather than after it.
     */
    /**
     * The SHA-256 of what was downloaded, as the catalog writes one: lowercase
     * hex over the file's UTF-8 bytes.
     */
    private fun digestOf(source: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun fetchedBundle(address: String): Triple<String, String, List<PluginLibraryFile>> {
        val base = runCatching { java.net.URI.create(address) }.getOrNull()
            ?.takeIf { it.scheme?.lowercase() in setOf("http", "https") && it.path.endsWith(".js") }
            ?: throw PluginUrlInvalidException(address)

        val source = fetched(base)
        val filename = base.path.substringAfterLast('/')

        val files = linkedMapOf<String, PluginLibraryFile>()
        val frontier = ArrayDeque<Pair<String, String>>()
        frontier.add("" to source)
        while (frontier.isNotEmpty()) {
            val (at, held) = frontier.removeFirst()
            val paths = when (val scanned = PluginBundle.scan(held, at)) {
                is PluginBundle.Scan.Paths -> scanned.paths
                is PluginBundle.Scan.Refused -> throw PluginContractException(scanned.reason)
            }
            for (path in paths) {
                if (path in files) continue
                if (files.size >= PluginRunner.MAX_LIBRARIES) {
                    throw PluginContractException("the plugin's imports reach more than ${PluginRunner.MAX_LIBRARIES} files")
                }
                val library = fetched(base.resolve(path))
                files[path] = PluginLibraryFile(path, library)
                frontier.add(path to library)
            }
        }
        return Triple(filename, source, files.values.toList())
    }

    /**
     * Installs from the marketplace, or updates what is installed — the same
     * act, and the same load every other door makes.
     *
     * Called by [MarketplaceAPI], which is where the GraphQL mutation and the
     * audit line live; this is the half that knows how a plugin is loaded.
     */
    fun installed(offering: MarketplaceOffering, accept: String?): ResponseEntity<Any> {
        /*
         * A release the marketplace still holds, or a sentence saying why not.
         *
         * The catalog keeps the record of every version and the bytes of the
         * ten newest, so an older one is real, nameable and not installable.
         * Refused here rather than at the fetch, where it arrives as a 404 and
         * reads as the marketplace being broken.
         */
        val release = offering.current
        if (release != null && !release.available) {
            throw PluginReleaseGoneException(offering.key, offering.version)
        }

        val (filename, source, files) = fetchedBundle(offering.url)

        /*
         * The bytes are the bytes the catalog published, checked rather than
         * assumed.
         *
         * Everything up to here trusted whatever came back over the wire: the
         * url is the catalog's own and the call goes through this
         * installation's proxy rules, which is good but is not the same as
         * knowing the file did not change on the way. The digest is the
         * catalog saying what it published, so holding the download against it
         * turns "it arrived" into "it arrived intact".
         *
         * Only the main file. It is what the catalog digests and what runs;
         * the libraries beside it are reached *from* it, so a main file that
         * is what it should be is a plugin whose shape nobody rearranged.
         *
         * Skipped where the catalog answers no digest, which is every
         * marketplace older than the field. A check somebody cannot perform is
         * not a reason to refuse an install that worked yesterday.
         */
        val expected = release?.digest?.lowercase().orEmpty()
        if (expected.isNotEmpty()) {
            val held = digestOf(source)
            if (held != expected) throw PluginDigestMismatchException(offering.key, expected, held)
        }

        /*
         * What it is called, what it is for and who wrote it.
         *
         * The catalog first, and the plugin's own `plugin.json` for whatever
         * the catalog leaves out.
         *
         * That way round because this is an install *from the catalog*, and
         * the catalog is the one that knows: it has an account that published
         * this version, and a manifest has a string somebody typed. Every
         * plugin in the first-party set ships `"author": "Orknux"` while the
         * listing names the person who published it - so trusting the file
         * put a different name in the row than the one on the page the plugin
         * was installed from.
         *
         * Prose either way, and only prose. What a plugin is *allowed* to do
         * is still read from its code in the sandbox at the moment somebody
         * accepts it, so a generous listing buys nothing.
         */
        val base = java.net.URI.create(offering.url)
        val said = manifest(runCatching { fetched(base.resolve(MANIFEST)) }.getOrNull())
        return loaded(
            filename,
            source,
            files,
            typescript = null,
            accept = accept,
            marketplace = offering.key to offering.version,
            icon = faceOf(offering.icon),
            /*
             * The white one too, where the catalog has it. Both come across
             * for the same reason either does: whichever ground somebody is
             * looking at, the installation has to be able to draw this plugin
             * without asking the marketplace again.
             */
            iconDark = faceOf(offering.iconDark),
            manifest = PluginManifest(
                name = offering.name.ifBlank { null } ?: said?.name,
                summary = offering.summary.ifBlank { null } ?: said?.summary,
                author = offering.author.ifBlank { null } ?: said?.author,
                version = offering.version.ifBlank { null } ?: said?.version,
                // Both faces came across already, by the route that checks
                // each is a drawing; a path from here would be fetched twice.
                icon = null,
                iconDark = null,
            ),
        )
    }

    /**
     * Where the white glyph sits when nobody said.
     *
     * `icon.svg` has `icon-white.svg` beside it - the marketplace's own
     * convention, which is how its catalog answers with two files for a plugin
     * whose manifest names one. Followed here so a plugin loaded from a zip or
     * a URL gets the same pair the same plugin gets from the catalog; without
     * it the Slack mark was the dark one on a dark screen, but only when it
     * had been loaded by hand.
     */
    private fun besideIt(icon: String?): String? {
        val held = icon ?: return null
        val dot = held.lastIndexOf('.').takeIf { it > 0 } ?: return null
        return held.substring(0, dot) + "-white" + held.substring(dot)
    }

    /**
     * The text if it is a drawing, and null if it is not.
     *
     * The rule itself is [drawingOrNull], which is shared with the catalog
     * screen's own icon fetch - it was written here, and a second door
     * answering it a second way is exactly how an icon whose file opens with a
     * licence comment ended up stored and then printed as its own source.
     */
    private fun drawing(text: String?): String? = drawingOrNull(text, MOST_ICON_CHARS)

    /**
     * The plugin's face, brought across rather than linked to.
     *
     * The marketplace hosts the SVG; an installation that loaded a plugin
     * last year should still draw it, and it may have no route to the
     * marketplace at all. So the bytes are fetched once here and stored with
     * the row. An emoji stands as itself and travels as the string it is.
     *
     * A face that cannot be fetched is not a failed install: the plugin is
     * the point, and a screen without an icon draws its own placeholder.
     */
    private fun faceOf(icon: String?): String? {
        val named = icon?.trim()?.ifEmpty { null } ?: return null
        if (!named.startsWith("http://") && !named.startsWith("https://")) return named

        return runCatching {
            val address = java.net.URI.create(named)
            val request = java.net.http.HttpRequest.newBuilder(address)
                .timeout(java.time.Duration.ofSeconds(15))
                .GET()
                .build()
            val answer = fetching.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
            if (answer.statusCode() != 200) return null

            val drawn = answer.body().orEmpty()
            /*
             * An SVG and nothing else, held to a size a row should carry. What
             * is refused here is the interesting half: a marketplace pointing
             * an icon at something that is not a picture is a marketplace
             * putting somebody else's bytes on this installation's screens.
             */
            drawing(drawn) ?: return null
        }.getOrNull()
    }

    /** One file from beside the plugin's URL, held to the upload's own bounds. */
    private fun fetched(address: java.net.URI): String {
        /*
         * The marketplace keys its files, and the key goes to the marketplace
         * and nowhere else.
         *
         * One path serves two kinds of address - a plugin the catalog named,
         * and a plugin at a URL somebody typed - and handing the day's value
         * to the second would post it to whatever host was in the box. That is
         * the leak the contract warns about, made by us rather than found, so
         * the header is fitted by host rather than sent on every fetch.
         *
         * Computed here, per request, never held: the message is a date, and a
         * value cached for the length of an install expires in the middle of
         * one.
         */
        val marketplace = installKey.own(address)
        if (marketplace && !installKey.configured) {
            throw PluginUrlUnreachableException(address.toString(), MarketplaceInstallKey.MISSING)
        }
        val building = java.net.http.HttpRequest.newBuilder(address)
            .timeout(java.time.Duration.ofSeconds(20))
            .GET()
        if (marketplace) {
            installKey.today()?.let { building.header(MarketplaceInstallKey.HEADER, it) }
        }
        val request = building.build()
        val answer = try {
            fetching.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray())
        } catch (failure: java.io.IOException) {
            throw PluginUrlUnreachableException(address.toString(), failure.message ?: "it could not be reached")
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw PluginUrlUnreachableException(address.toString(), "the request was interrupted")
        }
        /*
         * The statuses worth a sentence. A 401 from the marketplace is a
         * setting somebody has not set, and reporting it as "it answered 401"
         * sends them to their proxy rules looking for it; a 429 carries how
         * long to wait, and saying so is the difference between waiting and
         * retrying into the same wall.
         */
        if (answer.statusCode() == 401) {
            /*
             * Two different 401s, and saying which is the whole value of
             * catching it here.
             *
             * One is a key the marketplace would not take. The other is a key
             * this installation never sent, because the address did not look
             * like the marketplace's - which is what happens when the catalog
             * is configured on one host and hands out file URLs on another,
             * and it used to surface as a bare "it answered 401" with nothing
             * pointing at the setting that caused it.
             */
            throw PluginUrlUnreachableException(
                address.toString(),
                if (marketplace) MarketplaceInstallKey.REFUSED else installKey.elsewhere(address),
            )
        }
        if (answer.statusCode() == 429) {
            val after = answer.headers().firstValue("Retry-After").orElse(null)
            throw PluginUrlUnreachableException(
                address.toString(),
                if (after == null) {
                    "it is rate-limiting this installation; try again shortly"
                } else {
                    "it is rate-limiting this installation; try again in ${after}s"
                },
            )
        }
        if (answer.statusCode() != 200) {
            throw PluginUrlUnreachableException(address.toString(), "it answered ${answer.statusCode()}")
        }
        val bytes = answer.body() ?: ByteArray(0)
        if (bytes.isEmpty()) throw PluginEmptyException()
        if (bytes.size > maxSource()) throw PluginTooLargeException(maxSource() / 1024)
        return text(bytes)
    }

    /**
     * The shared half of every load, wherever the files came from: the plugin
     * is questioned, its declaration checked against what arrived, the
     * agreement checked against what is new, and only then is anything stored.
     */
    internal fun loaded(
        filename: String,
        source: String,
        files: List<PluginLibraryFile>,
        typescript: String?,
        accept: String?,
        /**
         * Where it came from, when that was the marketplace: the catalog key
         * and the version at this moment. Null for every other door, and the
         * row's own values are then left as they were — a plugin installed
         * from the catalog and re-uploaded by hand keeps saying where it came
         * from, which is true and is what an update would compare against.
         */
        marketplace: Pair<String, String>? = null,
        /** The face to store with it, where one came with this load. */
        icon: String? = null,
        /** And the one for a dark ground, where the catalog had a second. */
        iconDark: String? = null,
        /** What it says about itself, where it ships a manifest. */
        manifest: PluginManifest? = null,
    ): ResponseEntity<Any> {
        /*
         * The plugin is loaded and questioned before anything is stored: what it
         * calls itself, which API it was written against, and what it offers. One
         * evaluation, because all three answers have to come from the same instance
         * — and a plugin that fails the contract is not stored at all, so every row
         * here is a plugin that held it up.
         */
        // The installation's own answer to how long a load may take, set on the
        // admin screen rather than in the file it starts from.
        val inspected = when (val answered = runner.inspect(source, files, installation.pluginTimeoutMillis())) {
            is PluginInspection.Read -> answered
            is PluginInspection.Unreadable -> throw PluginContractException(answered.reason)
        }

        /*
         * The declaration is what somebody is asked to allow, so it has to be
         * exactly what arrived - a shipped file the declaration is silent
         * about is a file nobody was asked about, and a declared file that
         * did not arrive is a promise the imports will fall through.
         */
        val shipped = files.map { it.path }.toSet()
        if (inspected.libraries.toSet() != shipped) {
            val missing = inspected.libraries.toSet() - shipped
            val undeclared = shipped - inspected.libraries.toSet()
            throw PluginContractException(
                buildString {
                    append("what libraries() declares and what arrived do not agree")
                    if (missing.isNotEmpty()) append(": declared and not shipped - ${missing.sorted().joinToString(", ")}")
                    if (undeclared.isNotEmpty()) append("; shipped and not declared - ${undeclared.sorted().joinToString(", ")}")
                    append(". The declaration is what somebody allows, so the two have to be the same list.")
                },
            )
        }

        if (inspected.apiVersion !in PluginApiVersions.SUPPORTED) {
            throw PluginApiVersionUnsupportedException(inspected.apiVersion, PluginApiVersions.SUPPORTED)
        }

        val key = inspected.id
        if (!KEY.matches(key)) throw PluginIdInvalidException(key)

        val apiVersion = inspected.apiVersion
        /*
         * The shapes first, because the functions may name one. Checked
         * before anything else is, so a function declaring `returnType:
         * 'Issue'` is measured against a set that has already held together.
         */
        val declaredObjects = declarations.validatedObjects(inspected.objects)
        val declaredTypes = declarations.validatedTypes(inspected.types)
        val exported = inspected.objects.map { it.name.trim() }.toSet()
        val declared = declarations.validated(inspected.functions, exported)
        val declaredTools = declarations.validatedTools(inspected.tools, exported)
        val declaredSkills = declarations.validatedSkills(inspected.skills)
        // The kinds of host it declares, and the parameters checked against
        // them too, so a `connection` parameter may name the plugin's own kind.
        // Issue #363.
        val declaredConnectionTypes = declarations.validatedConnectionTypes(inspected.connectionTypes)
        // The workflow actions it declares, checked here so a node can be
        // pointed at one knowing its inputs and outputs are types this server
        // has. Issue #438.
        val declaredActions = declarations.validatedActions(inspected.actions)
        val parameters = declarations.validatedParameters(
            inspected.parameters,
            inspected.connectionTypes.map { it.name.trim() }.toSet(),
        )

        /*
         * What it needs, and whether anybody has said it may have it.
         *
         * Three ways this goes, and only one of them grants anything new. Asking
         * for nothing needs no acceptance. Asking for what is already accepted
         * needs none either — nothing new is being handed over, and making somebody
         * agree again to what they agreed to last week teaches them to click
         * through it. Asking for anything else is refused, with the list, until a
         * second load names exactly that list.
         *
         * This is where an escalation is caught: a plugin edited to need one more
         * thing lands in the third case, because the acceptance on the row names
         * the permissions it was given for and not the plugin in general.
         */
        val wanted = permissions.validated(inspected.permissions)
        val already = plugins.findByKey(key)?.let { permissions.read(it.acceptedPermissions) } ?: emptySet()
        val agreeing = wanted.isNotEmpty() && !already.containsAll(wanted)

        /*
         * The same three steps for what the plugin asks the *server* to do, and
         * deliberately not folded into the ones above: a capability reaches
         * outside the sandbox where a permission does not, so accepting one must
         * never be able to cover the other. Each validator reads only its own
         * names off the shared field, so the separation holds on the wire too.
         */
        val wantedCapabilities = capabilities.validated(inspected.capabilities)
        val heldCapabilities = plugins.findByKey(key)?.let { capabilities.read(it.acceptedCapabilities) } ?: emptySet()
        val agreeingCapabilities = wantedCapabilities.isNotEmpty() && !heldCapabilities.containsAll(wantedCapabilities)

        /*
         * And once more for the library files, which are code riding in beside
         * the plugin: a set already allowed is not re-asked, a changed set is.
         * By path - the person is allowing that these files ship, the way
         * accepting the plugin at all allows what its own source says.
         */
        val wantedLibraries = inspected.libraries.toSet()
        val heldLibraries = plugins.findByKey(key)
            ?.let { held -> libraryRows.findByPluginIdOrderByPositionAsc(requireNotNull(held.id)).map { it.path }.toSet() }
            ?: emptySet()
        val agreeingLibraries = wantedLibraries.isNotEmpty() && heldLibraries != wantedLibraries

        /*
         * Refused as one question, with both lists. Asked one at a time this
         * could never converge: each refused load stores nothing, so a second
         * request accepting only the second list would be refused over the
         * first again. The lists stay separately named all the way to the
         * screen, so nothing is agreed to under cover of the other.
         */
        val refusedPermissions = agreeing && permissions.accepted(accept) != wanted
        val refusedCapabilities = agreeingCapabilities && capabilities.accepted(accept) != wantedCapabilities
        val refusedLibraries = agreeingLibraries && acceptedLibraries(accept) != wantedLibraries
        if (refusedPermissions || refusedCapabilities || refusedLibraries) {
            throw PluginAgreementNeededException(
                // Each list travels only while it is being agreed to: what was
                // accepted long ago is not re-asked beside what is new.
                permissions = if (agreeing) permissions.viewOf(wanted) else emptyList(),
                capabilities = if (agreeingCapabilities) capabilities.viewOf(wantedCapabilities) else emptyList(),
                libraries = if (agreeingLibraries) wantedLibraries.sorted() else emptyList(),
            )
        }

        /*
         * What it calls itself, where it says — the filename otherwise.
         *
         * A filename is a fact about how somebody saved a file rather than
         * about the plugin: "slack", "slack.min" and "slack (2)" are one
         * plugin, and only the first of those reads like its name.
         */
        val name = manifest?.name ?: filename.removeSuffix(".mjs").removeSuffix(".js").takeLast(MAX_NAME)

        // The bundle as one measure and one fingerprint: what is stored is the
        // plugin and its files, so what is sized and hashed is too.
        val totalBytes = source.toByteArray(Charsets.UTF_8).size.toLong() +
            files.sumOf { it.source.toByteArray(Charsets.UTF_8).size.toLong() }
        val fingerprint = digest(files.fold(source) { acc, held -> acc + "\n// " + held.path + "\n" + held.source })

        val existing = plugins.findByKey(key)
        // Stamped when somebody has just agreed; carried over when nothing new was
        // granted; cleared with the list when the plugin stops asking for anything.
        val acceptedAt = when {
            wanted.isEmpty() -> null
            agreeing -> OffsetDateTime.now()
            else -> existing?.permissionsAcceptedAt ?: OffsetDateTime.now()
        }
        val acceptedBy = when {
            wanted.isEmpty() -> null
            agreeing -> currentUser()
            else -> existing?.permissionsAcceptedBy ?: currentUser()
        }

        val plugin = existing?.apply {
            this.name = name
            this.filename = filename.takeLast(MAX_NAME)
            this.source = source
            this.typescript = typescript?.ifBlank { null }
            this.sizeBytes = totalBytes
            this.apiVersion = apiVersion
            this.declaredFunctions = declared
            this.declaredTools = declaredTools
            this.declaredSkills = declaredSkills
            this.declaredConnectionTypes = declaredConnectionTypes
            this.declaredActions = declaredActions
            this.declaredObjects = declaredObjects
            this.declaredTypes = declaredTypes
            this.declaredParameters = parameters
            this.declaredPermissions = permissions.write(wanted)
            this.acceptedPermissions = permissions.write(wanted)
            this.declaredCapabilities = capabilities.write(wantedCapabilities)
            this.acceptedCapabilities = capabilities.write(wantedCapabilities)
            this.permissionsAcceptedAt = acceptedAt
            this.permissionsAcceptedBy = acceptedBy
            this.sha256 = fingerprint
            this.uploadedAt = OffsetDateTime.now()
            this.uploadedBy = currentUser()
            marketplace?.let { (fromKey, version) ->
                this.marketplaceKey = fromKey
                this.marketplaceVersion = version
            }
            // Only where one came with this load: a re-upload by hand does not
            // strip the face the catalog gave it.
            icon?.let { this.icon = it }
            iconDark?.let { this.iconDark = it }
            manifest?.let {
                this.summary = it.summary
                this.author = it.author
                this.version = it.version
            }
            // `enabled` is deliberately untouched: somebody who switched this
            // plugin off said something about the plugin, and a new version
            // arriving is not them changing their mind.
        } ?: Plugin(
            key = key,
            name = name,
            filename = filename.takeLast(MAX_NAME),
            source = source,
            typescript = typescript?.ifBlank { null },
            sizeBytes = totalBytes,
            apiVersion = apiVersion,
            declaredFunctions = declared,
            declaredTools = declaredTools,
            declaredSkills = declaredSkills,
            declaredConnectionTypes = declaredConnectionTypes,
            declaredActions = declaredActions,
            declaredObjects = declaredObjects,
            declaredTypes = declaredTypes,
            declaredParameters = parameters,
            declaredPermissions = permissions.write(wanted),
            /*
             * Accepted is set to exactly what is declared, never to more. The check
             * above is what decides whether that was allowed; by here it has been
             * agreed to, or there was nothing to agree to.
             */
            acceptedPermissions = permissions.write(wanted),
            declaredCapabilities = capabilities.write(wantedCapabilities),
            acceptedCapabilities = capabilities.write(wantedCapabilities),
            permissionsAcceptedAt = acceptedAt,
            permissionsAcceptedBy = acceptedBy,
            sha256 = fingerprint,
            uploadedBy = currentUser(),
            marketplaceKey = marketplace?.first,
            marketplaceVersion = marketplace?.second,
            icon = icon,
            iconDark = iconDark,
            summary = manifest?.summary,
            author = manifest?.author,
            version = manifest?.version,
        )

        val saved = plugins.save(plugin)
        /*
         * The files, replaced whole beside the row - a re-upload's library set
         * is whatever it shipped this time, in declaration order. Their whole
         * lifetime is the plugin's: the cascade takes them when it goes.
         */
        libraryRows.deleteByPluginId(requireNotNull(saved.id))
        // Pushed to the database now: Hibernate orders inserts ahead of
        // deletes within a flush, which on a re-upload would put the new rows
        // in before the old ones went and trip the path's unique key.
        libraryRows.flush()
        files.forEachIndexed { at, held ->
            libraryRows.save(PluginLibrary(pluginId = requireNotNull(saved.id), position = at, path = held.path, source = held.source))
        }
        // What it declares becomes what it provides, in the same transaction: a
        // plugin that is loaded but whose functions did not appear is a state
        // nobody could explain.
        /*
         * The shapes before the functions, because a function that returns one
         * holds a reference to its row and the row has to exist to be pointed
         * at. Both in the same transaction as the plugin: a plugin that is
         * loaded but whose functions did not appear is a state nobody could
         * explain.
         */
        val shapes = shapeRegistry.reconcile(saved)
        val provided = registry.reconcile(saved)

        return ResponseEntity.ok(
            mapOf(
                "plugin" to saved.view(
                    declarations.read(saved.declaredFunctions),
                    declarations.readParameters(saved.declaredParameters),
                    permissions.viewOf(permissions.grantedTo(saved)),
                    // The files just stored, in the order they were: read off
                    // what arrived rather than back out of the rows, which are
                    // the same list one flush later.
                    files.map { it.path },
                    declarations.readSkills(saved.declaredSkills),
                    declarations.readObjects(saved.declaredObjects),
                    declarations.readTypes(saved.declaredTypes),
                    declarations.readConnectionTypes(saved.declaredConnectionTypes, saved.key, saved.name),
                    declarations.readActions(saved.declaredActions, saved.key, saved.name),
                ),
                "replaced" to (existing != null),
                "provides" to provided,
                // The agents' half of what it provides, under the same prefix
                // rule: these are the names an agent is granted.
                "tools" to declarations.readTools(saved.declaredTools).map { "${saved.key}_${it.name}" }.sorted(),
                // And the shapes, under the same prefix rule.
                "objects" to shapes,
            ),
        )
    }

    /**
     * A refused upload, answered as a refusal.
     *
     * Everything this endpoint rejects is something about the file somebody
     * chose — too large, not JavaScript, would not say which API it uses — so it
     * is a 400 carrying the sentence, not a 500. Scoped to this controller
     * because these exceptions mean nothing anywhere else.
     */
    /**
     * A plugin needing something nobody has agreed to, answered as the question
     * it is.
     *
     * Its own handler because the lists have to travel: a message alone would
     * leave the interface with nothing to show, and something to accept that
     * nobody was shown is exactly what this is meant to prevent. Two lists under
     * two names, never one: a permission relaxes the sandbox and a capability
     * asks the server to act, and a screen must be able to say which is which.
     * The status is a 400 like every other refusal — nothing was stored, and
     * the way forward is a second request.
     */
    @ExceptionHandler(PluginAgreementNeededException::class)
    fun needsAccepting(failure: PluginAgreementNeededException): ResponseEntity<Map<String, Any>> =
        ResponseEntity.badRequest().body(
            mapOf(
                "message" to (failure.message ?: "This plugin needs to be accepted"),
                "permissions" to failure.permissions.map { mapOf("name" to it.name, "summary" to it.summary) },
                "capabilities" to failure.capabilities.map { mapOf("name" to it.name, "summary" to it.summary) },
                // Paths only: what is being allowed is that these files ship
                // with the plugin. The screen keeps the list folded by default.
                "libraries" to failure.libraries,
            ),
        )

    @ExceptionHandler(
        PluginEmptyException::class,
        PluginTooLargeException::class,
        PluginNotJavaScriptException::class,
        PluginNotTextException::class,
        PluginContractException::class,
        PluginApiVersionUnsupportedException::class,
        
        PluginDeclarationInvalidException::class,
        
        PluginIdInvalidException::class,
        PluginFunctionInUseException::class,
        PluginPermissionUnknownException::class,
        PluginCapabilityUnknownException::class,
        PluginZipInvalidException::class,
        PluginUrlInvalidException::class,
        PluginUrlUnreachableException::class,
    )
    fun refused(failure: RuntimeException): ResponseEntity<Map<String, String>> =
        ResponseEntity.badRequest().body(mapOf("message" to (failure.message ?: "That file could not be loaded")))

    /**
     * The bytes as the text they are supposed to be.
     *
     * Strict decoding, so something that is not UTF-8 is refused here rather than
     * stored as replacement characters and puzzled over later.
     */
    private fun text(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    } catch (failure: CharacterCodingException) {
        throw PluginNotTextException()
    }

    private fun digest(source: String): String = MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    /**
     * The archive taken apart: the file at its root is the plugin, everything
     * else is a library under its path. Anything that is not a `.js` under a
     * relative path is refused - a zip is how a plugin ships more files, not a
     * way for anything else to arrive - and the usual archive lint (folders,
     * `__MACOSX/`, dotfiles) is passed over without comment.
     */
    private fun unzipped(bytes: ByteArray): Unzipped {
        val held = linkedMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zip ->
            var total = 0L
            var entry = zip.nextEntry
            while (entry != null) {
                // Windows archivers write entry names with backslashes; they
                // mean the same path, so they are read as it.
                val name = entry.name.replace('\\', '/')
                val junk = entry.isDirectory ||
                    name.startsWith("__MACOSX/") ||
                    name.substringAfterLast('/').startsWith(".")
                if (!junk) {
                    val path = name.removePrefix("./")
                    if (path.startsWith("/") || path.split('/').any { it.isEmpty() || it == ".." }) {
                        throw PluginZipInvalidException("\"$name\" is not a path a plugin zip may hold")
                    }
                    /*
                     * JavaScript, the manifest, and the face it names: those
                     * three are what a plugin is made of, and the rest of the
                     * archive is passed over.
                     *
                     * Passed over rather than refused. A plugin folder is a
                     * folder somebody works in - a README, a licence, a
                     * lockfile - and the packer zips what is there, so
                     * refusing the archive over a README meant a plugin that
                     * builds cannot be loaded. What the refusal was actually
                     * guarding is code nobody declared, and that is still
                     * caught where it matters: a shipped .js the plugin does
                     * not declare is refused by name, and a declared one that
                     * did not arrive is too.
                     */
                    if (!path.endsWith(".js") && path != MANIFEST && !path.endsWith(".svg")) {
                        zip.closeEntry()
                        entry = zip.nextEntry
                        continue
                    }
                    val content = zip.readBytes()
                    total += content.size
                    if (content.size > maxSource()) throw PluginTooLargeException(maxSource() / 1024)
                    if (total > maxSource() * WHOLE_ZIP_TIMES) throw PluginTooLargeException(maxSource() * WHOLE_ZIP_TIMES / 1024)
                    if (held.size >= MAX_ZIP_FILES) throw PluginZipInvalidException("a plugin zip holds at most $MAX_ZIP_FILES files")
                    if (held.put(path, content) != null) throw PluginZipInvalidException("\"$path\" appears in the zip twice")
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (held.isEmpty()) throw PluginEmptyException()

        /*
         * What it says about itself, and the face it names — read out of the
         * archive before the code is, because neither is code and neither
         * goes to the sandbox.
         */
        val said = manifest(held[MANIFEST]?.let(::text))
        fun drawn(path: String?): String? = path?.removePrefix("./")
            ?.let { held[it] }
            ?.takeIf { it.size <= MOST_ICON_CHARS }
            ?.let(::text)
            // Held to what the other two doors hold an icon to. This one
            // stored whatever the manifest pointed at, so a manifest naming
            // its own README got the README onto the screen.
            ?.let(::drawing)

        val face = drawn(said?.icon)
        // The white one where the manifest names it, and otherwise wherever
        // the convention puts it - which is beside the icon, and is where it
        // already is in every plugin that ships two.
        val faceDark = drawn(said?.iconDark ?: besideIt(said?.icon))

        // The plugin itself: the one root-level .js, or plugin.js where
        // several sit there. A rule somebody can hold in their head.
        val code = held.filterKeys { it.endsWith(".js") }
        if (code.isEmpty()) throw PluginZipInvalidException("the zip holds no JavaScript, and the plugin is JavaScript")
        val roots = code.keys.filter { '/' !in it }
        val main = when {
            roots.size == 1 -> roots.single()
            "plugin.js" in roots -> "plugin.js"
            roots.isEmpty() -> throw PluginZipInvalidException("the zip has no file at its root, and the plugin itself sits there")
            else -> throw PluginZipInvalidException(
                "the zip has several files at its root (${roots.sorted().joinToString(", ")}) - name the plugin plugin.js",
            )
        }
        val libraries = code.filterKeys { it != main }
            .map { (path, content) -> PluginLibraryFile(path, text(content)) }
        return Unzipped(main, text(code.getValue(main)), libraries, said, face, faceDark)
    }


    /**
     * What a plugin says about itself, read from the `plugin.json` beside it.
     *
     * Everything here is prose for a screen — the name, the line under it,
     * who wrote it, what it calls its version. None of it grants anything or
     * changes what the plugin may do: what a plugin is *allowed* is read from
     * the code, at the moment somebody accepts it, and a manifest that
     * claimed otherwise would be a plugin describing itself into privileges.
     *
     * Every field is optional and every one is bounded. A manifest that will
     * not parse is not a failed load: it is a plugin without a manifest,
     * which is the ordinary case and the one every plugin was until now.
     */
    private fun manifest(json: String?): PluginManifest? {
        val read = json?.let { runCatching { mapper.readTree(it) }.getOrNull() }?.takeIf { it.isObject }
            ?: return null

        fun said(name: String, most: Int) =
            read.path(name).takeIf { it.isString }?.asString()?.trim()?.ifEmpty { null }?.take(most)

        return PluginManifest(
            name = said("name", MAX_NAME),
            summary = said("summary", MOST_SUMMARY_CHARS),
            author = said("author", MAX_NAME),
            version = said("version", 32),
            // A path beside the plugin, so it cannot name anything else.
            icon = said("icon", 200)?.takeIf { PluginRunner.LIBRARY_PATH_ANY.matches(it) },
            iconDark = said("iconDark", 200)?.takeIf { PluginRunner.LIBRARY_PATH_ANY.matches(it) },
        )
    }

    /**
     * The library paths an upload said it accepts, off the shared field.
     *
     * A path is its own kind of name there - nothing else in the field holds a
     * slash or ends in .js - so each reader takes its own and leaves the rest.
     */
    private fun acceptedLibraries(field: String?): Set<String> = field.orEmpty()
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() && PluginRunner.LIBRARY_PATH.matches(it) }
        .map { it.removePrefix("./") }
        .toSet()

    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"

    private companion object {
        /**
         * A plugin is a bundled script. Generous enough for one with its
         * dependencies compiled in, small enough that the row stays a row.
         */
        /**
         * A zip bomb's ceiling, as a multiple of the per-file cap: what an
         * archive may add up to once opened. The cap itself is the
         * administrator's setting - see `InstallationSettings.pluginMaxSourceKb`.
         */
        const val WHOLE_ZIP_TIMES = 4L

        /** The plugin and its libraries; the library bound plus one. */
        const val MAX_ZIP_FILES = 51

        /**
         * A face is a small drawing. Large enough for a real icon with a
         * gradient in it, small enough that a row stays a row.
         */
        const val MOST_ICON_CHARS = 64 * 1024

        /** A line under a name, and the room a line needs. */
        const val MOST_SUMMARY_CHARS = 500

        /** What a plugin's manifest is called, beside the plugin itself. */
        const val MANIFEST = "plugin.json"

        const val MAX_NAME = 200

        /**
         * What a plugin may call itself: an identifier, and a short one.
         *
         * Short because it is the prefix on every function the plugin declares, and
         * a function name has 120 characters to fit in — 32 here leaves room for
         * the longest name a plugin could reasonably declare.
         */
        val KEY = Regex("[A-Za-z_$][A-Za-z0-9_$]{0,31}")

        /**
         * The starting point handed out by the button on the Plugins screen.
         *
         * Deliberately a plugin that already works: it answers the two questions
         * the server asks — which API it uses, and what it offers — so it can be
         * loaded unchanged and then edited into something useful.
         */
        val TEMPLATE = """
            /*
             * What the server provides. Declared, not imported: these exist before this
             * file is evaluated, and `declare` compiles to nothing — so an editor checks
             * your plugin against the real contract while the output stays what the
             * sandbox expects.
             */
            declare abstract class OrknuxPlugin {
              /** What this plugin calls itself, and the prefix on everything it declares. */
              abstract id(): string;
              /** Which plugin API this was written against. This server accepts @SUPPORTED@. */
              abstract apiVersion(): number;
              /** What this plugin offers to workflows. Defaults to none. */
              functions(): OrknuxFunction[];
              /**
               * What this plugin offers to agents, as tools a model calls.
               * Defaults to none.
               *
               * A surface of its own because it has a reader of its own: a
               * tool's description is read by a model deciding whether to call
               * it, where a function's is read by a person building a workflow.
               * A tool that is really one of the functions is declared as an
               * OrknuxFunctionTool, which proxies it rather than describing it
               * twice - params, return type and implementation stay the
               * function's, and only the name and description may be its own.
               */
              tools(): (OrknuxTool | OrknuxFunctionTool)[];
              /** What this plugin has to be told before it can work. Defaults to none. */
              parameters(): OrknuxParameter[];
              /**
               * Which JavaScript this plugin needs. Defaults to none.
               *
               * A plugin embeds its libraries rather than importing them - that is
               * what makes it portable - and a bundle written for a browser or for
               * Node often expects language features this sandbox does not switch
               * on. Say which, and whoever loads the plugin is shown the list and
               * has to accept it. Nothing is relaxed that was not accepted, and
               * nothing is relaxed for any other plugin.
               *
               * This is the whole of what can be asked for:
               @PERMISSION_LIST@
               *
               * There is deliberately no name for reading a file, opening a socket
               * or reaching a Java class. Those are not permissions this list can
               * express, and asking for one is refused rather than half-granted.
               *
               * Loading is done with none of them granted, because that is the run
               * that finds out which you want - so the top level of your bundle has
               * to evaluate without them. Ask for what `run` needs, not for what
               * loading needs.
               */
              permissions(): OrknuxPermission[];
              /**
               * What this plugin asks the server to do on its behalf. Defaults to
               * none.
               *
               * Deliberately not folded into `permissions()`: a permission turns
               * a language feature back on inside the sandbox, where a capability
               * has the server reach outside it - read a Slack thread, make an
               * HTTP request - and those are not decisions of the same size, so
               * whoever loads the plugin is shown them as two lists and accepts
               * each under its own name.
               *
               * This is the whole of what can be asked for:
               @CAPABILITY_LIST@
               *
               * Each `orknux.*` call below says which of these it needs. A call
               * made without its capability answers `{ error }` saying so, rather
               * than reaching anything.
               */
              capabilities(): OrknuxCapability[];
              /**
               * The kinds of host this plugin talks to, so a workspace can hold
               * several connections of each - two Prometheus servers, two wikis -
               * labelled by you rather than all reading as "HTTP". Each is an
               * HTTP connection (a URL, an auth kind, a secret) wearing your
               * label; a `connection` parameter may then name one of these to
               * be offered only your own hosts. Defaults to none.
               */
              connectionTypes?(): OrknuxConnectionType[];
              /**
               * The workflow actions this plugin offers: blocks a workflow's
               * Action node can be pointed at, listed in the editor under your
               * label beside "Send Message" and "HTTP Request". Defaults to none.
               *
               * A fourth surface with a fourth reader. A function is called
               * with positional arguments by whoever wrote the call; an action
               * is a node on a canvas whose inputs somebody wired by name. So
               * `run(input, context)` is handed one object keyed by parameter
               * name - an 'array' parameter arrives as an array, a parameter
               * nobody wired is absent - and a context carrying `settings`,
               * the same frozen object `this.settings` is, which is how it
               * reaches the connection the workspace pointed this plugin at.
               * What it returns is handed to the next node: an object's fields
               * under the names `outputs` declares, anything else under
               * `result`. Throw to fail the step; the message is what the run
               * shows.
               */
              actions?(): OrknuxAction[];
              /**
               * The library files this plugin ships with, as paths relative
               * to its own file: 'lib/util.js' or './lib/util.js'. Defaults
               * to none, which is every single-file plugin.
               *
               * The complete list - every file that arrives beside the plugin
               * is declared here, and every relative import in the plugin or
               * in a library resolves to a declared path. No absolute paths,
               * no URLs, no '..', no bare specifiers - an npm dependency is
               * still bundled in, not declared. Whoever loads the plugin is
               * shown the list and has to allow it: a zip's contents are
               * checked against it, and a load from a URL fetches these
               * files, resolved against the plugin's URL, and only these.
               */
              libraries(): string[];
              /**
               * What a workspace set those parameters to, keyed by name.
               *
               * Frozen, and put there by the server for the length of one call. A
               * parameter nothing usable is set for is absent rather than null, so
               * `this.settings.token === undefined` is the question to ask.
               *
               * This is the whole of what a plugin knows about the workspace it is
               * running for. Nothing reaches a plugin that a workspace did not
               * point at, which is what makes the parameter list a readable answer
               * to "what can this thing get at?".
               */
              readonly settings: Readonly<
                Record<string, string | number | boolean | OrknuxConnection<ConnectionType> | undefined>
              >;
            }

            /** The kinds of connection a workspace can hold. */
            type ConnectionType = @CONNECTION_TYPE_UNION@;

            /**
             * A connection the workspace configured, handed to a plugin as a handle.
             *
             * An id and a type and nothing else. A plugin cannot open a socket - the
             * sandbox has no network and no permission can ask for one - so what
             * crosses is a name for a connection the server will use on the plugin's
             * behalf, never the connection itself and never its credential.
             *
             * The type parameter is what makes `SlackConnection` mean something: it
             * appears as a member, so a Jira connection is not assignable where a
             * Slack one is wanted and the mistake is caught where it is written
             * rather than at the first call.
             */
            declare class OrknuxConnection<T extends ConnectionType> {
              readonly id: number;
              readonly type: T;
            }

            /** A Slack connection, which is what the Slack helpers take. */
            type SlackConnection = OrknuxConnection<'SLACK'>;

            /** One message in a Slack thread, as much of it as anything here needs. */
            interface SlackThreadMessage {
              /** Slack's timestamp, which is also the message's id. */
              ts: string;
              /** Who wrote it, or the bot that did. Null where Slack said neither. */
              user: string | null;
              text: string;
              /** Whether this is the message the thread hangs under rather than a reply. */
              parent: boolean;
            }

            /** A thread that was read, or why it could not be. */
            type SlackThread =
              | {
                  messages: SlackThreadMessage[];
                  /**
                   * Slack's own count of the replies under the parent.
                   *
                   * Not `messages.length - 1`: a page holds what was asked for and
                   * the count is of the whole thread. It is the number a filter
                   * wants - `replies === 1` is the first reply.
                   */
                  replies: number;
                  error?: undefined;
                }
              | {
                  /**
                   * Why not, in Slack's own words where they were Slack's:
                   * `not_in_channel`, `thread_not_found`, and the rest.
                   *
                   * A refusal rather than a thrown error, so a plugin can say
                   * something useful about it. Check for it before reading
                   * `messages`.
                   */
                  error: string;
                  messages?: undefined;
                  replies?: undefined;
                };

            /** A message that was posted - its channel and its own `ts` - or why not. */
            type SlackPost =
              | { channel: string; ts: string | null; error?: undefined }
              | { error: string; channel?: undefined; ts?: undefined };

            /** Whether a reaction went on. Already-reacted counts as ok. */
            type SlackReaction =
              | { ok: true; error?: undefined }
              | { error: string; ok?: undefined };

            /** The one message a permalink points at, or why it could not be read. */
            type SlackLinkedMessage =
              | { channel: string; ts: string; user: string | null; text: string; threadTs: string | null; error?: undefined }
              | { error: string; text?: undefined };

            /** Who a user id belongs to, or why that could not be said. */
            type SlackUserInfo =
              | { id: string; name: string; realName: string | null; displayName: string | null; bot: boolean; error?: undefined }
              | { error: string; id?: undefined };

            /** The notation Slack renders as a mention, ready to put in a message. */
            type SlackMention =
              | { mention: string; id: string; label: string; error?: undefined }
              | { error: string; mention?: undefined };

            /**
             * What the workflow editor's target box would offer for what was
             * typed, or why it could not be asked.
             */
            type SlackSuggestions =
              | {
                  /** FOUND, NOT_FOUND or UNCHECKED, as the editor says it. */
                  outcome: string;
                  /** One line ready to show, empty when there is nothing worth saying. */
                  message: string;
                  matches: {
                    /** Slack's own id: `U0123456789`, `C0123456789`. */
                    id: string;
                    /** What the field is filled with when this is taken: `@alice`, `#general`. */
                    name: string;
                    kind: 'CHANNEL' | 'USER';
                    realName: string | null;
                  }[];
                  /** Whether `matches` is everything that matches. */
                  complete: boolean;
                  error?: undefined;
                }
              | { error: string; matches?: undefined };

            /** What a search of Slack's messages came to, or why it could not be run. */
            type SlackSearchResult =
              | {
                  matches: {
                    channel: string | null;
                    channelName: string | null;
                    ts: string | null;
                    user: string | null;
                    text: string;
                    /** The way back to the message, for the thread around it. */
                    permalink: string | null;
                  }[];
                  /** How many the whole search holds, not how many came back. */
                  total: number;
                  error?: undefined;
                }
              | { error: string; matches?: undefined; total?: undefined };

            /**
             * What the server will do on a plugin's behalf.
             *
             * A plugin has no network and no way to ask for one: GraalJS has no
             * `fetch` and no sockets, and giving it either would mean handing it a
             * host object it could reflect from. So the calls that have to reach
             * outside are made by the server, under a capability the plugin
             * declares and a person accepts, and what crosses is data.
             *
             * Every call here needs its capability. Without it the call answers
             * `{ error }` saying so, rather than reaching anything.
             */
            declare const orknux: {
              slack: {
                /**
                 * The messages in one Slack thread, oldest first.
                 *
                 * Needs the `SLACK_READ_THREAD` capability.
                 *
                 * @param connection which Slack to read through. A workspace with
                 *   two Slack connections has two Slacks, and a reply that arrived
                 *   on one has to be read through that one - so pass the connection
                 *   the trigger says its event came in on rather than assuming.
                 * @param channel the channel's id, as the trigger gives it.
                 * @param threadTs the parent's timestamp - Slack's `thread_ts`,
                 *   which every reply in the thread carries.
                 * @param limit how many to fetch; the count comes back whatever
                 *   this is. Capped by the server.
                 */
                thread(
                  connection: SlackConnection,
                  channel: string,
                  threadTs: string,
                  limit?: number,
                ): SlackThread;

                /**
                 * Post a message through a connection the plugin was given.
                 *
                 * Needs the `SLACK_POST_MESSAGE` capability.
                 *
                 * @param connection which Slack to post through.
                 * @param channel the channel id, or a `#name`/`@handle` it resolves.
                 * @param text what to say.
                 * @param threadTs when set, the message joins that thread. The
                 *   answer's `ts` is the new message's own timestamp, which
                 *   `react` hangs on and a reply threads onto.
                 */
                post(
                  connection: SlackConnection,
                  channel: string,
                  text: string,
                  threadTs?: string,
                ): SlackPost;

                /**
                 * Add an emoji reaction to a message.
                 *
                 * Needs the `SLACK_ADD_REACTION` capability.
                 *
                 * @param ts the message's own `ts` - `post` returns one, and
                 *   every thread message carries one.
                 * @param emoji the short name, with or without the colons.
                 */
                react(
                  connection: SlackConnection,
                  channel: string,
                  ts: string,
                  emoji: string,
                ): SlackReaction;

                /**
                 * The one message a Slack permalink points at.
                 *
                 * Needs the `SLACK_READ_MESSAGE` capability.
                 *
                 * @param link the message's permalink - what a message pasted
                 *   into another message travels as.
                 */
                message(
                  connection: SlackConnection,
                  link: string,
                ): SlackLinkedMessage;

                /**
                 * Who a Slack user id is.
                 *
                 * Needs the `SLACK_READ_USER` capability.
                 *
                 * @param userId the id, bare or as the `<@U…>` notation a
                 *   message carries it in.
                 */
                user(
                  connection: SlackConnection,
                  userId: string,
                ): SlackUserInfo;

                /**
                 * The notation that pings somebody, from their name.
                 *
                 * Needs the `SLACK_MENTION` capability.
                 *
                 * @param name a display name, username, email, id, or a user
                 *   group's handle - with or without the `@`. The answer's
                 *   `mention` goes into `post`'s text as it is.
                 */
                mention(
                  connection: SlackConnection,
                  name: string,
                ): SlackMention;

                /**
                 * Search Slack's messages, the way the search box does.
                 *
                 * Needs the `SLACK_SEARCH` capability - and, from Slack's own
                 * side, a **user** token: `search.messages` refuses the usual
                 * bot token with `not_allowed_token_type`. A connection stores
                 * one in its User Token field (xoxp-, scope `search:read`) and
                 * search runs on it; a connection without one falls back to
                 * the bot token and that refusal comes back here as the error.
                 *
                 * @param query in Slack's search syntax - `in:#channel`,
                 *   `from:@name` and the rest work as they do in the box.
                 * @param limit how many matches to bring back; capped to one
                 *   page by the server.
                 */
                search(
                  connection: SlackConnection,
                  query: string,
                  limit?: number,
                ): SlackSearchResult;

                /**
                 * Members and channels for what somebody typed, ranked the way
                 * the workflow editor's target box ranks them.
                 *
                 * Needs the `SLACK_SUGGEST` capability. This is what a type a
                 * plugin defines - a `SlackUser` - completes values with, so a
                 * variable's picker and the editor's agree.
                 *
                 * @param typed a partial handle, real name, email or channel;
                 *   empty lists what there is.
                 * @param kind `USER` or `CHANNEL` to ask for one; omit for both.
                 * @param limit how many to bring back; at most fifty.
                 */
                suggest(
                  connection: SlackConnection,
                  typed: string,
                  kind?: 'USER' | 'CHANNEL',
                  limit?: number,
                ): SlackSuggestions;
              };

              http: {
                /**
                 * One HTTP request, made by the server on this plugin's behalf.
                 *
                 * Needs the `NETWORK_REQUEST` capability, which is the widest
                 * thing a plugin can ask for and the one an administrator will
                 * think hardest about: it reaches anything the server can. Ask
                 * for it only if the plugin is about an outside service, and say
                 * in the plugin's description which one.
                 *
                 * Where a request may get to is the installation's proxy rules,
                 * which this cannot see and cannot argue with. The body comes
                 * back as text; binary crosses as base64, which `upload` and
                 * `download` below spell out, because base64 is the one shape
                 * bytes have in a sandbox with nowhere else to put them.
                 *
                 * @param what the url on its own, or the whole request.
                 */
                request(
                  what:
                    | string
                    | {
                        url: string;
                        method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE' | 'HEAD';
                        headers?: Record<string, string>;
                        /**
                         * A string goes through untouched. An object is sent as
                         * JSON, and `content-type: application/json` is set
                         * unless a header already names one.
                         */
                        body?: string | object;
                        /** Base64 whose decoded bytes are the body; wins over `body`. */
                        bodyBase64?: string;
                        /** Bring the answer's bytes back as `base64` instead of `body`. */
                        binary?: boolean;
                      },
                ): OrknuxResponse;

                /** The two nearly everybody wants, spelled out. */
                get(url: string, headers?: Record<string, string>): OrknuxResponse;
                post(url: string, body?: string | object, headers?: Record<string, string>): OrknuxResponse;

                /**
                 * Sends bytes - a file - given as base64. Sent as an octet
                 * stream unless `contentType` or a header says what it is.
                 * The upload is capped at 10 MB of decoded bytes.
                 */
                upload(
                  url: string,
                  base64: string,
                  contentType?: string,
                  headers?: Record<string, string>,
                ): OrknuxResponse;

                /**
                 * Fetches binary content - an image, a PDF - and answers
                 * `base64`, `contentType` and `size` instead of `body`.
                 * Capped at 5 MB of bytes.
                 */
                download(url: string, headers?: Record<string, string>): OrknuxBinaryResponse;
              };

              /**
               * The AI session's own store, for a plugin that has to keep its
               * place between the calls of one conversation.
               *
               * What one tool call puts, a later one gets, for as long as the
               * session lives - and no other session ever sees it. Not a
               * capability: nothing outside the session is reached by it. The
               * doors only exist where the call was made inside an AI session;
               * anywhere else `put` answers `{ error }` saying so and `get`
               * answers null.
               */
              session: {
                store: {
                  /**
                   * Stores one value under a key, replacing what was there.
                   * The value makes the trip as JSON, so what comes back out
                   * is a copy - and anything JSON cannot say (a function,
                   * undefined) does not survive.
                   */
                  put(key: string, value: unknown): { ok: true } | { error: string };

                  /** What the key holds, parsed, or null where nothing does. */
                  get(key: string): unknown;
                };
              };

              /**
               * The way to say something, in a sandbox with no `console`.
               *
               * The line lands in the server's own log under this plugin's name.
               * Anything that is not a string is written as JSON, and a level
               * below the installation's threshold is dropped where it was
               * written - so tracing may stay in and costs one comparison until
               * somebody turns the level down. Needs no capability.
               */
              log: {
                debug(...said: unknown[]): void;
                info(...said: unknown[]): void;
                warn(...said: unknown[]): void;
                error(...said: unknown[]): void;
              };
            };

            /**
             * What came back, or why nothing did.
             *
             * A refusal is data rather than a thrown error, so a plugin can say
             * something useful about it - and so a condition that could not be
             * decided does not quietly decide.
             */
            type OrknuxResponse =
              | {
                  status: number;
                  headers: Record<string, string>;
                  body: string;
                  /**
                   * `body`, parsed, where it parsed as JSON - beside it, never
                   * instead of it. A reply that is not JSON simply has no `json`.
                   */
                  json?: unknown;
                  error?: undefined;
                }
              | { error: string; status?: undefined; headers?: undefined; body?: undefined; json?: undefined };

            /** A binary answer: the bytes as base64, and what they claim to be. */
            type OrknuxBinaryResponse =
              | {
                  status: number;
                  headers: Record<string, string>;
                  /** The answer's bytes, base64-encoded. */
                  base64: string;
                  /** How many bytes that decodes to. */
                  size: number;
                  /** The answer's own content-type header, or null where it sent none. */
                  contentType: string | null;
                  error?: undefined;
                }
              | { error: string; status?: undefined; headers?: undefined; base64?: undefined; size?: undefined; contentType?: undefined };

            /** The shape of a value crossing between a workflow and a plugin. */
            type OrknuxValueType = @VALUE_TYPE_UNION@;

            /** What a plugin may ask for. Exactly this list, and nothing else. */
            type OrknuxPermission = @PERMISSION_UNION@;

            /** What a plugin may ask the server to do. Exactly this list, and nothing else. */
            type OrknuxCapability = @CAPABILITY_UNION@;

            /** A kind of host a plugin declares; see `connectionTypes()`. */
            interface OrknuxConnectionType {
              /** An identifier, stable: it becomes part of what a connection stores. */
              name: string;
              /** What a person reads on the type menu and the connection list. */
              label: string;
              description?: string;
              /** What the URL box shows before anything is typed, e.g. 'https://prometheus.example.com'. */
              urlPlaceholder?: string;
            }

            /**
             * What an action's input or output may be; see `actions()`.
             *
             * 'object' is a free-form map. A plugin belongs to every workspace at
             * once, so it can name none of a workspace's own shapes here.
             */
            type OrknuxActionValueType = 'string' | 'number' | 'boolean' | 'array' | 'object';

            /** One input a workflow action takes, wired by name on the node. */
            interface OrknuxActionParameter {
              /** An identifier: letters, digits and underscores. */
              name: string;
              type: OrknuxActionValueType;
              /** Whether a node has to wire it. Defaults to true. */
              required?: boolean;
              /** Shown beside the port. */
              description?: string;
            }

            /** One output a workflow action hands on, read by the next node under this name. */
            interface OrknuxActionOutput {
              name: string;
              type: OrknuxActionValueType;
              description?: string;
            }

            /** What an action's `run` is told about where it is running. */
            interface OrknuxActionContext {
              /** What this workspace set the plugin's parameters to - the same object `this.settings` is. */
              settings: Readonly<Record<string, string | number | boolean | OrknuxConnection<ConnectionType> | undefined>>;
              workspaceId: number;
              /** The Action's name in the workspace's catalogue. */
              action: string;
              /** When the step started, ISO-8601. */
              now: string;
              timestamp: number;
            }

            /** A workflow action a plugin declares; see `actions()`. */
            interface OrknuxAction {
              /** An identifier, stable: an Action row stores it beside the plugin's key. */
              name: string;
              /** What the node picker shows - 'Reply in the thread' rather than 'respond'. */
              label: string;
              description?: string;
              parameters?: OrknuxActionParameter[];
              outputs?: OrknuxActionOutput[];
              /** What it does. `input` is keyed by parameter name; arrays stay arrays. */
              run: (input: Record<string, unknown>, context: OrknuxActionContext) => unknown;
            }

            declare class OrknuxFunction {
              constructor(declaration: {
                /** An identifier: letters, digits and underscores. */
                name: string;
                /** Optional; shown beside it in the interface. */
                description?: string;
                /** In the order `run` receives them. */
                params?: { name: string; type: OrknuxValueType }[];
                /** What it answers with. A function has to answer something. */
                returnType: OrknuxValueType;
                /** What it does. Stays here; the server calls back into it. */
                run: (...args: never[]) => unknown;
              });
            }

            /** A tool of the plugin's own: a declaration with a run, offered to agents. */
            declare class OrknuxTool {
              constructor(declaration: {
                /** An identifier: letters, digits and underscores. */
                name: string;
                /** Written for the model that reads it: when to call this, and with what. */
                description?: string;
                /** In the order `run` receives them. */
                params?: { name: string; type: OrknuxValueType }[];
                returnType: OrknuxValueType;
                run: (...args: never[]) => unknown;
              });
            }

            /**
             * A tool that is one of this plugin's own functions, exposed to agents.
             *
             * The utility that says so rather than a copy: params, return type and
             * implementation are the function's - including any edit somebody makes
             * to it on the server later - and only the name and the model-facing
             * description may be this tool's own. A `function` that functions()
             * does not declare is refused at load.
             */
            declare class OrknuxFunctionTool {
              constructor(declaration: {
                /** The name of one of this plugin's functions, as functions() declares it. */
                function: string;
                /** What agents call it. Defaults to the function's own name. */
                name?: string;
                /** Written for the model. Defaults to the function's description. */
                description?: string;
              });
            }

            /** What a parameter may be: exactly what a workspace variable can hold. */
            type OrknuxParameterType = @PARAMETER_TYPE_UNION@ | 'connection';

            declare class OrknuxParameter {
              constructor(declaration: {
                /** An identifier: letters, digits and underscores. */
                name: string;
                /** Optional; shown under it on the form somebody fills in. */
                description?: string;
                type: OrknuxParameterType;
                /**
                 * Whether the plugin can work without it. Defaults to true, because
                 * a parameter nobody needs is one nobody should be asked for.
                 *
                 * A workspace that has not answered a required one is marked as
                 * such in its plugin list and against the parameter itself.
                 */
                required?: boolean;
                /**
                 * Whether this is asking for something that should not be typed
                 * into a form. Defaults to false.
                 *
                 * Saying true refuses a typed-in value: the only way to answer it
                 * is to point at one of the workspace's variables, which is where
                 * this installation keeps things it encrypts.
                 */
                secret?: boolean;
                /**
                 * Which kind of connection, and required when `type` is
                 * `'connection'`.
                 *
                 * It narrows the picker to the connections the plugin can
                 * actually use: a Slack plugin handed a Jira connection has been
                 * handed a credential it cannot read and fails at the first call,
                 * which is a worse answer than a list that never offered it.
                 *
                 * What arrives in `settings` is then an `OrknuxConnection<T>` -
                 * an id and a type, never the connection's credential. The
                 * sandbox has no network; the server makes the call.
                 */
                connectionType?: ConnectionType | (string & {});
              });
            }

            /**
             * An Orknux plugin: a class extending OrknuxPlugin, exported as the default.
             *
             * Nothing else is accepted — a plain object with the right keys is refused,
             * because the server checks the prototype rather than probing for methods it
             * hopes are there.
             */
            export default class Teammates extends OrknuxPlugin {

              /**
               * What this plugin calls itself. Its identity, not its filename: loading
               * this id again replaces whatever is loaded under it.
               *
               * Also the prefix on everything below, so `isTeammate` is offered to a
               * workflow as `teammates_isTeammate`. No default: leave it out and loading
               * fails saying so.
               */
              id(): string {
                return 'teammates';
              }

              /**
               * Which plugin API this was written against. This server accepts
               * @SUPPORTED@, and refuses anything else rather than guessing.
               */
              apiVersion(): number {
                return @API_VERSION@;
              }

              /**
               * What this plugin has to be told before it can work.
               *
               * Each workspace answers these once, either by typing a value or by
               * pointing at one of its own variables, and what they come to arrives as
               * `this.settings`. Declaring them is also how a workspace can see what
               * this plugin is able to reach: nothing gets in that is not on this list.
               *
               * Defaults to none, so a plugin that needs nothing says nothing.
               */
              parameters(): OrknuxParameter[] {
                return [
                  new OrknuxParameter({
                    name: 'teamDomain',
                    description: 'The mail domain this workspace treats as its own.',
                    type: 'string',
                  }),
                ];
              }

              /**
               * Which JavaScript this plugin needs beyond what every plugin gets.
               *
               * Whoever loads this is shown the list and has to accept it, and only
               * what was accepted is turned on - for this plugin, in the sandbox one
               * of its calls runs in, and nowhere else. Editing this to ask for more
               * means being asked again: the acceptance names the permissions it was
               * given for, so a new one is not covered by it.
               *
               * Leave it out if you need nothing, which is the common case.
               */
              permissions(): OrknuxPermission[] {
                return ['INTL'];
              }

              /**
               * What this plugin offers.
               *
               * Each one is checked as it is constructed — a missing name, return type
               * or `run` fails here, while you are looking at it, rather than on the way
               * into the database. Defaults to none: a plugin may exist for something
               * other than offering functions.
               */
              functions(): OrknuxFunction[] {
                return [
                  new OrknuxFunction({
                    name: 'isTeammate',
                    description: 'Whether an email address belongs to a member of this workspace.',
                    params: [{ name: 'email', type: 'string' }],
                    returnType: 'boolean',

                    /*
                     * `this.settings` is what the workspace answered, and it is all a
                     * function has beyond what it was passed. Reaching out — looking a
                     * user up in the directory, asking which workspace this is — is
                     * something the server has still to hand over.
                     *
                     * Written as an arrow function so `this` is the plugin. A method
                     * would work too; both are called with the plugin as `this`.
                     */
                    run: (email: string): boolean => {
                      if (typeof email !== 'string' || email.length === 0) {
                        return false;
                      }
                      const domain = this.settings.teamDomain;
                      if (typeof domain !== 'string') {
                        // Required, so a workspace that has not set it is already
                        // marked as needing to. Answering no is the safe reading.
                        return false;
                      }
                      return email.endsWith('@' + domain);
                    },
                  }),
                ];
              }
            }
        """
    }
}

/** Listing what is loaded, and taking one out. */
@Controller
class PluginAPI(
    private val plugins: PluginRepository,
    private val access: WorkspaceAccess,
    private val declarations: PluginDeclarations,
    private val registry: PluginFunctionRegistry,
    private val shapeRegistry: PluginObjectRegistry,
    private val permissions: PluginPermissions,
    /** What it asks the server to do for it; see [PluginCapabilities]. */
    private val capabilities: PluginCapabilities,
    private val functions: WorkflowFunctionRepository,
    /** The files each plugin ships with, listed beside its declarations. */
    private val sources: PluginSources,
) {

    /**
     * Every tool the loaded plugins offer to agents.
     *
     * Not an administrator's query, the way listing the plugins is: granting a
     * tool to an agent is workspace work, so whoever edits an agent can be
     * shown what exists without being allowed to see what is loaded - the same
     * reasoning that puts a plugin's name on a function for the pickers.
     */
    @QueryMapping
    fun pluginTools(): List<PluginAgentToolView> =
        plugins.findAllByOrderByNameAsc().flatMap { plugin ->
            declarations.readTools(plugin.declaredTools).map { tool ->
                PluginAgentToolView(
                    name = "${plugin.key}_${tool.name}",
                    description = tool.description,
                    plugin = plugin.name,
                    // A proxy fronts a function with a page; the grant list can
                    // offer the jump. A tool with its own run has nowhere to go.
                    functionId = tool.proxyOf?.let { proxied ->
                        functions.findByScopeAndName(FunctionScope.PLUGIN, "${plugin.key}_$proxied")
                            ?.id?.toString()
                    },
                )
            }
        }

    /** Everything loaded into this installation, by name. */
    @QueryMapping
    fun plugins(): List<PluginView> {
        access.requireAdmin()
        return plugins.findAllByOrderByNameAsc().map {
            it.view(
                declarations.read(it.declaredFunctions),
                declarations.readParameters(it.declaredParameters),
                // What was accepted, shown to whoever is reading the list rather
                // than only to whoever accepted it. A decision about what code may
                // do that lives in a dialog is a decision nobody can audit.
                permissions.viewOf(permissions.grantedTo(it)),
                sources.librariesOf(it).map { library -> library.path },
                declarations.readSkills(it.declaredSkills),
                declarations.readObjects(it.declaredObjects),
                declarations.readTypes(it.declaredTypes),
                declarations.readConnectionTypes(it.declaredConnectionTypes, it.key, it.name),
                declarations.readActions(it.declaredActions, it.key, it.name),
            )
        }
    }

    /**
     * Takes a plugin out of the installation.
     *
     * Nothing refers to a plugin yet, so this is a delete. Once workflows can
     * point at what a plugin registers, this has to grow an in-use check and
     * leave something behind for a graph to render — removing a plugin out from
     * under a running workflow is not a thing to do quietly.
     */
    @MutationMapping
    @Transactional
    fun unloadPlugin(@Argument id: Long): Boolean {
        access.requireAdmin()
        val plugin = plugins.findByIdOrNull(id) ?: throw PluginNotFoundException(id)

        /*
         * Its functions go with it — the database cascades them — so anything still
         * calling one has to be dealt with first. Checked here rather than left to
         * the cascade, because a workflow pointing at a function that has stopped
         * existing fails at the moment it runs, which is the worst moment to learn.
         */
        val used = registry.inUse(plugin)
        if (used.isNotEmpty()) throw PluginInUseException(used)

        // Its shapes go the same way, and anything pointing at one has the
        // same problem: a property whose reference stopped existing describes
        // nothing, and says so at the moment it matters.
        val pointed = shapeRegistry.inUse(plugin)
        if (pointed.isNotEmpty()) throw PluginObjectsInUseException(pointed)

        plugins.delete(plugin)
        return true
    }
}

/**
 * A workspace's side of a plugin: what it has been told, and what it has not.
 *
 * Separate from [PluginAPI] because the audience is: listing and unloading are an
 * administrator's, while answering what a plugin needs belongs to whoever runs the
 * workspace it will run for. The same plugin, two decisions, two sets of people.
 *
 * A plugin declaring no parameters still appears here. "This one needs nothing"
 * is an answer worth being able to read, and a plugin that grows a parameter
 * later should not appear out of nowhere.
 */
@Controller
class WorkspacePluginAPI(
    private val plugins: PluginRepository,
    private val parameters: PluginParameters,
    private val workspaces: WorkspaceRepository,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    /** Every loaded plugin, with what this workspace has set it to. */
    @QueryMapping
    fun workspacePlugins(@Argument workspaceId: Long): List<WorkspacePluginView> {
        requireWorkspaceAccess(workspaceId)
        return plugins.findAllByOrderByNameAsc().map { parameters.viewOf(it, workspaceId) }
    }

    /**
     * Answers one parameter, with a value or with one of the workspace's variables.
     *
     * Answers with the whole plugin rather than the one parameter, because setting
     * one changes whether the plugin is still marked as needing something - and a
     * screen that had to work that out for itself would eventually disagree with
     * the server about it.
     */
    @MutationMapping
    @Transactional
    fun setPluginParameter(
        @Argument workspaceId: Long,
        @Argument pluginId: Long,
        @Argument name: String,
        @Argument literal: String?,
        @Argument variableId: Long?,
    ): WorkspacePluginView {
        requireWorkspaceAccess(workspaceId)
        val plugin = plugins.findByIdOrNull(pluginId) ?: throw PluginNotFoundException(pluginId)

        parameters.set(plugin, workspaceId, name, literal?.trim()?.ifEmpty { null }, variableId, currentUser())

        /*
         * What it was set to is not recorded, only that it was. A literal is as
         * likely to be a hostname as the one piece of this that should never have
         * been typed in, and the audit log is read by more people than the
         * variables screen is.
         */
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.INTEGRATION,
            "Plugin ${plugin.key}: parameter $name set",
        )
        return parameters.viewOf(plugin, workspaceId)
    }

    /** Unsets one parameter. A required one is marked as missing again. */
    @MutationMapping
    @Transactional
    fun clearPluginParameter(
        @Argument workspaceId: Long,
        @Argument pluginId: Long,
        @Argument name: String,
    ): WorkspacePluginView {
        requireWorkspaceAccess(workspaceId)
        val plugin = plugins.findByIdOrNull(pluginId) ?: throw PluginNotFoundException(pluginId)

        parameters.clear(plugin, workspaceId, name)
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.INTEGRATION,
            "Plugin ${plugin.key}: parameter $name cleared",
        )
        return parameters.viewOf(plugin, workspaceId)
    }

    private fun requireWorkspaceAccess(workspaceId: Long) {
        access.requireVisible(workspaceId)
    }

    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"
}

class PluginNotFoundException(id: Long) : RuntimeException("There is no plugin $id")

class PluginEmptyException : RuntimeException("That file is empty")

class PluginTooLargeException(maxKb: Long) : RuntimeException("A plugin may be at most $maxKb KB")

class PluginNotJavaScriptException(filename: String) :
    RuntimeException("$filename is not JavaScript; a plugin is a .js or .mjs file")

class PluginNotTextException : RuntimeException("That file is not UTF-8 text")

/** An archive that is not the shape a plugin zip has; the sentence says what is. */
class PluginZipInvalidException(what: String) : RuntimeException(what)

/**
 * What a plugin says about itself, from the `plugin.json` beside it.
 *
 * Prose for a screen and nothing more. What a plugin is *allowed* comes from
 * the code, read at the moment somebody accepts it — a manifest that could
 * widen that would be a plugin describing itself into privileges. Every field
 * is optional: a plugin without a manifest is the ordinary case.
 */
/** A plugin archive taken apart: the code, and what it says about itself. */
data class Unzipped(
    val filename: String,
    val source: String,
    val libraries: List<PluginLibraryFile>,
    val manifest: PluginManifest?,
    val icon: String?,
    /** The white glyph, where the archive held one. */
    val iconDark: String? = null,
)

data class PluginManifest(
    val name: String?,
    val summary: String?,
    val author: String?,
    val version: String?,
    /** A path beside the plugin, for the face it wears. */
    val icon: String?,
    /**
     * And the same in white, for a dark ground.
     *
     * Usually absent, and usually there anyway: the marketplace's own
     * convention is a `-white` sibling of the icon, so a manifest that names
     * only `icon.svg` still has `icon-white.svg` beside it. Named here where a
     * plugin wants to call it something else.
     */
    val iconDark: String?,
)

/**
 * What the browser sends to load a plugin from where it lives.
 *
 * The creator is bound explicitly for the reason `ChatStreamRequest` binds its
 * own: Jackson 3 has no Kotlin module here, so parameter names alone are not
 * enough to deserialize from.
 */
data class PluginUrlRequest @com.fasterxml.jackson.annotation.JsonCreator constructor(
    @com.fasterxml.jackson.annotation.JsonProperty("url") val url: String,
    /** The same comma-separated names and paths the upload's accept field takes. */
    @com.fasterxml.jackson.annotation.JsonProperty("accept") val accept: String? = null,
)

class PluginUrlInvalidException(what: String) : RuntimeException(
    "\"$what\" is not a URL a plugin loads from: http or https, pointing at the plugin's own .js file.",
)

class PluginUrlUnreachableException(where: String, why: String) : RuntimeException(
    "$where could not be fetched: $why.",
)

/**
 * The catalog remembers this version and no longer holds its bytes.
 *
 * The marketplace keeps the record of every release and the files of the ten
 * newest, so an older one can be named, listed and discussed and cannot be
 * installed. Said before the download rather than after, where it arrives as a
 * 404 and reads as the marketplace being broken.
 */
class PluginReleaseGoneException(key: String, version: String) : RuntimeException(
    "$key $version is in the marketplace's history but its files are no longer held; install a newer version.",
)

/**
 * What arrived is not what the catalog published.
 *
 * The digest is the marketplace's own statement about the bytes of a release,
 * so a download that does not match it is a download nobody should run - and
 * the difference between the two is worth printing, because the honest causes
 * (a proxy rewriting a response, a half-finished transfer) look nothing alike
 * from the outside.
 */
class PluginDigestMismatchException(key: String, expected: String, held: String) : RuntimeException(
    "$key did not arrive as the marketplace published it: it says sha256 $expected and what came back is $held.",
)

/**
 * The plugin never said which API it uses.
 *
 * The reason is passed on as the plugin gave it — no default export, no
 * `apiVersion` method, threw while loading — because that sentence is what tells
 * whoever wrote it what to change.
 */
class PluginApiVersionUnreadableException(reason: String) :
    RuntimeException("The plugin could not be asked which plugin API it uses: $reason")

/**
 * It is not a plugin, or it did not hold up the contract.
 *
 * The reason comes from the plugin's own sandbox — usually the base class saying
 * which method was not implemented — because that sentence is what tells whoever
 * wrote it what to change.
 */
class PluginContractException(reason: String) :
    RuntimeException("That file is not a usable plugin: $reason")

class PluginIdInvalidException(key: String) : RuntimeException(
    "\"$key\" cannot be a plugin id: it has to start with a letter and hold only " +
        "letters, digits or underscores, up to 32 of them — it becomes the prefix on " +
        "every function the plugin declares.",
)

class PluginApiVersionUnsupportedException(asked: Int, supported: Set<Int>) : RuntimeException(
    "The plugin uses plugin API version $asked, which this server does not know. " +
        "It supports ${supported.sorted().joinToString(", ")}.",
)

@Component
class PluginExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            is PluginEmptyException,
            is PluginTooLargeException,
            is PluginNotJavaScriptException,
            is PluginNotTextException,
            is PluginContractException,
            is PluginApiVersionUnsupportedException,
            
            is PluginDeclarationInvalidException,
            
            is PluginIdInvalidException,
            is PluginPermissionUnknownException,
            is PluginCapabilityUnknownException,
            is PluginAgreementNeededException,
            is PluginInUseException,
            is PluginFunctionInUseException,
            is PluginParameterUnknownException,
            is PluginParameterAmbiguousException,
            is PluginParameterEmptyException,
            is PluginParameterNotSecretException,
            is PluginParameterNotValueException,
            is PluginParameterVariableElsewhereException,
            is PluginZipInvalidException,
            is PluginUrlInvalidException,
            -> ErrorType.BAD_REQUEST

            /*
             * A service that will not answer is not the caller's mistake, and
             * reporting it as a bad request would have somebody checking what
             * they typed instead of checking the marketplace. What matters
             * either way is that the sentence travels: before these were
             * named here, a marketplace that was down and a key that does not
             * exist both arrived on screen as INTERNAL_ERROR and an id.
             */
            is MarketplaceUnreachableException,
            is PluginUrlUnreachableException,
            -> ErrorType.INTERNAL_ERROR

            is PluginNotFoundException,
            is MarketplaceOfferingUnknownException,
            -> ErrorType.NOT_FOUND

            else -> return null
        }

        return GraphQLError.newError()
            .errorType(errorType)
            .message(exception.message)
            .path(environment.executionStepInfo.path)
            .location(environment.field.sourceLocation)
            .build()
    }
}

