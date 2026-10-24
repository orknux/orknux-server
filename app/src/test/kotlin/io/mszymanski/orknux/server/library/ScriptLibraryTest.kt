package io.mszymanski.orknux.server.library

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.chat.AgentTools
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.WithMockUser

/**
 * Libraries: JavaScript an installation loads once and its scripts import.
 *
 * The upload is driven through the controller rather than the repository, because
 * what it does before storing anything is the interesting half: a library is
 * evaluated in the sandbox it will run in, and what its export turned out to hold
 * is read off the value. A test that wrote the member list itself would still pass
 * on the day the reading stopped working.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ScriptLibraryTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val upload: ScriptLibraryUploadAPI,
    @Autowired val libraries: ScriptLibraryRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val tools: AgentToolRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val agentTools: AgentTools,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        agents.deleteAll()
        tools.deleteAll()
        // Not the release's own, which every workspace's Research action calls (#659) and start-up writes back anyway.
        functions.deleteAll(functions.findAll().filter { it.scope != io.mszymanski.orknux.server.action.FunctionScope.EMBEDDED })
        libraries.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    /**
     * Several files chosen at once, made into one library. Issue #319.
     *
     * The paths are the point. A browser sends a file's basename and nothing
     * else, so a form that sent only files would turn `lib/parse.js` into
     * `parse.js` and `require("./lib/parse")` would answer nothing - the
     * interface sends each file's path beside it, and this is what says so.
     */
    @Test
    fun `several files chosen at once become one library`() {
        val answered = upload.bundle(
            files = listOf(
                MockMultipartFile("files", "index.js", "text/plain", ENTRY.toByteArray()),
                MockMultipartFile("files", "parse.js", "text/plain", PARSE.toByteArray()),
            ),
            paths = listOf("index.js", "lib/parse.js"),
            entry = "index.js",
            key = "sums",
            allowed = true,
        )

        assertThat(answered.statusCode.value()).isEqualTo(200)
        val stored = requireNotNull(libraries.findByKey("sums"))
        assertThat(stored.sourceFormat).isEqualTo(LibrarySource.COMMONJS)
        // Read off the evaluated value in the sandbox, so a bundle that did not
        // run would fail here rather than be stored.
        assertThat(stored.declaredMembers).contains("total")
        // What went in, so a bundle is not a black box in the one table whose
        // job is answering what code is running here.
        assertThat(stored.bundledFrom).contains("index.js").contains("lib/parse.js")
    }

    /**
     * Bundling is offered rather than taken, even here.
     *
     * What comes out is an artefact this server assembled, which is a different
     * thing from the file somebody chose - so more than one file without
     * permission is refused, and the refusal says what to do instead.
     */
    @Test
    fun `more than one file without permission is refused`() {
        assertThatThrownBy {
            upload.bundle(
                files = listOf(
                    MockMultipartFile("files", "index.js", "text/plain", ENTRY.toByteArray()),
                    MockMultipartFile("files", "parse.js", "text/plain", PARSE.toByteArray()),
                ),
                paths = listOf("index.js", "lib/parse.js"),
                entry = "index.js",
                key = "sums",
                allowed = null,
            )
        }
            .isInstanceOf(LibraryBundleNotAllowedException::class.java)
            .hasMessageContaining("on its own")

        assertThat(libraries.findByKey("sums")).isNull()
    }

    @Test
    fun `a loaded library is read for what it exports, and listed with it`() {
        load("slugs", "export default { tag: 'slugs', of: (t) => t.toLowerCase().split(' ').join('-') };")

        graphQlTester.document(
            """query { scriptLibraries { key callable members { name callable } usedBy { name } uploadedBy } }""",
        ).execute()
            .path("scriptLibraries[0].key").entity(String::class.java).isEqualTo("slugs")
            .path("scriptLibraries[0].callable").entity(Boolean::class.java).isEqualTo(false)
            .path("scriptLibraries[0].uploadedBy").entity(String::class.java).isEqualTo("alice")
            .path("scriptLibraries[0].members[*].name").entityList(String::class.java).containsExactly("of", "tag")
            .path("scriptLibraries[0].members[0].callable").entity(Boolean::class.java).isEqualTo(true)
            .path("scriptLibraries[0].members[1].callable").entity(Boolean::class.java).isEqualTo(false)
            .path("scriptLibraries[0].usedBy").entityList(Object::class.java).hasSize(0)
    }

    /** A bundle that exports one function rather than an object is the other spelling. */
    @Test
    fun `a library whose export is itself a function says so`() {
        load("shout", "export default function shout(t) { return t.toUpperCase(); }")

        graphQlTester.document("""query { scriptLibraries { callable members { name } } }""").execute()
            .path("scriptLibraries[0].callable").entity(Boolean::class.java).isEqualTo(true)
    }

    /**
     * A CommonJS library, end to end, because the wrapper has two ends. #274.
     *
     * It is put round the text once when the library is loaded — which is where
     * the member list comes from — and again on every run that imports it. A test
     * that only loaded one would pass on the day the run-time half stopped being
     * applied, and the failure would be a `TypeError` in the middle of somebody's
     * workflow rather than a sentence on the screen they chose the file on.
     *
     * What the row holds is the file as it was written, so the two hashes beside
     * it stay claims about that file and not about something this server made up.
     */
    @Test
    fun `a CommonJS library is wrapped on the way into the sandbox, and a function calling it runs`() {
        val library = load(
            "cased",
            """
            'use strict'
            exports.upper = function (t) { return String(t).toUpperCase(); }
            exports.tag = 'cased'
            """.trimIndent(),
        )

        val stored = requireNotNull(libraries.findByKey("cased"))
        assertThat(stored.sourceFormat).isEqualTo(LibrarySource.COMMONJS)
        assertThat(stored.source).doesNotContain("export default")

        graphQlTester.document("""query { scriptLibraries { format members { name callable } } }""").execute()
            .path("scriptLibraries[0].format").entity(String::class.java).isEqualTo("COMMONJS")
            .path("scriptLibraries[0].members[*].name").entityList(String::class.java)
            .containsExactly("tag", "upper")

        val shout = graphQlTester.document(
            """
            mutation {
              createFunction(input: {
                workspaceId: $workspaceId, name: "shout",
                source: "export default function shout(t) { return imports.cased.upper(t); }",
                typescript: "export default function shout(t) { return imports.cased.upper(t); }",
                params: [{ name: "t", type: STRING }], returnType: STRING,
                libraries: [{ libraryId: $library, name: "cased" }]
              }) { id }
            }
            """,
        ).execute().path("createFunction.id").entity(Long::class.java).get()

        graphQlTester.document(
            """
            mutation {
              runFunction(input: {
                workspaceId: $workspaceId, functionId: $shout,
                arguments: [{ name: "t", json: "\"hello\"" }]
              }) { ok returned error }
            }
            """,
        ).execute()
            .path("runFunction.error").valueIsNull()
            .path("runFunction.ok").entity(Boolean::class.java).isEqualTo(true)
            .path("runFunction.returned").entity(String::class.java).isEqualTo("\"HELLO\"")
    }

    /**
     * UMD, which is what a great many small packages are actually published as.
     *
     * Under the wrapper `module` and `exports` are defined and `define` is not,
     * so the file takes its CommonJS branch. The AMD branch is left dead on
     * purpose: defining `define` would mean standing up a loader this sandbox
     * does not have, and a package that only ever registers through AMD leaves
     * nothing on `module.exports` and is refused for having no default export —
     * which is a sentence, at the moment somebody chose the file.
     *
     * `this` is asserted through, and it is the reason the wrapper uses `.call`.
     * A UMD head passes `this` as its global object, and at the top of an ES
     * module `this` is `undefined`; leaving it so would break the branch that
     * reads `root` for no reason at all.
     */
    @Test
    fun `a UMD bundle takes its CommonJS branch and is read for what it exports`() {
        load(
            "cased",
            """
            (function (root, factory) {
              if (typeof define === 'function' && define.amd) { define([], factory); }
              else if (typeof module === 'object' && module.exports) { module.exports = factory(); }
              else { root.cased = factory(); }
            }(this, function () {
              return { upper: function (t) { return String(t).toUpperCase(); }, tag: 'cased' };
            }));
            """.trimIndent(),
        )

        graphQlTester.document("""query { scriptLibraries { format callable members { name callable } } }""")
            .execute()
            .path("scriptLibraries[0].format").entity(String::class.java).isEqualTo("COMMONJS")
            .path("scriptLibraries[0].callable").entity(Boolean::class.java).isEqualTo(false)
            .path("scriptLibraries[0].members[*].name").entityList(String::class.java)
            .containsExactly("tag", "upper")
    }

    /**
     * The same rule a package is held to, at the other door.
     *
     * A file being run as CommonJS that calls `require` names a second package.
     * Stored, it would install cleanly and fail at its first call, so it is
     * refused where somebody can still do something about it.
     */
    @Test
    fun `a CommonJS file that requires another package is refused, and the refusal names it`() {
        val failure = runCatching {
            upload.upload(file("needy.js", "var b = require('buffer');\nmodule.exports = { b: b };"), null)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(LibraryUnreadableException::class.java)
        assertThat(failure?.message).contains("require(\"buffer\")")
        assertThat(failure?.message).contains("does not bundle")
        assertThat(libraries.findAll()).isEmpty()
    }

    /** Refused where somebody is looking, rather than found when a workflow needed it. */
    @Test
    fun `a file with nothing to import is refused`() {
        val failure = runCatching {
            upload.upload(file("empty.js", "const x = 1;"), null)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(LibraryUnreadableException::class.java)
        assertThat(failure?.message).contains("no default export")
        assertThat(libraries.findAll()).isEmpty()
    }

    /**
     * A function imports a library and a tool imports the same one, and both run.
     *
     * The end-to-end one, taken through an agent's call so that nothing about the
     * path is stood in for: the tool imports a function that imports the library,
     * which is also the case that proves a module reached through another still
     * gets what it calls.
     */
    @Test
    fun `a tool imports a function that uses a library, and the whole chain runs`() {
        val library = load("slugs", "export default { of: (t) => t.toLowerCase().split(' ').join('-') };")

        val slugify = graphQlTester.document(
            """
            mutation {
              createFunction(input: {
                workspaceId: $workspaceId, name: "slugify",
                source: "export default function slugify(title) { return imports.slugs.of(title); }",
                typescript: "export default function slugify(title) { return imports.slugs.of(title); }",
                params: [{ name: "title", type: STRING }], returnType: STRING,
                libraries: [{ libraryId: $library, name: "slugs" }]
              }) { id libraries { name library { key members { name } } } }
            }
            """,
        ).execute()
            .path("createFunction.libraries[0].library.key").entity(String::class.java).isEqualTo("slugs")
            .path("createFunction.id").entity(Long::class.java).get()

        graphQlTester.document(
            """
            mutation {
              createTool(input: {
                workspaceId: $workspaceId, name: "makeSlug", description: "Slugs a title",
                source: "export default function makeSlug(title) { return { slug: imports.slug(title) }; }",
                typescript: "export default function makeSlug(title) { return { slug: imports.slug(title) }; }",
                params: [{ name: "title", type: STRING }],
                imports: [{ functionId: $slugify, name: "slug" }]
              }) { id }
            }
            """,
        ).execute().path("createTool.id").entity(Long::class.java).get()

        val agentId = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Writer", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        graphQlTester.document(
            """mutation { updateAgent(id: $agentId, input: { name: "Writer", tools: ["makeSlug"] }) { tools } }""",
        ).execute()

        val answer = agentTools.run(
            requireNotNull(agents.findById(agentId).orElse(null)),
            ToolCall(id = "call_1", name = "makeSlug", arguments = """{"title":"Hello There World"}"""),
        )
        assertThat(answer).contains("hello-there-world")
    }

    /**
     * What depends on a library is the question the installation has to answer, and
     * the answer is what stops it being removed.
     */
    @Test
    fun `a library something imports cannot be removed, and the refusal names it`() {
        val library = load("slugs", "export default { of: (t) => t };")
        graphQlTester.document(
            """
            mutation {
              createFunction(input: {
                workspaceId: $workspaceId, name: "slugify",
                source: "export default function slugify(t) { return imports.slugs.of(t); }",
                typescript: "export default function slugify(t) { return imports.slugs.of(t); }",
                params: [{ name: "t", type: STRING }], returnType: STRING,
                libraries: [{ libraryId: $library, name: "slugs" }]
              }) { id }
            }
            """,
        ).execute()

        graphQlTester.document("""query { scriptLibraries { usedBy { kind name workspaceName } } }""").execute()
            .path("scriptLibraries[0].usedBy[0].kind").entity(String::class.java).isEqualTo("FUNCTION")
            .path("scriptLibraries[0].usedBy[0].name").entity(String::class.java).isEqualTo("slugify")
            .path("scriptLibraries[0].usedBy[0].workspaceName").entity(String::class.java).isEqualTo("backend")

        graphQlTester.document("""mutation { deleteScriptLibrary(id: $library) }""").execute()
            .errors().expect { it.message?.contains("imported by slugify in backend") == true }.verify()
    }

    /**
     * Who else uses a library is an administrator's question.
     *
     * The picker needs the library and not the list of everything that imports it —
     * the same line the function picker already draws around plugins.
     */
    @Test
    fun `the workspace query offers the library without saying who else uses it`() {
        val library = load("slugs", "export default { of: (t) => t };")
        graphQlTester.document(
            """
            mutation {
              createFunction(input: {
                workspaceId: $workspaceId, name: "slugify",
                source: "export default function slugify(t) { return imports.slugs.of(t); }",
                typescript: "export default function slugify(t) { return imports.slugs.of(t); }",
                params: [{ name: "t", type: STRING }], returnType: STRING,
                libraries: [{ libraryId: $library, name: "slugs" }]
              }) { id }
            }
            """,
        ).execute()

        graphQlTester.document("""query { workspaceLibraries(workspaceId: $workspaceId) { key usedBy { name } } }""")
            .execute()
            .path("workspaceLibraries[0].key").entity(String::class.java).isEqualTo("slugs")
            .path("workspaceLibraries[0].usedBy").entityList(Object::class.java).hasSize(0)
    }

    /**
     * Updating a library keeps its row, so nothing that imports it is repointed.
     *
     * The reason an import is stored as an id rather than as a key: replacing the
     * library is the ordinary way to update one, and every function that uses it
     * has to go on using it without being edited.
     */
    @Test
    fun `loading the same key again replaces it in place`() {
        val first = load("slugs", "export default { of: (t) => t };")
        val again = load("slugs", "export default { of: (t) => t, version: 2 };")

        assertThat(again).isEqualTo(first)
        assertThat(libraries.findAll()).hasSize(1)
        graphQlTester.document("""query { scriptLibraries { members { name } } }""").execute()
            .path("scriptLibraries[0].members[*].name").entityList(String::class.java)
            .containsExactly("of", "version")
    }

    /** Uploads one and answers its id. */
    private fun load(key: String, source: String): Long {
        val answered = upload.upload(file("$key.js", source), null)
        @Suppress("UNCHECKED_CAST")
        return (answered.body as Map<String, Any>)["id"] as Long
    }

    private fun file(name: String, source: String) =
        MockMultipartFile("file", name, "text/plain", source.toByteArray())

    private companion object {
        /** An entry that reaches a second file, which is what makes it a bundle. */
        val ENTRY = """
            var parse = require('./lib/parse');
            module.exports = { total: function (t) { return parse(t) + 1; } };
        """.trimIndent()

        const val PARSE = "module.exports = function (t) { return Number(t) || 0; };"
    }

}
