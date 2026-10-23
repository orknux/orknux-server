package io.mszymanski.orknux.server.library

import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.zip.GZIPInputStream

@ConfigurationProperties(prefix = "orknux.library.registry")
data class LibraryRegistryProperties(
    /**
     * Where a package is fetched from. Empty switches fetching off altogether.
     *
     * Empty is the offline installation's setting, and it is a real one rather
     * than a way of saying "leave the default": nothing here is fetched on a
     * timer or on anybody's behalf, so a registry that cannot be reached costs
     * only the administrator who pressed Install — but a field offering to
     * install from a registry that is not there is a field that lies, so an
     * installation without one says so and the screen shows the upload alone.
     */
    val url: String = "https://registry.npmjs.org",
    /** How long the registry has to answer, for the metadata and for the file. */
    val timeout: Duration = Duration.ofSeconds(30),
)

/**
 * Fetching one package from an npm registry, once, at an administrator's request.
 *
 * **Why this exists at all, given what [ScriptLibrary] says.** That note rules out
 * a library that *is* a name from a registry, and it still does. What is fetched
 * here is a file, and the file is what is stored and what runs; the registry is a
 * way of *getting* it and is never consulted again. Nothing about the sandbox
 * changes — it has no network before this and none after — and an installation
 * with no registry configured goes on being an installation where a library is a
 * file somebody uploads.
 *
 * The three things that have to be true for that to be honest, and where each one
 * is enforced:
 *
 * **A version is pinned, by the person asking.** [resolve] refuses `latest`, a
 * range and a bare name. There is no default, because a default would be a
 * version this server picked on a day nobody was looking, and "what code is
 * running here" would then have as many answers as there were installs.
 *
 * **What was fetched is recorded and checked.** The registry says what the file it
 * is about to serve hashes to; [verify] hashes what arrived and compares. The
 * resolved version, the URL, that hash and the path inside the package are all
 * stored on the row, so the artefact can be shown to be what it claims — and can
 * be fetched again elsewhere and compared.
 *
 * **It goes out the way everything else does.** The client is built from
 * [ProxyRouter.builder], so an installation whose outbound traffic has to cross a
 * proxy reaches the registry through it. A second HTTP client here would be the
 * bug #176 found in Slack in a new place: rules that cover most of the product and
 * silently miss one screen.
 *
 * **On dependencies, this refuses rather than bundles.** See [Fetched] and
 * [LibraryDependsException].
 */
