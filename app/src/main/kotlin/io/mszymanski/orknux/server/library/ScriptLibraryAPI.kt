package io.mszymanski.orknux.server.library

import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.tuning.Tunable
import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.dependency.ComponentDependants
import io.mszymanski.orknux.server.dependency.DependantView
import io.mszymanski.orknux.server.dependency.DependencyAPI
import io.mszymanski.orknux.server.dependency.DependencyKind
import io.mszymanski.orknux.server.dependency.phrases
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.workflow.script.EsModules
import io.mszymanski.orknux.workflow.script.LibraryInspection
import io.mszymanski.orknux.workflow.script.Rewritten
import io.mszymanski.orknux.workflow.script.ScriptRunner
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import tools.jackson.databind.ObjectMapper
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.OffsetDateTime

/**
 * Loading a library into the installation, and taking it out again.
 *
 * Administrators only. A library is installation-wide, and the reason it is —
 * rather than a thing each workspace uploads for itself — is on [ScriptLibrary].
 *
 * The upload is REST for the same reason a plugin's is: what crosses is a file,
 * and a multipart form is what a browser makes of a file picker. Everything else
 * is GraphQL.
 *
 * The file is evaluated in the sandbox it will run in before anything is stored,
 * and refused if it cannot be. That is not a security check — a library gets no
 * more than a function gets, which is nothing — it is so that "this file is not a
 * module with a default export" is answered while somebody is looking at it.
 */