@Component
@EnableConfigurationProperties(LibraryRegistryProperties::class)
class NpmRegistry(
    private val properties: LibraryRegistryProperties,
    private val mapper: ObjectMapper,
    proxies: ProxyRouter,
    /** The numbers this reads from Admin -> Settings. Issue #453. */
    private val tuning: Tuning = Tuning.DEFAULTS,
) {

    private val client: HttpClient = proxies.builder()
        .connectTimeout(properties.timeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** Where packages come from, or null when this installation fetches none. */
    val registry: String? get() = properties.url.trim().trimEnd('/').ifEmpty { null }

    /**
     * Fetches one package and answers the single module inside it, or refuses.
     *
     * [spec] is what an administrator typed: `random@4.1.0`, or
     * `@scope/name@1.2.3`. Everything it can be wrong about is answered as a
     * sentence, because this is a field somebody is looking at.
     *
     * **What is trusted here, and what is not.** The registry is trusted, because
     * an operator configured it — [spec] is a package name and never an address,
     * so nobody with this screen can aim the server at a host of their choosing.
     * The registry's own `dist.tarball` is followed, and redirects with it, for
     * the same reason: a mirror serves its files from wherever it serves them.
     * What is *not* trusted is what comes back, and that is what [verify] is
     * for — the bytes have to hash to the value the metadata named before
     * anything is read out of them.
     */
    fun fetch(spec: String): Fetched {
        val registry = registry ?: throw LibraryRegistryOffException()
        val (name, version) = resolve(spec)

        val manifest = json("$registry/${encoded(name)}/$version", "$name@$version")
        val distribution = manifest.path("dist")
        val tarball = distribution.path("tarball").asString("").trim()
        if (!tarball.startsWith("http://") && !tarball.startsWith("https://")) {
            throw LibraryRegistrySilentException("$name@$version", "it named no file to download")
        }

        // The version the registry answered with, not the one that was asked for.
        // They agree for an exact version, and the registry is the one that knows.
        val resolved = manifest.path("version").asString(version).ifBlank { version }

        val archive = bytes(tarball, "$name@$resolved")
        val integrity = verify(archive, distribution, "$name@$resolved")

        val described = mapper.readTree(
            entry(archive, PACKAGE_JSON) ?: throw LibraryRegistrySilentException(
                "$name@$resolved",
                "its file holds no package.json",
            ),
        )

        val (path, source) = entryOf(archive, described) ?: throw LibraryNoEntryException("$name@$resolved")

        val format = LibrarySource.formatOf(source)
        LibrarySource.imported(source)?.let {
            throw LibraryDependsException("$name@$resolved", path, "imports", it)
        }
        if (format == LibrarySource.COMMONJS) {
            LibrarySource.required(source)?.let {
                throw LibraryDependsException("$name@$resolved", path, "requires", it)
            }
        }

        return Fetched(
            packageName = name,
            version = resolved,
            url = tarball,
            integrity = integrity,
            entry = path,
            source = source,
        )
    }

    /**
     * A package and everything it needs, fetched, ready to be made into one file.
     *
     * **The install that used to be a refusal.** A package whose entry reaches a
     * second file — which is most packages — was answered with *build a bundle
     * and upload it*, and the person reading that had to install Node to do
     * something this server was already holding the archive for. Issue #319.
     *
     * The three rules at the top of this file are unchanged and this is held to
     * all of them, once per package rather than once: every archive is verified
     * against the hash its registry named, every version ends up written down,
     * and it all goes out through [ProxyRouter]. The one worth saying again is
     * the version. A dependency's range is not something anybody typed — npm
     * publishes ranges — so it is resolved here, by [NpmVersions], against what
     * the registry has published, and the version it resolved to goes on the
     * row. The bundle is then an artefact with a bill of materials rather than a
     * black box.
     *
     * Flat, and a conflict is refused rather than nested. Node would install two
     * copies of a package at two versions; one file cannot hold two, and a bundle
     * that silently gave one of them the other's code would be the worst kind of
     * wrong. So it says which package, and both versions.
     */
    fun gather(spec: String): Gathered {
        val (name, version) = resolve(spec)
        val root = one(name, version)

        val modules = LinkedHashMap<String, String>()
        val packages = LinkedHashMap<String, String>()
        val parts = mutableListOf<BundlePart>()
        val versions = LinkedHashMap<String, String>()

        modules.putAll(root.files)
        /*
         * The root has to have one - it is the thing being installed, and a
         * library is entered somewhere. The two ways it can fail are different
         * and are said differently: a package whose manifest points at nothing
         * that is in it has published nothing this can install, and one whose
         * entry is an ES module has published something this will not transpile.
         */
        val candidates = existing(root)
        if (candidates.isEmpty()) throw LibraryNoEntryException("$name@${root.version}")
        val entry = entryOf(root, "")
            ?: throw LibraryBundleEsmException("$name@${root.version}", candidates.first())
        parts += root.part(entry)
        versions[name] = root.version

        /*
         * Breadth first, so what is fetched is the graph nearest the package
         * somebody actually asked for - and so a limit on how many packages is a
         * limit on how far this wanders rather than on which branch it happened
         * to take first.
         */
        val queue = ArrayDeque<Pair<String, JsonNode>>()
        queue.addLast(name to root.described)

        while (queue.isNotEmpty()) {
            val (owner, described) = queue.removeFirst()
            val needs = described.path("dependencies")
            if (!needs.isObject) continue

            for (dependency in needs.propertyNames()) {
                val range = needs.path(dependency).asString("").trim()
                val already = versions[dependency]
                if (already != null) {
                    /*
                     * The same package wanted at two versions. Node nests a copy
                     * of each; one file cannot, and choosing one of them for both
                     * is choosing somebody's bug on their behalf.
                     */
                    if (!NpmVersions.allows(range, already)) {
                        throw LibraryBundleConflictException(spec, dependency, already, range)
                    }
                    continue
                }
                if (parts.size >= tuning[Tunable.NPM_PACKAGES_MOST]) throw LibraryBundleTooManyException(spec, tuning[Tunable.NPM_PACKAGES_MOST])

                val resolved = NpmVersions.best(range, versionsOf(dependency))
                    ?: throw LibraryBundleUnsatisfiedException(spec, owner, dependency, range)

                val fetched = one(dependency, resolved)
                val prefix = "node_modules/$dependency/"
                modules.putAll(fetched.files.mapKeys { prefix + it.key })

                /*
                 * A dependency need not have a root entry, and refusing one that
                 * has none was wrong. `math-intrinsics` publishes `"main": false`
                 * and a dozen subpaths, and what reaches for it reaches for
                 * `math-intrinsics/abs` - which resolves as a file like any
                 * other. So the entry is recorded where there is one and left
                 * alone where there is not: a bare `require` for a package with
                 * no entry is then refused by the bundler, naming the file that
                 * asked, and one that is never required does not block anything.
                 *
                 * The ES module check moves with it, to the files the graph
                 * actually reaches. A dependency shipping a modern build it never
                 * enters is not this installation's problem.
                 */
                val within = entryOf(fetched, prefix)
                if (within != null) packages[dependency] = within
                parts += fetched.part(within ?: "")
                versions[dependency] = resolved

                if (modules.size > MAX_FILES) throw LibraryBundleTooManyException(spec, tuning[Tunable.NPM_PACKAGES_MOST])
                queue.addLast(dependency to fetched.described)
            }
        }

        return Gathered(entry = entry, modules = modules, packages = packages, parts = parts)
    }

    /**
     * One package's archive, verified, with every file in it that could be a
     * module.
     *
     * `.js`, `.cjs`, `.mjs` and `.json`, and nothing else: what is being built is
     * a module graph, and a package's README, its type declarations and its
     * source maps are the megabytes no entry could ever reach.
     */
    private fun one(name: String, version: String): One {
        val registry = registry ?: throw LibraryRegistryOffException()
        val named = "$name@$version"

        val manifest = json("$registry/${encoded(name)}/$version", named)
        val distribution = manifest.path("dist")
        val tarball = distribution.path("tarball").asString("").trim()
        if (!tarball.startsWith("http://") && !tarball.startsWith("https://")) {
            throw LibraryRegistrySilentException(named, "it named no file to download")
        }

        val resolved = manifest.path("version").asString(version).ifBlank { version }
        val archive = bytes(tarball, "$name@$resolved")
        val integrity = verify(archive, distribution, "$name@$resolved")

        val files = read(archive, MAX_FILES) { path ->
            path.startsWith("package/") && MODULE_FILES.any { path.endsWith(it) }
        }.mapKeys { it.key.removePrefix("package/") }

        val described = files["package.json"]?.let { mapper.readTree(it) }
            ?: throw LibraryRegistrySilentException("$name@$resolved", "its file holds no package.json")

        return One(name, resolved, tarball, integrity, described, shimmed(described, files))
    }

    /**
     * A package's own `browser` map, applied to its files.
     *
     * **What it is, and why honouring it is right here.** It is the field a
     * package publishes to say what to do with a file where there is no Node —
     * `{"./util.inspect.js": false}` means *this one is not available, use
     * nothing*. That is exactly the environment a library runs in: no files, no
     * network, no Node. `object-inspect` publishes precisely this, and without it
     * `qs` — four packages away — was refused for requiring `util`, which the
     * author had already said would not happen outside Node.
     *
     * Two forms and no third. `false` is the file replaced by an empty module,
     * which is what the author asked for, and the stub says so in a comment so a
     * bundle is not quietly different from the archive it names. A path is the
     * file replaced by another, and only where that other sits in the same
     * directory: a replacement from elsewhere would take its own relative
     * `require`s with it and resolve them against the wrong place, and a bundle
     * that resolved somebody's specifier to the wrong file is worse than one that
     * refused.
     *
     * A string `browser` — an alternative entry rather than a map — is
     * deliberately not read. It would change which file an ordinary one-file
     * install picks, and that is a working feature this has no business moving.
     */
    private fun shimmed(described: JsonNode, files: Map<String, String>): Map<String, String> {
        val browser = described.path("browser")
        if (!browser.isObject) return files

        val held = LinkedHashMap(files)
        for (named in browser.propertyNames()) {
            val from = file(named)?.let { LibraryBundle.fileAt(it) { path -> held.containsKey(path) } } ?: continue
            val asked = browser.path(named)

            if (asked.isBoolean && !asked.asBoolean()) {
                held[from] = EMPTY_MODULE
                continue
            }
            val target = file(text(asked))?.let { LibraryBundle.fileAt(it) { path -> held.containsKey(path) } }
                ?: continue
            if (target.substringBeforeLast('/', "") == from.substringBeforeLast('/', "")) {
                held[from] = requireNotNull(held[target])
            }
        }
        return held
    }

    /**
     * The file a package is entered by when it is bundled.
     *
     * Its CommonJS build where it publishes one, because that goes into the
     * bundle exactly as it was published. An ES build otherwise - which used to
     * be a refusal and is now a compile, since `import` is syntax and cannot be
     * handed a `require`. The one-file install still prefers the ES module for
     * the opposite reason: the sandbox runs one natively and nothing has to
     * touch it at all.
     */
    private fun entryOf(one: One, prefix: String): String? = existing(one)
        /*
         * Through the file rules rather than by exact name. A manifest naming
         * `./index` means `index.js` - `ms` does exactly that, and looked up by
         * name alone the most ordinary CommonJS package there is came back as
         * one that had published nothing this could enter.
         */
        /*
         * A CommonJS build first where there is one, because it goes into the
         * bundle as it was published; an ES one otherwise, which the compiler
         * rewrites on the way in. Preferring the published spelling is not
         * fussiness - it is the difference between a bundle somebody can compare
         * with the package and one they have to take on trust.
         */
        .let { held -> held.firstOrNull { one.files[it]?.let { text -> !LibrarySource.esm(text) } == true } ?: held.firstOrNull() }
        ?.let { prefix + it }

    /**
     * The files the manifest points at that are actually in the package.
     *
     * Empty means the package names nothing this could enter - `"main": false`
     * and subpath exports alone, which is a real and increasingly common way to
     * publish. That is a different refusal from an entry this will not
     * transpile, and the two used to arrive as the same sentence.
     */
    private fun existing(one: One): List<String> = modules(one.described)
        .mapNotNull { candidate -> LibraryBundle.fileAt(candidate) { one.files.containsKey(it) } }

    /**
     * Which versions of a package the registry has published.
     *
     * The abbreviated packument, which is what npm's own client asks for: the
     * full document for a popular package is megabytes of changelogs and
     * maintainer lists, and what is wanted here is a list of version numbers.
     */
    private fun versionsOf(name: String): List<String> {
        val registry = registry ?: throw LibraryRegistryOffException()
        val packument = json("$registry/${encoded(name)}", name, ABBREVIATED)
        val versions = packument.path("versions")
        if (!versions.isObject) throw LibraryPackageMissingException(name)
        return versions.propertyNames().toList()
    }

    /** One package, fetched and verified, with its files. */
    private data class One(
        val name: String,
        val version: String,
        val url: String,
        val integrity: String,
        val described: JsonNode,
        val files: Map<String, String>,
    ) {
        fun part(entry: String) = BundlePart(name, version, url, integrity, entry)
    }

    /**
     * A package name and an exact version, or a sentence about why that is not
     * what was typed.
     *
     * The version has no default on purpose. `latest` and a range are both
     * refused by the same rule and for the same reason: a library is the answer
     * to "what code is running in this installation", and a specification that
     * resolves differently tomorrow is not an answer.
     */
    internal fun resolve(spec: String): Pair<String, String> {
        val typed = spec.trim()
        if (typed.isEmpty()) throw LibraryPackageInvalidException(typed, "name a package: random@4.1.0")

        // The `@` that separates the version is the last one, since a scoped
        // package's name starts with one of its own.
        val at = typed.lastIndexOf('@')
        val name = if (at <= 0) typed else typed.take(at)
        val version = if (at <= 0) "" else typed.substring(at + 1).trim()

        if (!PACKAGE.matches(name)) {
            throw LibraryPackageInvalidException(typed, "\"$name\" is not an npm package name")
        }
        if (!VERSION.matches(version)) {
            throw LibraryPackageInvalidException(
                typed,
                "name an exact version — $name@1.2.3 — because a range or \"latest\" is not an answer to " +
                    "what code is running here",
            )
        }
        return name to version
    }

    /**
     * The file this installs, with the path it came from, or null.
     *
     * **An ES module is preferred wherever the package ships one**, and that is
     * decided by reading the candidate rather than by trusting the field it was
     * named under: `exports.default` is as often the CommonJS build as not, and a
     * package that publishes both should have its module installed even where its
     * manifest lists them the other way round. So the candidates are walked in
     * [modules]' order, the first that is genuinely an ES module wins, and the
     * first that exists at all is the fallback.
     *
     * More than one candidate is walked rather than the first taken for a second
     * reason too: a package's `exports` frequently points at a file it did not
     * publish — a `./src` entry a build step was supposed to produce — and
     * falling through is what npm's own resolver would do next.
     */
    private fun entryOf(archive: ByteArray, described: JsonNode): Pair<String, String>? {
        var fallback: Pair<String, String>? = null
        for (candidate in modules(described)) {
            val source = entry(archive, "package/$candidate") ?: continue
            if (LibrarySource.formatOf(source) == LibrarySource.ESM) return candidate to source
            if (fallback == null) fallback = candidate to source
        }
        return fallback
    }

    /**
     * The files in the package that could be the entry, best first.
     *
     * ES builds first and the CommonJS ones after them, because the sandbox runs
     * a module natively and a CommonJS file only through the wrapper
     * [LibrarySource.runnable] puts round it. Within the ES half `exports` is
     * asked before `module`, and `main` counts as one only where `"type":
     * "module"` says it is.
     *
     * Then the same manifest is read again for what it says is CommonJS: the
     * `require` condition of `exports`, and `main`. Until #274 those were not
     * candidates at all and a package shipping only them was refused; a CommonJS
     * file that requires nothing is a self-contained module with a different
     * spelling, and refusing it was a rule wider than its reason.
     */
    internal fun modules(described: JsonNode): List<String> {
        val found = LinkedHashSet<String>()

        val exports = described.path("exports")
        if (exports.isTextual) file(exports.asString(""))?.let(found::add)
        val root = if (exports.isObject) (if (exports.has(".")) exports.path(".") else exports) else null
        if (root != null) conditions(root, found, 0, PREFERRED)

        file(text(described.path("module")))?.let(found::add)
        val main = file(text(described.path("main")))
        if (described.path("type").asString("") == "module") main?.let(found::add)

        if (root != null) conditions(root, found, 0, REQUIRED)
        main?.let(found::add)
        return found.toList()
    }

    /**
     * Walks an `exports` condition tree, following the conditions it is given.
     *
     * Called twice: once with [PREFERRED], the conditions that name an ES module,
     * and once with [REQUIRED], which is the CommonJS branch. A subpath is never
     * asked for — a library here is one module, and a package's second entry
     * point is a second file it would have to import.
     */
    private fun conditions(node: JsonNode, into: MutableSet<String>, depth: Int, asked: List<String>) {
        if (depth > MAX_CONDITIONS) return
        if (node.isTextual) {
            file(node.asString(""))?.let(into::add)
            return
        }
        if (!node.isObject) return
        for (condition in asked) {
            if (node.has(condition)) conditions(node.path(condition), into, depth + 1, asked)
        }
    }

    /**
     * A field's value, but only where it actually is one.
     *
     * `asString("")` **coerces**: asked of `"main": false` it answers `"false"`,
     * and a package was then looked for in a file of that name. npm's spelling
     * for "this package has no root entry" is exactly `"main": false`, and
     * `math-intrinsics` publishes it — so the one case this most needed to
     * handle was the one it invented a filename for. Found against the real
     * registry.
     */
    private fun text(node: JsonNode): String = if (node.isTextual) node.asString("") else ""

    /** A path inside the package, or null for anything that is not a plain file. */
    private fun file(named: String): String? {
        val path = named.trim().removePrefix("./")
        if (path.isEmpty() || path.startsWith("/") || path.contains("..") || path.contains('*')) return null
        return path
    }

    /**
     * Hashes what arrived and compares it with what the registry said it would be.
     *
     * `dist.integrity` is the modern spelling and `dist.shasum` the old one; a
     * registry answering with neither is refused rather than trusted, because an
     * artefact nothing can be compared against is exactly the thing this feature
     * is not allowed to store. What comes back is the claim that was checked, and
     * it goes on the row.
     */
    internal fun verify(archive: ByteArray, distribution: JsonNode, named: String): String {
        val integrity = distribution.path("integrity").asString("").trim()
        if (integrity.isNotEmpty()) {
            val algorithm = integrity.substringBefore('-', "")
            val expected = integrity.substringAfter('-', "")
            val digest = when (algorithm) {
                "sha512" -> "SHA-512"
                "sha384" -> "SHA-384"
                "sha256" -> "SHA-256"
                else -> throw LibraryRegistrySilentException(named, "it hashed the file with \"$algorithm\"")
            }
            val actual = Base64.getEncoder().encodeToString(MessageDigest.getInstance(digest).digest(archive))
            if (actual != expected) throw LibraryIntegrityException(named, integrity, "$algorithm-$actual")
            return integrity
        }

        val shasum = distribution.path("shasum").asString("").trim().lowercase()
        if (shasum.isEmpty()) throw LibraryRegistrySilentException(named, "it did not say what the file hashes to")
        val actual = MessageDigest.getInstance("SHA-1").digest(archive).joinToString("") { "%02x".format(it) }
        if (actual != shasum) throw LibraryIntegrityException(named, "sha1-$shasum", "sha1-$actual")
        return "sha1-$shasum"
    }

    private fun json(url: String, named: String, accept: String = "application/json"): JsonNode {
        val answered = get(url, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), named, accept)
        if (answered.statusCode() == 404) throw LibraryPackageMissingException(named)
        if (answered.statusCode() !in 200..299) {
            throw LibraryRegistrySilentException(named, "the registry answered ${answered.statusCode()}")
        }
        return try {
            mapper.readTree(answered.body())
        } catch (failure: Exception) {
            throw LibraryRegistrySilentException(named, failure.message ?: "the registry answered nothing readable")
        }
    }

    private fun bytes(url: String, named: String): ByteArray {
        val answered = get(url, HttpResponse.BodyHandlers.ofByteArray(), named)
        if (answered.statusCode() !in 200..299) {
            throw LibraryRegistrySilentException(named, "its file answered ${answered.statusCode()}")
        }
        val body = answered.body()
        val mostMb = tuning[Tunable.NPM_ARCHIVE_MB]
        if (body.size > mostMb * 1024L * 1024L) {
            throw LibraryRegistrySilentException(named, "its file is larger than $mostMb MB")
        }
        return body
    }

    private fun <T> get(
        url: String,
        handler: HttpResponse.BodyHandler<T>,
        named: String,
        accept: String = "application/json",
    ): HttpResponse<T> {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(properties.timeout)
            .header("Accept", accept)
            .header("User-Agent", "orknux")
            .GET()
            .build()
        return try {
            client.send(request, handler)
        } catch (failure: IOException) {
            throw LibraryRegistryUnreachableException(named, failure.message ?: "it could not be reached")
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw LibraryRegistryUnreachableException(named, "the fetch was interrupted")
        }
    }

    /** One named file out of a gzipped tar, as text, or null. */
    internal fun entry(archive: ByteArray, path: String): String? = read(archive, 1) { it == path }[path]

    /**
     * Every file in a gzipped tar that is asked for, as text.
     *
     * A reader rather than a library, because this needs no more of tar than
     * reading files out of one. It knows regular files, the PAX and GNU records
     * that carry a name too long for a header, and how to skip everything else;
     * what it will not do is read past [MAX_ENTRY], past [upTo] files or past the
     * archive, so an archive that claims to be enormous costs a refusal rather
     * than the heap.
     *
     * One walk whether one file is wanted or four hundred, since #319 made the
     * second a thing that happens: a bundle is every file a package's entry
     * reaches, and opening the archive again per file would be a gzip stream per
     * module.
     */
    internal fun read(archive: ByteArray, upTo: Int, wanted: (String) -> Boolean): Map<String, String> {
        val found = LinkedHashMap<String, String>()
        GZIPInputStream(ByteArrayInputStream(archive)).use { stream ->
            var override: String? = null
            while (true) {
                val header = block(stream) ?: return found
                if (header.all { it == 0.toByte() }) return found

                val size = octal(header, 124, 12)
                val padded = ((size + BLOCK - 1) / BLOCK) * BLOCK
                val kind = header[156].toInt().toChar()
                val name = override ?: string(header, 0, 100)
                override = null

                when (kind) {
                    // A PAX or GNU record whose payload is the next file's name.
                    'x', 'X', 'L' -> {
                        val held = String(read(stream, size.toInt()), StandardCharsets.UTF_8)
                        override = if (kind == 'L') held.trimEnd('\u0000') else pax(held)
                        skip(stream, padded - size)
                    }

                    // A regular file: "0" in ustar, and NUL in the older
                    // spelling, which is still what some writers produce.
                    '0', '\u0000' -> {
                        if (size <= MAX_ENTRY && wanted(name)) {
                            found[name] = String(read(stream, size.toInt()), StandardCharsets.UTF_8)
                            skip(stream, padded - size)
                            if (found.size >= upTo) return found
                        } else {
                            skip(stream, padded)
                        }
                    }

                    else -> skip(stream, padded)
                }
            }
        }
    }

    /** The `path=` record out of a PAX extended header. */
    private fun pax(held: String): String? = held.lineSequence()
        .mapNotNull { line -> line.substringAfter("path=", "").ifEmpty { null } }
        .firstOrNull()

    private fun block(stream: InputStream): ByteArray? = try {
        read(stream, BLOCK)
    } catch (_: EOFException) {
        null
    }

    private fun read(stream: InputStream, count: Int): ByteArray {
        val held = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val took = stream.read(held, filled, count - filled)
            if (took < 0) throw EOFException()
            filled += took
        }
        return held
    }

    private fun skip(stream: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val stepped = stream.skip(left)
            if (stepped <= 0) {
                if (stream.read() < 0) return
                left--
            } else {
                left -= stepped
            }
        }
    }

    private fun string(header: ByteArray, at: Int, length: Int): String {
        val end = (at until at + length).firstOrNull { header[it] == 0.toByte() } ?: (at + length)
        return String(header, at, end - at, StandardCharsets.UTF_8)
    }

    private fun octal(header: ByteArray, at: Int, length: Int): Long =
        string(header, at, length).trim().ifEmpty { "0" }.toLongOrNull(8) ?: 0

    /** A scoped name's slash is escaped; the registry takes either spelling. */
    private fun encoded(name: String): String = name.replace("/", "%2f")

    private companion object {
        const val PACKAGE_JSON = "package/package.json"

        const val BLOCK = 512

        // A published package, compressed, is bounded by Tunable.NPM_ARCHIVE_MB. #453.

        /** One file out of it. The upload's own limit refuses it again after this. */
        const val MAX_ENTRY = 8L * 1024 * 1024

        /** How deep an `exports` tree is followed before it is somebody's mistake. */
        const val MAX_CONDITIONS = 8

        /**
         * How many packages one bundle may draw in, and how many files.
         *
         * Not a guess at what is reasonable so much as a floor under what is
         * absurd. A library is one stored row and there is already a limit on
         * how large that may be, so what these stop is the *fetching* — a
         * dependency graph that would have this server pulling half a registry
         * before finding out the result was too large to keep.
         *
         * It was 25 until `qs` was tried against the real registry and came to
         * 22 — a small, extremely ordinary package four levels deep in
         * single-function dependencies, which is how the ecosystem is now built.
         * A ceiling a package like that nearly touches is one that would refuse
         * on the day rather than on the absurdity, and the size limit on the row
         * is the honest guard. The package count is Tunable.NPM_PACKAGES_MOST
         * since #453.
         */

        const val MAX_FILES = 400

        /** What could be a module. A README could not, and a package is full of them. */
        val MODULE_FILES = listOf(".js", ".cjs", ".mjs", ".json")

        /**
         * What a file replaced by a package's `browser: false` becomes.
         *
         * The comment is not decoration. A bundle names the archives it came out
         * of, and one file in it is not the file that was published - so it says
         * so, where somebody reading the bundle would be looking.
         */
        val EMPTY_MODULE = """
            // The package's own browser map says this file is not available outside Node.
            module.exports = {};
        """.trimIndent()

        /** npm's own abbreviated packument: the version numbers without the prose. */
        const val ABBREVIATED = "application/vnd.npm.install-v1+json"

        /**
         * The `exports` conditions that name an ES module, best first.
         *
         * `require` is missing here and has [REQUIRED] to itself, because the two
         * are asked in two passes: everything a package calls a module is a
         * candidate before anything it calls CommonJS. `node` and `browser` are
         * here because a package that ships both usually names the module under
         * one of them rather than at the top.
         */
        val PREFERRED = listOf("module", "import", "browser", "node", "default")

        /**
         * The CommonJS branch, asked only once the ES ones have been.
         *
         * `node` and `default` are repeated because `require` is often nested
         * under one of them, and a condition already visited costs a set
         * membership.
         */
        val REQUIRED = listOf("require", "node", "default")

        /** npm's own rule for a name, scope included, and its length limit. */
        val PACKAGE = Regex("(?:@[a-z0-9][a-z0-9._-]{0,100}/)?[a-z0-9][a-z0-9._-]{0,100}")

        /** Exactly one version. No range, no tag, no `latest`. */
        val VERSION = Regex("\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?")
    }
}

/**
 * One module, fetched from a registry, with everything the row records about it.
 *
 * [integrity] is the registry's own claim about the archive, verified against
 * what arrived. [entry] is the path inside the package, because "which of the
 * eleven files in this tarball is the thing running" is otherwise unanswerable.
 */
data class Fetched(
    val packageName: String,
    val version: String,
    val url: String,
    val integrity: String,
    val entry: String,
    val source: String,
)

/**
 * A package and its dependencies, fetched, as the files a bundle is made of.
 *
 * The paths are the ones the bundle will be keyed by: a package's own files at
 * the paths it published them at, and everything it needs under
 * `node_modules/<name>/`, which is where a `require` for a bare name would look
 * for it. [packages] is the part [LibraryBundle] cannot work out for itself —
 * *which* file `require("ms")` means is a question about `ms`'s manifest, and
 * reading manifests is this file's job.
 *
 * [parts] is the bill of materials, and it is the reason this is worth doing at
 * all rather than telling somebody to go and run a bundler: every package that
 * went in is named, at the version it resolved to, with the hash its registry
 * claimed for it. Issue #319.
 */
data class Gathered(
    val entry: String,
    val modules: Map<String, String>,
    val packages: Map<String, String>,
    val parts: List<BundlePart>,
) {
    /** The one somebody asked for. The rest arrived because it needed them. */
    val root: BundlePart get() = parts.first()
}