@RestController
class ScriptLibraryUploadAPI(
    private val libraries: ScriptLibraryRepository,
    private val access: WorkspaceAccess,
    private val store: LibraryStore,
    /** Turns an ES module into the spelling a bundle can hold; see [EsModules]. */
    private val modules: EsModules,
) {
    /**
     * One ES module as CommonJS, or null where the compiler could not read it.
     *
     * The shape [LibraryBundle] wants: it does not know what a compiler is and
     * should not, so it asks for a file and gets one back or nothing. What is
     * lost by answering null is only the reason, and the reason is logged where
     * it was found - by Babel, which is the only thing here that knows what is
     * wrong with somebody's syntax.
     */
    private fun asCommonJs(source: String, path: String): String? =
        when (val held = modules.toCommonJs(source, path)) {
            is Rewritten.Done -> held.source
            is Rewritten.Refused -> null
        }


    @PostMapping("/api/libraries")
    @Transactional
    fun upload(
        @RequestParam("file") file: MultipartFile,
        /**
         * What it was written in, when that was TypeScript.
         *
         * Sent by the interface, which compiles before uploading: the sandbox runs
         * JavaScript and this server has no compiler. Kept only so the library can
         * be downloaded as what somebody wrote, and never evaluated.
         */
        @RequestParam("typescript", required = false) typescript: String?,
    ): ResponseEntity<Any> {
        access.requireAdmin()

        if (file.isEmpty) throw LibraryEmptyException()

        val filename = file.originalFilename?.trim()?.ifEmpty { null } ?: "library.js"
        if (!filename.endsWith(".js") && !filename.endsWith(".mjs")) throw LibraryNotJavaScriptException(filename)

        val source = text(file.bytes)
        val key = filename.removeSuffix(".mjs").removeSuffix(".js")

        val replaced = libraries.findByKey(key) != null
        val saved = store.store(
            key = key,
            filename = filename,
            source = source,
            typescript = typescript?.ifBlank { null },
            sizeBytes = file.size,
        )
        return ResponseEntity.ok(mapOf("id" to requireNotNull(saved.id), "key" to saved.key, "replaced" to replaced))
    }

    /**
     * Several files, made into one library.
     *
     * The other half of #319. A registry install has a manifest to read and this
     * has nothing: which of the files is the entry is a question only the person
     * choosing them can answer, so it is asked rather than guessed at — a folder
     * of six files has no `index.js` often enough that guessing would be wrong on
     * the day it mattered.
     *
     * [paths] is what keeps `require("./lib/parse")` working. A browser sends a
     * file's basename and nothing else, so a form that sent only files would turn
     * `lib/parse.js` into `parse.js` and the specifier would answer nothing; the
     * interface sends each file's path beside it, in the same order.
     *
     * The permission is asked here too, and for the same reason as on an install:
     * what comes out is an artefact this server assembled, which is a different
     * thing from the file somebody chose. Refused rather than offered, though —
     * unlike an install there is nothing to fetch and nothing to describe that
     * the person did not just select themselves, so the answer is to say so and
     * suggest the one file.
     */
    @PostMapping("/api/libraries/bundle")
    @Transactional
    fun bundle(
        @RequestParam("files") files: List<MultipartFile>,
        @RequestParam("paths", required = false) paths: List<String>?,
        @RequestParam("entry") entry: String,
        @RequestParam("key", required = false) key: String?,
        @RequestParam("bundle", required = false) allowed: Boolean?,
    ): ResponseEntity<Any> {
        access.requireAdmin()

        if (files.isEmpty() || files.all { it.isEmpty }) throw LibraryEmptyException()
        val named = key?.trim()?.ifEmpty { null } ?: entry.substringAfterLast('/').removeSuffix(".js")
        if (files.size > 1 && allowed != true) throw LibraryBundleNotAllowedException(named, files.size)

        val modules = LinkedHashMap<String, String>()
        files.forEachIndexed { at, file ->
            val path = paths?.getOrNull(at)?.trim()?.ifEmpty { null }
                ?: file.originalFilename?.trim()?.ifEmpty { null }
                ?: throw LibraryEmptyException()
            modules[path.removePrefix("./").removePrefix("/")] = text(file.bytes)
        }

        val chosen = entry.trim().removePrefix("./").removePrefix("/")
        if (!modules.containsKey(chosen)) throw LibraryBundleMissingException(named, "the form", chosen)

        val made = LibraryBundle.bundle(modules, chosen, named, rewrite = ::asCommonJs)
        val stored = store.store(
            key = named,
            filename = chosen,
            source = made.source,
            sizeBytes = made.source.toByteArray(StandardCharsets.UTF_8).size.toLong(),
            // No version and no hash: an upload has no provenance this
            // installation can vouch for, and what went in is all it can say.
            parts = made.files.map { LibraryPartView(it, version = null, entry = null, integrity = null) },
        )
        return ResponseEntity.ok(
            mapOf("id" to requireNotNull(stored.id), "key" to stored.key, "files" to made.files.size),
        )
    }

    /** The library as it was written, for downloading. TypeScript where there is any. */
    @GetMapping("/api/libraries/{id}/source")
    fun download(@PathVariable id: Long): ResponseEntity<String> {
        access.requireAdmin()
        val library = libraries.findByIdOrNull(id) ?: throw LibraryNotFoundException(id)

        val written = library.typescript
        val extension = if (written == null) "js" else "ts"
        return ResponseEntity.ok()
            .contentType(MediaType.valueOf("text/plain"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${library.key}.$extension\"")
            .body(written ?: library.source)
    }

    @ExceptionHandler(
        LibraryEmptyException::class,
        LibraryTooLargeException::class,
        LibraryNotJavaScriptException::class,
        LibraryNotTextException::class,
        LibraryKeyInvalidException::class,
        LibraryUnreadableException::class,
        LibraryBundleEsmException::class,
        LibraryBundleMissingException::class,
        LibraryBundleBuiltinException::class,
        LibraryBundleNotAllowedException::class,
    )
    fun refused(failure: RuntimeException): ResponseEntity<Map<String, String>> =
        ResponseEntity.badRequest().body(mapOf("message" to (failure.message ?: "That file could not be loaded")))

    /** The bytes as the text they are supposed to be, strictly, or a refusal. */
    private fun text(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (failure: CharacterCodingException) {
        throw LibraryNotTextException()
    }

}

/**
 * The one place a library becomes a row.
 *
 * Two doors reach it — a file somebody uploaded and a package somebody named —
 * and they must not disagree about what a stored library is. What this decides is
 * everything that is true of a library however it arrived: it is evaluated in the
 * sandbox it will run in before anything is stored, what its export turned out to
 * hold is read off the value, and a key that is already loaded is replaced in
 * place rather than duplicated, so nothing importing it is repointed.
 *
 * The provenance is the only thing that differs, and it is passed in rather than
 * worked out here: [Fetched] where a registry served the file, null where somebody
 * chose it, and null is what makes the row say `UPLOAD`.
 *
 * The size limit and the shape of a key live here for the same reason. They are
 * properties of a library rather than of an upload, and a copy in each door is two
 * copies to keep level — the door that fetches a package would otherwise be free
 * to store one twice the size of anything anybody could upload.
 */
@Component
class LibraryStore(
    private val libraries: ScriptLibraryRepository,
    private val scripts: ScriptRunner,
    private val mapper: ObjectMapper,
    /** The numbers this reads from Admin -> Settings. Issue #453. */
    private val tuning: Tuning = Tuning.DEFAULTS,
) {

    @Transactional
    fun store(
        key: String,
        filename: String,
        source: String,
        typescript: String? = null,
        sizeBytes: Long,
        fetched: Fetched? = null,
        /**
         * What went into this, where it was made out of more than one file.
         *
         * Null is the ordinary library and is not the same as an empty list. What
         * it changes here is the self-containment check below: a bundle's
         * `require` calls are calls into itself, answered by a table the bundler
         * built, and refusing them would refuse the thing this feature exists to
         * produce. Every one of them was resolved before this was called, which
         * is a stronger check than the one being skipped rather than a weaker
         * one. Issue #319.
         */
        parts: List<LibraryPartView>? = null,
    ): ScriptLibrary {
        if (!KEY.matches(key)) throw LibraryKeyInvalidException(key)
        val mostMb = tuning[Tunable.LIBRARY_MB]
        if (sizeBytes > mostMb * 1024L * 1024L) throw LibraryTooLargeException(mostMb * 1024L)

        // Which of the two spellings this is, and whether it is on its own. Asked
        // here rather than in the door that fetched it, so that a CommonJS file
        // somebody uploads is held to exactly the rule a package is: a `require`
        // in a file that is going to be *run* as CommonJS is a call into a second
        // package, and one stored without this would install and fail at its
        // first use. An ES module is never asked, because a bundle mentions
        // `require` inside a shim it never reaches - see LibrarySource.required.
        val format = LibrarySource.formatOf(source)
        if (format == LibrarySource.COMMONJS && parts == null) {
            LibrarySource.required(source)?.let {
                throw LibraryUnreadableException(
                    "it calls require(\"$it\"), and a library has to be one self-contained file. " +
                        "This installation does not bundle.",
                )
            }
        }

        val read = when (val answered = scripts.library(LibrarySource.runnable(source, format))) {
            is LibraryInspection.Read -> answered
            is LibraryInspection.Unreadable -> throw LibraryUnreadableException(answered.reason)
        }

        val members = mapper.writeValueAsString(read.members.map { mapOf("name" to it.name, "callable" to it.callable) })
        val library = libraries.findByKey(key) ?: ScriptLibrary(
            key = key,
            name = key,
            filename = filename.takeLast(MAX_NAME),
            source = source,
            sizeBytes = sizeBytes,
            sha256 = digest(source),
        )

        return libraries.save(
            library.apply {
                this.name = key
                this.filename = filename.takeLast(MAX_NAME)
                this.source = source
                this.typescript = typescript
                this.sizeBytes = sizeBytes
                this.sha256 = digest(source)
                this.declaredMembers = members
                this.callable = read.callable
                this.sourceFormat = format
                this.uploadedAt = OffsetDateTime.now()
                this.uploadedBy = currentUser()
                // Rewritten in full both ways round. A registry install over an
                // upload has a provenance to record, and an upload over a
                // registry install has none - and a row still claiming a package
                // whose file was replaced by hand is worse than one claiming
                // nothing, because it would be believed.
                this.origin = if (fetched == null) ScriptLibrary.ORIGIN_UPLOAD else ScriptLibrary.ORIGIN_REGISTRY
                this.originPackage = fetched?.packageName
                this.originVersion = fetched?.version
                this.originUrl = fetched?.url
                this.originIntegrity = fetched?.integrity
                this.originEntry = fetched?.entry
                // Rewritten both ways round for the reason above it: a re-upload
                // over a bundle is not a bundle any more, and a row still listing
                // packages that are no longer in it would be believed.
                this.bundledFrom = parts?.let { mapper.writeValueAsString(it) }
            },
        )
    }

    private fun digest(source: String): String = MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun currentUser(): String = SecurityContextHolder.getContext().authentication?.name ?: "system"

    private companion object {
        const val MAX_NAME = 200

        /** A bundle, and bundles are large. Small enough that the row stays a row. */
        const val MAX_SIZE = 4L * 1024 * 1024

        /**
         * What a library may be called: what a package is usually called.
         *
         * Dots and dashes allowed, unlike a plugin's key, because this is never
         * written into anybody's code — a workspace importing a library gives it a
         * local name of its own. `date-fns` should be loadable as `date-fns`.
         */
        val KEY = Regex("[A-Za-z][A-Za-z0-9._-]{0,63}")
    }
}

/** Listing what is loaded, seeing what depends on it, and taking one out. */
@Controller
class ScriptLibraryAPI(
    private val libraries: ScriptLibraryRepository,
    private val dependants: ComponentDependants,
    private val dependencies: DependencyAPI,
    private val access: WorkspaceAccess,
    private val mapper: ObjectMapper,
    private val registry: NpmRegistry,
    private val store: LibraryStore,
    private val modules: EsModules,
) {
    /**
     * One ES module as CommonJS, or null where the compiler could not read it.
     *
     * The shape [LibraryBundle] wants: it does not know what a compiler is and
     * should not, so it asks for a file and gets one back or nothing. What is
     * lost by answering null is only the reason, and the reason is logged where
     * it was found - by Babel, which is the only thing here that knows what is
     * wrong with somebody's syntax.
     */
    private fun asCommonJs(source: String, path: String): String? =
        when (val held = modules.toCommonJs(source, path)) {
            is Rewritten.Done -> held.source
            is Rewritten.Refused -> null
        }


    /**
     * Whether a package can be named here, and where one would come from.
     *
     * Asked by the screen so that an installation with no registry shows the
     * upload alone. A field offering to fetch from a registry that is not
     * configured is a field that fails on being used, which is the one thing an
     * offline installation should never be shown.
     */
    @QueryMapping
    fun libraryRegistry(): LibraryRegistryStatus {
        access.requireAdmin()
        val url = registry.registry
        return LibraryRegistryStatus(configured = url != null, url = url.orEmpty())
    }

    /**
     * Fetches one package and loads what is inside it as a library.
     *
     * **Once, here, into the database.** The file is what is stored and what
     * runs, exactly as an uploaded one is; the registry is consulted at this
     * moment and never again, and nothing in the sandbox gains a network. See
     * [NpmRegistry] for the three rules that make that honest — a pinned
     * version, a verified hash, and the proxy rules — and [LibraryDependsException]
     * for what happens to a package that is not one file.
     *
     * `spec` is `random@4.1.0`. The key is the package's name with the scope
     * folded into it, so `@scope/thing` loads as `scope-thing`, and loading a key
     * that is already there replaces it in place the way a re-upload does.
     */
    @MutationMapping
    @Transactional
    fun installScriptLibrary(@Argument spec: String, @Argument bundle: Boolean?): ScriptLibraryInstall {
        access.requireAdmin()

        /*
         * The one-file install first, unchanged, and a package that is one file
         * never reaches anything below. What tells the two apart is the refusal
         * that used to be the end of it: `LibraryDependsException` is thrown for
         * an entry that reaches a second file, which is precisely the case
         * bundling exists for. Issue #319.
         */
        val fetched = try {
            registry.fetch(spec)
        } catch (needsMore: LibraryDependsException) {
            return bundled(spec, bundle == true, needsMore)
        }

        val stored = store.store(
            // The key and the size are the store's rules, not this one's: a
            // library is a library however it arrived here.
            key = keyOf(fetched.packageName),
            filename = "${fetched.packageName}@${fetched.version}/${fetched.entry}",
            source = fetched.source,
            sizeBytes = fetched.source.toByteArray(StandardCharsets.UTF_8).size.toLong(),
            fetched = fetched,
        )
        return ScriptLibraryInstall(installed = describe(stored, usersOf(requireNotNull(stored.id))))
    }

    /**
     * A package that is more than one file: what would be bundled, or the bundle.
     *
     * **Asked rather than taken**, which is what [allowed] is. The permission is
     * not about safety — the archives have already been fetched and verified, and
     * nothing runs until it is stored. It is about what the row is going to *be*:
     * a bundle is an artefact this server assembled, which no registry published
     * and nobody else can hash to the same thing, and every provenance column
     * beside it describes the package it was entered by rather than the whole of
     * what is in it. That is a different bargain from the one an install usually
     * makes, so it is offered.
     *
     * What comes back when it has not been allowed is the offer itself and not an
     * error: the packages that would go in, at the versions their ranges resolved
     * to. A refusal is what an error is for, and this is a question.
     */
    private fun bundled(spec: String, allowed: Boolean, why: LibraryDependsException): ScriptLibraryInstall {
        val gathered = registry.gather(spec)
        val made = LibraryBundle.bundle(gathered.modules, gathered.entry, spec, gathered.packages, ::asCommonJs)
        val parts = gathered.parts.map {
            LibraryPartView(it.packageName, it.version, it.entry, it.integrity)
        }

        if (!allowed) {
            return ScriptLibraryInstall(
                proposed = LibraryBundlePlan(
                    // The one somebody typed, so the question names what they asked
                    // for rather than only what it dragged in behind it.
                    spec = spec,
                    why = why.message.orEmpty(),
                    files = made.files.size,
                    parts = parts,
                ),
            )
        }

        val root = gathered.root
        val stored = store.store(
            key = keyOf(root.packageName),
            filename = "${root.packageName}@${root.version}/${root.entry}",
            source = made.source,
            sizeBytes = made.source.toByteArray(StandardCharsets.UTF_8).size.toLong(),
            /*
             * The provenance of the package it was entered by, which is true and
             * is not the whole truth - the bundle is not the file that hash is
             * of. `bundledFrom` is the rest of it, and the two are read together.
             */
            fetched = Fetched(root.packageName, root.version, root.url, root.integrity, root.entry, made.source),
            parts = parts,
        )
        return ScriptLibraryInstall(installed = describe(stored, usersOf(requireNotNull(stored.id))))
    }

    /** Everything loaded, with what imports it. Administrators. */
    @QueryMapping
    fun scriptLibraries(): List<ScriptLibraryView> {
        access.requireAdmin()
        return libraries.findAllByOrderByNameAsc().map { describe(it, usersOf(requireNotNull(it.id))) }
    }

    /**
     * The libraries a workspace may import, for the editor's picker.
     *
     * The same rows, without what depends on them: who else imports a library is an
     * administrator's question, and the people writing functions are not
     * administrators. A picker has to be able to offer `date-fns` without being
     * allowed to see every workspace that uses it — which is the same line the
     * function picker already draws around plugins.
     */
    @QueryMapping
    fun workspaceLibraries(@Argument workspaceId: Long): List<ScriptLibraryView> {
        access.requireVisible(workspaceId)
        return libraries.findAllByOrderByNameAsc().map { describe(it, usedBy = emptyList()) }
    }

    /**
     * Takes a library out of the installation.
     *
     * Refused while anything imports it, and the refusal names them. A library
     * removed out from under a function is a `TypeError` in the middle of a run,
     * which is the worst moment and the worst wording to learn this in.
     */
    @MutationMapping
    @Transactional
    fun deleteScriptLibrary(@Argument id: Long): Boolean {
        access.requireAdmin()
        val library = libraries.findByIdOrNull(id) ?: throw LibraryNotFoundException(id)

        val used = dependants.of(DependencyKind.LIBRARY, id)
        if (used.isNotEmpty()) throw LibraryInUseException(used.phrases())

        libraries.delete(library)
        return true
    }

    private fun describe(library: ScriptLibrary, usedBy: List<DependantView>) = ScriptLibraryView(
        id = requireNotNull(library.id),
        key = library.key,
        name = library.name,
        filename = library.filename,
        // GraphQL has no long, and the rest of this schema reports sizes as floats.
        sizeBytes = library.sizeBytes.toDouble(),
        sha256 = library.sha256,
        callable = library.callable,
        format = library.sourceFormat,
        members = mapper.readTree(library.declaredMembers).values().map { held ->
            LibraryMemberView(
                name = held.path("name").asString(""),
                callable = held.path("callable").asBoolean(false),
            )
        },
        usedBy = usedBy,
        uploadedAt = library.uploadedAt.toString(),
        uploadedBy = library.uploadedBy,
        registry = registryOf(library),
        bundledFrom = library.bundledFrom?.let { written ->
            mapper.readTree(written).values().map { held ->
                LibraryPartView(
                    name = held.path("name").asString(""),
                    version = held.path("version").asString("").ifEmpty { null },
                    entry = held.path("entry").asString("").ifEmpty { null },
                    integrity = held.path("integrity").asString("").ifEmpty { null },
                )
            }
        },
    )

    /**
     * Where it came from, or null for a file somebody chose.
     *
     * Built only where every part of it is there. A half-filled provenance is
     * one nobody can check, and a row that says which package without saying
     * what it hashed to would still be shown as though it had been vouched for.
     */
    private fun registryOf(library: ScriptLibrary): LibraryRegistryView? {
        if (library.origin != ScriptLibrary.ORIGIN_REGISTRY) return null
        return LibraryRegistryView(
            packageName = library.originPackage ?: return null,
            version = library.originVersion ?: return null,
            url = library.originUrl.orEmpty(),
            integrity = library.originIntegrity.orEmpty(),
            entry = library.originEntry.orEmpty(),
        )
    }

    /**
     * A package's name as a library key.
     *
     * `random` is `random`, and a scoped package folds its scope in with a dash:
     * `@scope/thing` is `scope-thing`. A key holds no `@` and no slash, since it
     * is what an administrator matches a re-load against and one nobody can say
     * out loud is no use for that — and it is not what anybody's code types,
     * which is a local name of the importer's own.
     */
    private fun keyOf(packageName: String): String =
        packageName.removePrefix("@").replace('/', '-').takeLast(64)

    /**
     * Every function and tool that imports this library, with the workspace it is in.
     *
     * Through [DependencyAPI.visible] rather than raw, so that the one rule about
     * naming something in a workspace the reader cannot open lives in one place.
     * This listing is an administrator's and an administrator sees every
     * workspace, so nothing is ever dropped here — but the rule is not this
     * screen's to restate.
     */
    private fun usersOf(id: Long): List<DependantView> =
        dependencies.visible(dependants.of(DependencyKind.LIBRARY, id)).entries
}

@Component
class ScriptLibraryExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            is LibraryInUseException,
            is LibraryUnreadableException,
            is LibraryKeyInvalidException,
            is LibraryTooLargeException,
            // Every way an install can be refused. Each of them carries a sentence
            // meant for whoever typed the package name, so all of them are
            // BAD_REQUEST and none is turned into a shorter one on the way out -
            // "it could not be fetched" would leave somebody guessing between a
            // typo, a proxy and a package that ships no module.
            is LibraryRegistryOffException,
            is LibraryPackageInvalidException,
            is LibraryRegistryUnreachableException,
            is LibraryRegistrySilentException,
            is LibraryIntegrityException,
            is LibraryNoEntryException,
            is LibraryDependsException,
            // Every way a bundle can be refused, and each one carries a sentence
            // naming the file or the package it is about - which is the whole of
            // what somebody can act on.
            is LibraryBundleEsmException,
            is LibraryBundleMissingException,
            is LibraryBundleBuiltinException,
            is LibraryBundleNotAllowedException,
            is LibraryBundleConflictException,
            is LibraryBundleUnsatisfiedException,
            is LibraryBundleTooManyException,
            is LibraryRangeUnreadableException,
            -> ErrorType.BAD_REQUEST

            is LibraryNotFoundException,
            is LibraryPackageMissingException,
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