/** One package inside a bundle, at the version it resolved to. */
data class BundlePart(
    val packageName: String,
    val version: String,
    val url: String,
    val integrity: String,
    /** Which file in it the bundle enters it by. */
    val entry: String,
)

/**
 * Two packages need the same third one, and not the same version of it.
 *
 * Node installs a copy of each, nested where they are needed. One file cannot
 * hold two, and picking one of the two versions for both callers is picking
 * somebody's bug on their behalf — so this is said out loud, with both versions,
 * because what to do about it is a decision for whoever is installing.
 */
class LibraryBundleConflictException(spec: String, dependency: String, held: String, wanted: String) :
    RuntimeException(
        "$spec cannot be bundled: it needs $dependency at $held and at \"$wanted\" at the same time, and one " +
            "file can only hold one of them.",
    )

/** Nothing published satisfies a range something in the graph asked for. */
class LibraryBundleUnsatisfiedException(spec: String, owner: String, dependency: String, range: String) :
    RuntimeException(
        "$spec cannot be bundled: $owner needs $dependency \"$range\", and the registry has published no " +
            "version that satisfies it.",
    )

/**
 * The graph is larger than this will fetch.
 *
 * A number rather than a size, because what is being stopped is the fetching:
 * finding out at the end that the result is too large to store would mean having
 * pulled all of it first.
 */
class LibraryBundleTooManyException(spec: String, most: Int) : RuntimeException(
    "$spec draws in more than $most packages, which is more than this will fetch to make one library. " +
        "Build the bundle elsewhere and upload the one file.",
)
